package com.smartys.iptv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mehrsprachige Oberfläche — Sprachen liegen als Dateien, nicht im Code.
 *
 * **Neue Sprache hinzufügen** (ohne Codeänderung):
 * 1. `desktop/src/jvmMain/resources/i18n/<code>.json` anlegen, Inhalt
 *    `{ "Einstellungen": "…", … }` — Schlüssel ist immer der deutsche Text.
 * 2. In `resources/i18n/index.json` eine Zeile ergänzen:
 *    `{ "code": "fr", "label": "Français", "rtl": false }`
 * 3. Bauen. Der Umschalter in den Einstellungen liest den Index und zeigt die
 *    Sprache automatisch an.
 *
 * Fehlt ein Schlüssel, erscheint der deutsche Text — nie ein leeres Label.
 * Eine Sprache darf also unvollständig ausgeliefert und später ergänzt werden.
 *
 * `rtl: true` spiegelt die Oberfläche (Persisch, Urdu, Arabisch, Hebräisch).
 */
object I18n {

    /** Eine Sprache: Code (ISO 639-1), Anzeigename, Leserichtung, Tabelle. */
    data class Sprache(
        val code: String,
        val label: String,
        val rtl: Boolean = false,
        val tabelle: Map<String, String>
    )

    private const val INDEX_PFAD = "/i18n/index.json"

    /** Wird beim ersten Zugriff aus dem Index + den JSON-Dateien gebaut. */
    val alle: List<Sprache> by lazy { indexLaden() }

    /** Aktive Sprache. */
    var sprache: Sprache = Sprache("de", "Deutsch", false, emptyMap())
        private set

    /** Zähler für Compose: ein Sprachwechsel zeichnet die Oberfläche neu. */
    var version by mutableStateOf(0)
        private set

    private fun text(pfad: String): String? = runCatching {
        I18n::class.java.getResourceAsStream(pfad)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    private fun indexLaden(): List<Sprache> {
        val roh = text(INDEX_PFAD) ?: return listOf(Sprache("de", "Deutsch", false, emptyMap()))
        val liste = mutableListOf<Sprache>()
        runCatching {
            val arr = JSONArray(roh)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val code = o.optString("code").lowercase()
                if (code.isBlank()) continue
                liste.add(
                    Sprache(
                        code = code,
                        label = o.optString("label", code),
                        rtl = o.optBoolean("rtl", false),
                        tabelle = tabelleLaden(code)
                    )
                )
            }
        }
        return liste.ifEmpty { listOf(Sprache("de", "Deutsch", false, emptyMap())) }
    }

    private fun tabelleLaden(code: String): Map<String, String> {
        val roh = text("/i18n/$code.json") ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        runCatching {
            val o = JSONObject(roh)
            o.keys().forEach { schluessel ->
                val wert = o.optString(schluessel)
                if (wert.isNotBlank()) map[schluessel] = wert
            }
        }
        return map
    }

    fun setze(neu: Sprache) {
        if (sprache.code == neu.code) return
        sprache = neu
        AppSettings.setLanguage(neu.code)
        version++
    }

    /** Lädt die gespeicherte Sprache (beim App-Start aufrufen). */
    fun laden() {
        val code = AppSettings.language()
        sprache = alle.firstOrNull { it.code == code }
            ?: alle.firstOrNull { it.code == "de" }
            ?: alle.first()
    }

    /** Übersetzt einen festen Text; fällt auf Deutsch zurück. */
    fun s(de: String): String =
        if (sprache.code == "de") de else sprache.tabelle[de] ?: de

    /**
     * Übersetzt mit Platzhaltern: `sf("Noch {0}", "12 Min")`.
     * In den Vorlagen `{0}`, `{1}` … statt String-Interpolation verwenden.
     */
    fun sf(de: String, vararg werte: Any?): String {
        var ergebnis = s(de)
        werte.forEachIndexed { i, wert ->
            ergebnis = ergebnis.replace("{$i}", wert?.toString() ?: "")
        }
        return ergebnis
    }

    /** Schreibrichtung der aktiven Sprache. */
    fun istRtl(): Boolean = sprache.rtl

    /** Für Verifikation: Anzahl der Sprachen bzw. Übersetzungen der aktiven. */
    fun anzahlSprachen(): Int = alle.size

    fun anzahlUebersetzungen(): Int = sprache.tabelle.size
}
