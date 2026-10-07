package com.smartys.iptv

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Geplante Aufnahmen (Timer). Persistiert in
 * ~/.smartys-iptv/recordings.json — wird von App UND Hintergrund-Dienst
 * gemeinsam gelesen, damit eine Aufnahme auch bei geschlossener App startet.
 */
object RecordingStore {

    data class Recording(
        val id: String,
        val channelId: String,       // Xtream stream_id
        val channelName: String,
        val title: String,           // Sendungstitel (EPG) oder manueller Name
        val startEpoch: Long,        // Startzeit (ms)
        val endEpoch: Long,          // Endzeit (ms)
        val source: String,          // "epg" | "manual"
        val outputPath: String,      // Zieldatei
        val streamUrl: String        // aufgelöste Stream-URL (damit der Daemon ohne Auth aufnimmt)
    )

    private fun dir(): File = File(System.getProperty("user.home"), ".smartys-iptv")
    private fun file(): File = File(dir(), "recordings.json")

    fun load(): List<Recording> {
        val f = file()
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Recording(
                    o.getString("id"),
                    o.getString("channelId"),
                    o.getString("channelName"),
                    o.getString("title"),
                    o.getLong("startEpoch"),
                    o.getLong("endEpoch"),
                    o.optString("source", "manual"),
                    o.getString("outputPath"),
                    o.optString("streamUrl", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(list: List<Recording>) {
        val d = dir()
        if (!d.exists()) d.mkdirs()
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("channelId", r.channelId)
                .put("channelName", r.channelName)
                .put("title", r.title)
                .put("startEpoch", r.startEpoch)
                .put("endEpoch", r.endEpoch)
                .put("source", r.source)
                .put("outputPath", r.outputPath)
                .put("streamUrl", r.streamUrl))
        }
        file().writeText(arr.toString(2))
    }

    fun add(r: Recording) {
        val list = load().toMutableList()
        list.removeAll { it.id == r.id }
        list.add(r)
        save(list)
    }

    fun remove(id: String) {
        save(load().filter { it.id != id })
    }

    /** Generiert eine kollisionsfreie id. */
    fun newId(): String = java.util.UUID.randomUUID().toString().take(8)
}
