package com.smartys.iptv

import java.io.File
import java.security.SecureRandom

/**
 * Desktop-Variante von CredStore.
 *
 * Gleiche Pairing-Logik wie die Android-App (Player-ID + Pairing-Passwort),
 * aber ohne Android-Keystore. Persistenz läuft über eine JSON-Datei im
 * User-Home (~/.smartys-iptv/config.json).
 *
 * Hinweis: Der Android-Build verschlüsselt die Werte über EncryptedSharedPreferences
 * (AES-256). Der Desktop nutzt hier bewusst eine einfache Datei im User-Home —
 * der gleiche Schutzgrad wie ein Browser-Profil. Ein OS-Keystore (Keychain/DPAPI)
 * ist ein späterer Ausbauschritt.
 */
object CredStore {

    data class Creds(val url: String, val user: String, val pass: String) {
        fun streamUrl(streamId: String, ext: String = "m3u8"): String =
            "$url/live/$user/$pass/$streamId.$ext"

        /**
         * Timeshift-URL (Xtream-Standard): Archiv-Stream ab `startTs`
         * (Unix-Sekunden), `hours` Stunden verfügbar. Nur für Sender mit
         * tv_archive=1 — der Server liefert ab dem Zeitpunkt, dadurch kann
         * der Player pausieren/zurückspulen ohne eigene Aufzeichnung.
         */
        fun timeshiftUrl(streamId: String, startTs: Long, hours: Int): String =
            "$url/timeshift/$user/$pass/$hours/$startTs/$streamId.m3u8"

        fun movieUrl(streamId: String, ext: String = "mp4"): String =
            "$url/movie/$user/$pass/$streamId.$ext"

        fun seriesUrl(episodeId: String, ext: String = "mp4"): String =
            "$url/series/$user/$pass/$episodeId.$ext"
    }

    private fun dir(): File = File(System.getProperty("user.home"), ".smartys-iptv")
    private fun cfgFile(): File = File(dir(), "config.json")

    // ---------- Player-ID + Pairing-Passwort ----------

    /**
     * Player-ID: rein kryptographischer Zufall (SecureRandom), 6 Bytes im
     * MAC-Format — identisch zur Android-App. Byte 0 = lokal-admin-Bit (02:...)
     * => keine Kollision mit echten Hardware-MACs.
     */
    fun playerId(): String {
        val json = readJson()
        json["player_id"]?.let { return it }
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        val id = bytes.joinToString(":") { String.format("%02X", it) }
        json["player_id"] = id
        writeJson(json)
        return id
    }

    /** Pairing-Passwort: 12 Zeichen, kryptographisch sicher, einmalig generiert. */
    fun pairingPw(): String {
        val json = readJson()
        json["pairing_pw"]?.let { return it }
        val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val sb = StringBuilder(12)
        val buf = ByteArray(12)
        rnd.nextBytes(buf)
        for (b in buf) sb.append(CHARS[Math.floorMod(b.toInt(), CHARS.length)])
        val pw = sb.toString()
        json["pairing_pw"] = pw
        writeJson(json)
        return pw
    }

    // ---------- Provider-Persistenz ----------

    fun saveProviders(providers: List<Creds>) {
        val json = readJson()
        val arr = org.json.JSONArray()
        providers.forEach { c ->
            arr.put(org.json.JSONObject().put("url", c.url).put("user", c.user).put("pass", c.pass))
        }
        json["providers"] = arr.toString()
        writeJson(json)
    }

    fun loadProviders(): List<Creds> {
        val json = readJson()
        val raw = json["providers"] ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Creds(o.getString("url"), o.getString("user"), o.getString("pass"))
            }
        }.getOrDefault(emptyList())
    }

    // ---------- JSON-Datei (einfache Persistenz) ----------

    private fun readJson(): MutableMap<String, String> {
        val f = cfgFile()
        if (!f.exists()) return mutableMapOf()
        return runCatching {
            val o = org.json.JSONObject(f.readText())
            o.keys().asSequence().associateWith { o.optString(it) }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun writeJson(map: MutableMap<String, String>) {
        val d = dir()
        if (!d.exists()) d.mkdirs()
        val o = org.json.JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        cfgFile().writeText(o.toString(2))
    }
}
