package com.smartys.iptv

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Überwacht RecordingStore und startet/stoppt Aufnahmen zur richtigen Zeit.
 * Nutzt die im Timer GESPEICHERTE Stream-URL — keine Auth/Auflösung nötig,
 * daher läuft derselbe Code in der App UND headless im Daemon.
 *
 * Robustheit (gelernt vom 0-Byte-Vorfall): der Scheduler vertraut seiner
 * aktiven ID nicht blind — er prüft jeden Tick, ob die Engine noch lebt.
 * Stirbt sie (Stream-Fehler), zählt das als Fehlversuch und nach dem
 * Backoff startet die Aufnahme neu und HÄNGT an die Teil-Datei an, statt
 * still 0 Bytes zu erzeugen. Der Lock-Herzschlag läuft nur, solange die
 * Engine wirklich schreibt — ein toter Zustand kann sich damit nicht mehr
 * unbegrenzt als "aufnehmend" tarnen.
 */
class RecordingScheduler {
    private val engine = RecordingEngine()
    private var activeId: String? = null
    private val activeFile = java.io.File(System.getProperty("user.home"), ".smartys-iptv/active_recording")

    /** Fehlversuche pro Timer-ID: id → (Anzahl Versuche, letzter Versuch ms).
     *  In-Memory: nach Prozess-Neustart beginnt die Aufnahme im selben
     *  Zeitfenster wieder mit frischen Versuchen. */
    private val attempts = ConcurrentHashMap<String, Pair<Int, Long>>()

    /** Einmaliger Tick: prüft fällige Aufnahmen. */
    suspend fun tick() {
        val now = System.currentTimeMillis()
        val list = RecordingStore.load()

        // 1) Laufende Aufnahme beenden, wenn Ende erreicht oder Timer gelöscht
        activeId?.let { id ->
            val rec = list.find { it.id == id }
            if (rec == null) {
                stopActive("Timer gelöscht")
            } else if (now >= rec.endEpoch) {
                stopActive("Ende erreicht")
            }
        }

        // 2) Engine-Tod erkennen: Scheduler hält die Aufnahme für aktiv, aber
        //    die Engine ist still (Stream-Fehler). Neustart zählt als Fehlversuch
        //    (Backoff), die Teil-Datei bleibt für den Anhang-Neustart erhalten.
        if (activeId != null && !engine.recording) {
            val id = activeId!!
            val rec = list.find { it.id == id }
            DaemonLog.log(
                "scheduler",
                "Aufnahme '${rec?.title ?: id}' abgestorben (${engine.deathReason ?: "unbekannt"}) — Neustart nach Backoff"
            )
            engine.stop() // Reste der toten Engine freigeben (löscht auch den Lock)
            activeId = null
            val (n, _) = attempts[id] ?: (0 to 0L)
            attempts[id] = (n + 1) to now
        }

        // 3) Lock-Herzschlag nur, solange die Engine wirklich schreibt
        if (activeId != null && engine.recording) RecordingEngine.touchLock()

        // 4) Fällige Aufnahme starten (die nächste, die jetzt dran ist)
        if (activeId == null) {
            // Verwaiste active_recording-Datei aufräumen (Prozess-Tod ohne Cleanup)
            if (activeFile.exists() && !RecordingEngine.isLocked()) activeFile.delete()

            val due = list
                .filter { now >= it.startEpoch && now < it.endEpoch && it.streamUrl.isNotBlank() }
                .minByOrNull { it.startEpoch } ?: return
            val (n, lastTry) = attempts[due.id] ?: (0 to 0L)
            if (n >= MAX_ATTEMPTS) return
            if (n > 0 && now - lastTry < RETRY_BACKOFF) return

            val out = File(due.outputPath)
            val append = out.exists() && out.length() > 0
            DaemonLog.log(
                "scheduler",
                "Starte Aufnahme: '${due.title}' (${due.channelName})${if (append) " [append]" else ""}"
            )
            if (engine.start(due.streamUrl, out, append)) {
                activeId = due.id
                activeFile.writeText(due.id)
                attempts.remove(due.id)
            } else {
                attempts[due.id] = (n + 1) to now
                DaemonLog.log(
                    "scheduler",
                    "Start fehlgeschlagen (Versuch ${n + 1}/$MAX_ATTEMPTS): '${due.title}'"
                )
            }
        }
    }

    private fun stopActive(why: String) {
        val id = activeId
        engine.stop()
        activeId = null
        activeFile.delete()
        DaemonLog.log("scheduler", "Aufnahme beendet ($why): $id")
    }

    fun stop() {
        stopActive("Scheduler-Stopp")
        engine.release()
    }

    fun isRecording(): Boolean = engine.recording

    companion object {
        private const val MAX_ATTEMPTS = 8
        private const val RETRY_BACKOFF = 30_000L
    }
}