package com.smartys.iptv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Sendungserinnerungen (Parität zur iOS-App).
 *
 * Auf dem Desktop ist das deutlich einfacher als auf iOS: Die App darf im
 * Hintergrund leben, also reicht ein Timer, der die anstehenden Sendungen
 * prüft und eine System-Benachrichtigung über das TrayIcon schickt.
 *
 * Ablage: ~/.smartys-iptv/erinnerungen.json
 */
object Erinnerungen {

    /** Vorlaufzeit in Minuten — wie in der iOS-App. */
    const val VORLAUF_MINUTEN = 15

    data class Eintrag(
        val id: String,
        val titel: String,
        val sender: String,
        /** Beginn der Sendung (Epoch-Millis). */
        val startMs: Long
    ) {
        /** Wann die Meldung kommt: 15 Minuten vor Beginn. */
        val feuerzeitMs: Long get() = startMs - VORLAUF_MINUTEN * 60_000L
    }

    private val datei: File
        get() = File(System.getProperty("user.home"), ".smartys-iptv/erinnerungen.json")

    private val eintraege = java.util.concurrent.ConcurrentHashMap<String, Eintrag>()
    private var gestartet = false

    /** Lädt gespeicherte Erinnerungen und startet den Prüf-Timer. */
    @Synchronized
    fun start() {
        if (gestartet) return
        gestartet = true
        laden()
        thread(isDaemon = true, name = "erinnerungen") {
            while (true) {
                runCatching { pruefen() }
                Thread.sleep(30_000)
            }
        }
    }

    private fun laden() {
        runCatching {
            val f = datei
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val jetzt = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Eintrag(
                    id = o.getString("id"),
                    titel = o.getString("titel"),
                    sender = o.optString("sender"),
                    startMs = o.getLong("startMs")
                )
                // Vergangene Sendungen gar nicht erst übernehmen
                if (e.startMs > jetzt) eintraege[e.id] = e
            }
        }
    }

    private fun speichern() {
        runCatching {
            val arr = JSONArray()
            eintraege.values.sortedBy { it.startMs }.forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("id", e.id)
                        put("titel", e.titel)
                        put("sender", e.sender)
                        put("startMs", e.startMs)
                    }
                )
            }
            datei.parentFile?.mkdirs()
            datei.writeText(arr.toString(2))
        }
    }

    fun alle(): List<Eintrag> = eintraege.values.sortedBy { it.startMs }

    fun istGeplant(id: String): Boolean = eintraege.containsKey(id)

    /** Legt eine Erinnerung an (oder entfernt sie, wenn sie schon existiert). */
    @Synchronized
    fun umschalten(id: String, titel: String, sender: String, startMs: Long): Boolean {
        return if (eintraege.remove(id) != null) {
            speichern()
            false
        } else {
            // Nur planen, wenn die Feuerzeit noch nicht vorbei ist
            val e = Eintrag(id, titel, sender, startMs)
            if (e.feuerzeitMs <= System.currentTimeMillis()) return false
            eintraege[id] = e
            speichern()
            true
        }
    }

    @Synchronized
    fun entfernen(id: String) {
        if (eintraege.remove(id) != null) speichern()
    }

    /** Prüft fällige Erinnerungen und schickt eine System-Meldung. */
    private fun pruefen() {
        val jetzt = System.currentTimeMillis()
        val faellig = eintraege.values.filter { it.feuerzeitMs <= jetzt && it.startMs > jetzt - 60_000L }
        faellig.forEach { e ->
            melden(
                titel = "Gleich: ${e.titel}",
                text = if (e.sender.isNotBlank())
                    "${e.sender} — Beginn in $VORLAUF_MINUTEN Minuten"
                else "Beginn in $VORLAUF_MINUTEN Minuten"
            )
            eintraege.remove(e.id)
        }
        // Abgelaufene Einträge mit aufräumen
        val abgelaufen = eintraege.values.filter { it.startMs < jetzt - 3_600_000L }
        if (abgelaufen.isNotEmpty() || faellig.isNotEmpty()) {
            abgelaufen.forEach { eintraege.remove(it.id) }
            speichern()
        }
    }

    /** System-Benachrichtigung über das TrayIcon (Linux/Windows gleichermaßen). */
    private fun melden(titel: String, text: String) {
        runCatching {
            if (java.awt.SystemTray.isSupported()) {
                val tray = TrayHolder.tray()
                tray?.displayMessage(titel, text, java.awt.TrayIcon.MessageType.INFO)
                return
            }
        }
        // Fallback: Desktop-Benachrichtigung über notify-send (Linux)
        runCatching {
            ProcessBuilder("notify-send", "-a", "Smartys IPTV", titel, text).start()
        }.onFailure {
            println("Erinnerung: $titel — $text")
        }
    }
}

/** Hält ein einziges TrayIcon für Benachrichtigungen. */
private object TrayHolder {
    private var icon: java.awt.TrayIcon? = null

    @Synchronized
    fun tray(): java.awt.TrayIcon? {
        icon?.let { return it }
        return runCatching {
            val tray = java.awt.SystemTray.getSystemTray()
            val bild = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = bild.createGraphics()
            g.color = java.awt.Color(0xFF, 0xC9, 0x4D)
            g.fillOval(2, 2, 12, 12)
            g.dispose()
            val neu = java.awt.TrayIcon(bild, "Smartys IPTV")
            neu.isImageAutoSize = true
            tray.add(neu)
            icon = neu
            neu
        }.getOrNull()
    }
}
