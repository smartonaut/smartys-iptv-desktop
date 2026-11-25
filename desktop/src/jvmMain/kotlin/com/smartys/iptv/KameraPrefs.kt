package com.smartys.iptv

import java.io.File
import java.util.Properties

/**
 * Schalter fuer die drei Kamera-/Stream-Quellen — Gegenstueck zur Android-Fassung.
 * Liegt in ~/.smartys-iptv/kameras.properties, damit die Wahl den Neustart ueberlebt.
 */
object KameraPrefs {

    private const val FIRMA_SERVER_STANDARD = ""   // oeffentliche Fassung: kein Firmenserver fest eincodiert
    private val datei = File(System.getProperty("user.home"), ".smartys-iptv/kameras.properties")
    private val p = Properties()

    init {
        if (datei.exists()) runCatching { datei.inputStream().use { p.load(it) } }
    }

    private fun speichern() = runCatching {
        datei.parentFile?.mkdirs()
        datei.outputStream().use { p.store(it, "Smartys Player — Kamera-Quellen") }
    }.let { }

    fun firmen(): Boolean = p.getProperty("firmen", "true").toBoolean()
    fun freie(): Boolean = p.getProperty("frei", "true").toBoolean()
    fun freieStreams(): Boolean = p.getProperty("streams", "true").toBoolean()

    fun setFirmen(an: Boolean) { p.setProperty("firmen", an.toString()); speichern() }
    fun setFreie(an: Boolean) { p.setProperty("frei", an.toString()); speichern() }
    fun setFreieStreams(an: Boolean) { p.setProperty("streams", an.toString()); speichern() }

    fun firmenServer(): String =
        p.getProperty("firmen_server", FIRMA_SERVER_STANDARD).trim().ifBlank { FIRMA_SERVER_STANDARD }

    fun setFirmenServer(url: String) { p.setProperty("firmen_server", url.trim()); speichern() }

    // ---- Kamera-Wand: Layout PRO PROFIL (jeder stellt sich seinen Stil ein) ----

    fun wandSpalten(profil: String): Int =
        p.getProperty("wand_spalten_$profil", "3").toIntOrNull()?.coerceIn(1, 4) ?: 3

    fun setWandSpalten(profil: String, n: Int) {
        p.setProperty("wand_spalten_$profil", n.coerceIn(1, 4).toString()); speichern()
    }

    /** Feldbreite je Kamera (1 oder 2 — Dual-Lens belegt zwei Felder). */
    fun wandBreiten(profil: String): Map<String, Int> {
        val roh = p.getProperty("wand_breiten_$profil", "").trim()
        if (roh.isEmpty()) return emptyMap()
        return roh.split(",").mapNotNull { teil ->
            val kv = teil.split("=")
            if (kv.size != 2) null else kv[0] to (kv[1].toIntOrNull() ?: 1)
        }.toMap()
    }

    fun setWandBreiten(profil: String, map: Map<String, Int>) {
        p.setProperty("wand_breiten_$profil", map.entries.joinToString(",") { "${it.key}=${it.value}" })
        speichern()
    }

    /** Gespeicherte Reihenfolge (leer = Server-Reihenfolge). */
    fun wandReihe(profil: String): List<String> =
        p.getProperty("wand_reihe_$profil", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun setWandReihe(profil: String, ids: List<String>) {
        p.setProperty("wand_reihe_$profil", ids.joinToString(",")); speichern()
    }
}
