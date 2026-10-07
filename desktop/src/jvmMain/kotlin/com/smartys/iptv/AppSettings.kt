package com.smartys.iptv

import java.io.File

/**
 * App-Einstellungen (getrennt von den Credentials), in derselben
 * config.json unter ~/.smartys-iptv/.
 */
object AppSettings {

    private fun dir(): File = File(System.getProperty("user.home"), ".smartys-iptv")
    private fun cfgFile(): File = File(dir(), "config.json")

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

    private fun get(key: String, def: String): String {
        val json = readJson()
        return json[key] ?: def
    }

    private fun set(key: String, value: String) {
        val json = readJson()
        json[key] = value
        writeJson(json)
    }

    // ---------- Live-TV-Timeshift ----------

    /** Timeshift (Live-TV-Pause) aktiviert? */
    fun timeshiftEnabled(): Boolean = get("timeshift_enabled", "false").toBoolean()

    fun setTimeshiftEnabled(enabled: Boolean) = set("timeshift_enabled", enabled.toString())

    /** Aufnahmepfad für den Timeshift-Puffer. Default: ~/.smartys-iptv/timeshift */
    fun timeshiftDir(): String {
        val def = File(dir(), "timeshift").absolutePath
        return get("timeshift_dir", def)
    }

    fun setTimeshiftDir(path: String) = set("timeshift_dir", path)

    // ---------- Aufnahme-Verzeichnis (dauerhafte Aufnahmen) ----------

    /** Zielverzeichnis für dauerhafte Aufnahmen. Default: ~/.smartys-iptv/recordings */
    fun recordingsDir(): String {
        val def = File(dir(), "recordings").absolutePath
        return get("recordings_dir", def)
    }

    fun setRecordingsDir(path: String) = set("recordings_dir", path)

    // ---------- Aufnahme-Daemon Autostart ----------

    /** Aufnahme-Daemon beim Login automatisch starten? (Default: aus) */
    fun daemonAutostartEnabled(): Boolean = get("daemon_autostart", "false").toBoolean()

    fun setDaemonAutostartEnabled(enabled: Boolean) = set("daemon_autostart", enabled.toString())

    // ---------- Video-Decoding (HW vs SW) ----------

    /** "auto" | "hw" | "sw" */
    fun decodingMode(): String = get("decoding_mode", "auto")

    fun setDecodingMode(mode: String) = set("decoding_mode", mode)

    /**
     * libVLC-Argument für den Decoding-Modus:
     *  - auto: nichts (libVLC wählt selbst)
     *  - hw:   Hardware-Decoding forcieren
     *  - sw:   Software-Decoding forcieren (--avcodec-hw=none)
     */
    fun hwDecodingArg(): String = when (decodingMode()) {
        "hw" -> "--avcodec-hw=any"
        "sw" -> "--avcodec-hw=none"
        else -> ""
    }
}
