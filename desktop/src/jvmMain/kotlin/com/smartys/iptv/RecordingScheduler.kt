package com.smartys.iptv

import kotlinx.coroutines.delay
import java.io.File

/**
 * Überwacht RecordingStore und startet/stoppt Aufnahmen zur richtigen Zeit.
 * Nutzt die im Timer GESPEICHERTE Stream-URL — keine Auth/Auflösung nötig,
 * daher läuft derselbe Code in der App UND headless im Daemon.
 */
class RecordingScheduler {
    private val engine = RecordingEngine()
    private var activeId: String? = null
    private val activeFile = java.io.File(System.getProperty("user.home"), ".smartys-iptv/active_recording")
    /** Timer-IDs, deren Start fehlgeschlagen ist (In-Memory, damit der Timer im
     *  Store sichtbar bleibt, aber nicht jeden Tick (10 s) erneut probiert wird). */
    private val skippedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Einmaliger Tick: prüft fällige Aufnahmen. */
    suspend fun tick() {
        val now = System.currentTimeMillis()
        val list = RecordingStore.load()

        // 1) Laufende Aufnahme beenden, wenn Ende erreicht oder Timer gelöscht
        activeId?.let { id ->
            val rec = list.find { it.id == id }
            if (rec == null || now >= rec.endEpoch) {
                stopActive()
            }
        }

        // 2) Lock-Herzschlag erneuern, solange eine Aufnahme läuft (sonst läuft
        //    die 30-s-Frische von isLocked() ab, obwohl noch aufgenommen wird).
        if (activeId != null) RecordingEngine.touchLock()

        // 3) Fällige Aufnahme starten (die nächste, die jetzt dran ist und noch nicht läuft)
        if (activeId == null) {
            val due = list
                .filter { now >= it.startEpoch && now < it.endEpoch && it.streamUrl.isNotBlank() }
                .filter { it.id !in skippedIds }
                .minByOrNull { it.startEpoch }
            if (due != null) {
                val ok = engine.start(due.streamUrl, File(due.outputPath))
                if (ok) {
                    activeId = due.id
                    activeFile.writeText(due.id)
                } else {
                    // Start fehlgeschlagen (z. B. Stream tot): diesen Timer nur für
                    // diese Session überspringen — sonst würde er jeden Tick (10 s)
                    // erneut probiert. Der Timer bleibt im Store sichtbar.
                    skippedIds.add(due.id)
                }
            }
        }
    }

    private fun stopActive() {
        engine.stop()
        activeId = null
        activeFile.delete()
    }

    fun stop() {
        stopActive()
        engine.release()
    }

    fun isRecording(): Boolean = engine.recording
}
