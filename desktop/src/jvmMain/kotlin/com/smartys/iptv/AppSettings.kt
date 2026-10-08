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

    // ---------- Video-Decoding (automatisch erkannt) ----------

    /**
     * Gewünschter Modus: "auto" (Standard, empfohlen) | "hw" | "sw".
     * Bei "auto" erkennt der Player selbst, ob Hardware-Decoding auf diesem
     * System sauber läuft, und wählt dementsprechend.
     */
    fun decodingMode(): String = get("decoding_mode", "auto")

    fun setDecodingMode(mode: String) = set("decoding_mode", mode)

    /** Hybrid-GPU erkennen: mehr als ein GPU-Vendor (z. B. Intel-iGPU + NVIDIA-dGPU).
     *  Genau diese Systeme liefern mit libVLVs VAAPI/VDPAU-Hardware-Decoding
     *  grüne Blöcke bei H.264 — dort bevorzugen wir Software-Decoding. */
    private fun isHybridGpu(): Boolean {
        val vendors = mutableSetOf<String>()
        val drm = File("/sys/class/drm")
        if (!drm.exists()) return false
        drm.listFiles()?.forEach { card ->
            val v = File(card, "device/vendor")
            if (v.exists()) runCatching { vendors.add(v.readText().trim()) }
        }
        return vendors.size > 1
    }

    /**
     * Effektive libVLC-Argumente für den Decoding-Modus:
     *  - hw:   Hardware-Decoding forcieren
     *  - sw:   Software-Decoding erzwingen (--avcodec-hw=none + --codec avcodec)
     *  - auto: Hybrid-GPU → Software (vermeidet grüne Blöcke); sonst Hardware
     *          zulassen (libVLC wählt den besten verfügbaren Decoder).
     *
     *  Zusätzlich --glconv=none: auf Hybrid-Systemen (Intel+NVIDIA) läuft sonst
     *  die Video-AUSGABE über die VAAPI/VDPAU-GL-Konvertierung, was auch bei
     *  sauberem Software-Decoding grüne Blöcke am unteren Bildrand erzeugt.
     */
    fun hwDecodingArgs(): List<String> = when (decodingMode()) {
        "hw" -> listOf("--avcodec-hw=any")
        "sw" -> listOf("--avcodec-hw=none", "--codec", "avcodec", "--glconv=none")
        else -> if (isHybridGpu()) listOf("--avcodec-hw=none", "--codec", "avcodec", "--glconv=none")
                else emptyList()
    }
}
