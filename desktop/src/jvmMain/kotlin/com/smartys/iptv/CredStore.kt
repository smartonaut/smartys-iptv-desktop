package com.smartys.iptv

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    data class Creds(val url: String, val user: String, val pass: String, val label: String = "") {
        /** Stabiler Schlüssel für die Profil→Line-Bindung (URL+User sind eindeutig). */
        val key: String get() = "$url|$user"

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
     * Player-ID nach PLAYER-IDENTITY.md — Aufl�sungsreihenfolge:
     *  1. gespeicherte player_id in config.json: unantastbar, wird NIE geaendert
     *  2. Identitaets-Spiegel (~/.config/smartys-iptv/identity.json), falls
     *     config.json keine ID hat — der Spiegel ueberlebt App-Reinstall und
     *     Loeschen des Profil-Verzeichnisses
     *  3. deterministische Ableitung aus Hardware-Merkmalen (machineSeed)
     *  4. kryptographischer Zufall, NUR wenn keine Merkmale lesbar sind
     *
     * Die Herkunft wird in player_id_source vermerkt ("mirror"/"hw-v1"/"random")
     * — "random" bedeutet ausdruecklich NICHT stabil. Nach jeder Neuermittlung
     * wird der Spiegel aktualisiert; eine dort stehende ANDERE ID bleibt
     * unangetastet (der Spiegel ist ein Gedaechtnis, kein Cache).
     */
    fun playerId(): String {
        val json = readJson()

        // Stufe 1: bereits gespeicherte ID ist unantastbar — nur lesen.
        json["player_id"]?.takeIf { it.isNotEmpty() }?.let { return it }

        // Stufe 2: Identitaet aus dem Spiegel uebernehmen (kein Neu-Wuerfeln).
        val mirror = readMirror()
        mirror["player_id"]?.takeIf { it.isNotEmpty() }?.let { id ->
            json["player_id"] = id
            // Quellen-Info aus dem Spiegel erhalten (stabile IDs bleiben als solche erkennbar).
            json["player_id_source"] = mirror["player_id_source"]?.takeIf { it.isNotEmpty() } ?: "mirror"
            // Ging das Profil-Verzeichnis verloren, aber der Spiegel hat die
            // Pairing-Daten ueberlebt: mit uebernehmen — nur solange config.json
            // selbst keine pairing_pw hat (vorhandene Werte werden nie ersetzt).
            val mirrorPw = mirror["pairing_pw"]
            if (json["pairing_pw"].isNullOrEmpty() && !mirrorPw.isNullOrEmpty()) {
                json["pairing_pw"] = mirrorPw
            }
            writeJson(json)
            return id
        }

        // Stufe 3: deterministisch aus geraetestabilen Merkmalen ableiten.
        val seed = machineSeed()
        val id: String
        val source: String
        if (seed.isNotEmpty()) {
            id = derivePlayerId(seed)
            source = "hw-v1"
        } else {
            // Stufe 4: keine Merkmale lesbar -> Zufall (nicht stabil).
            id = randomMacLikeId()
            source = "random"
        }
        json["player_id"] = id
        json["player_id_source"] = source
        writeJson(json)
        updateMirror(id, json["pairing_pw"], source)
        return id
    }

    /** 6 Zufalls-Bytes im MAC-Format, Byte 0 = lokal administriert (02:...) — wie die Android-App. */
    private fun randomMacLikeId(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        return bytes.joinToString(":") { String.format("%02X", it) }
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
        // Neu ermitteltes Pairing-Passwort auch in den Identitaets-Spiegel
        // schreiben (siehe playerId()) — eine dort stehende andere player_id
        // bleibt unberuehrt.
        updateMirror(json["player_id"], pw, json["player_id_source"])
        return pw
    }

    // ---------- Provider-Persistenz ----------

    fun saveProviders(providers: List<Creds>) {
        val json = readJson()
        val arr = org.json.JSONArray()
        providers.forEach { c ->
            arr.put(org.json.JSONObject().put("url", c.url).put("user", c.user)
                .put("pass", c.pass).put("label", c.label))
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
                Creds(o.getString("url"), o.getString("user"), o.getString("pass"),
                      o.optString("label", ""))
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

    // ---------- Identitaets-Spiegel (~/.config/smartys-iptv/identity.json) ----------

    /**
     * Der Spiegel ueberlebt App-Reinstall und Loeschen des Profil-Verzeichnisses
     * (~/.smartys-iptv) und erlaubt der App nach einer Neuinstallation, dieselbe
     * Player-ID wiederzufinden — die Panel-Line (AAD-gebunden an die ID) bleibt
     * damit erreichbar. Gelesen wird er NUR wenn config.json keine ID hat.
     */
    private fun mirrorFile(): File {
        // XDG_CONFIG_HOME respektieren (relative Pfade sind laut XDG-Spec ungueltig).
        val xdg = System.getenv("XDG_CONFIG_HOME")?.trim()
            ?.takeIf { it.isNotEmpty() && File(it).isAbsolute }
            ?: File(System.getProperty("user.home"), ".config").absolutePath
        return File(File(xdg, "smartys-iptv"), "identity.json")
    }

    private fun readMirrorMap(f: File): MutableMap<String, String> {
        if (!f.exists()) return mutableMapOf()
        return runCatching {
            val o = org.json.JSONObject(f.readText())
            o.keys().asSequence().associateWith { o.optString(it) }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun readMirror(): MutableMap<String, String> = readMirrorMap(mirrorFile())

    /**
     * Spiegel nach jeder Ermittlung aktualisieren — atomar (temp + move).
     * Steht dort bereits eine ANDERE player_id, wird sie NIEMALS ueberschrieben:
     * Der Spiegel ist ein Gedaechtnis, kein Cache.
     */
    private fun updateMirror(playerId: String?, pairingPw: String?, source: String?) {
        runCatching {
            val f = mirrorFile()
            val cur = readMirrorMap(f)
            if (!playerId.isNullOrEmpty()) {
                val existing = cur["player_id"]
                if (existing.isNullOrEmpty() || existing == playerId) cur["player_id"] = playerId
            }
            if (!pairingPw.isNullOrEmpty()) cur["pairing_pw"] = pairingPw
            if (!source.isNullOrEmpty()) cur["player_id_source"] = source
            val o = org.json.JSONObject()
            cur.forEach { (k, v) -> o.put(k, v) }
            val dir = f.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            val tmp = File(dir, ".identity.json.tmp")
            tmp.writeText(o.toString(2))
            try {
                Files.move(tmp.toPath(), f.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                // Dateisystem ohne atomaren Move: wenigstens temporaer und ganz schreiben.
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
