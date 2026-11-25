package com.smartys.iptv

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Ein heruntergeladener VOD-Titel (Filme + Serien-Episoden). */
data class DownloadItem(
    val id: String,
    val title: String,
    val url: String,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String,   // running | paused | complete | error
    val error: String? = null
) {
    val progress: Float get() =
        if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * VOD-Downloader mit HTTP-Range-Resume (Desktop-Variante, gleiche Logik wie Android).
 * Persistenz über java.util.prefs + .part-Dateien in ~/.smartys-iptv/downloads.
 */
object VodDownloader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val paused = ConcurrentHashMap<String, Boolean>()

    val items = mutableStateListOf<DownloadItem>()

    fun downloadsDir(): File =
        File(System.getProperty("user.home"), ".smartys-iptv/downloads")

    fun load() {
        val json = java.util.prefs.Preferences.userRoot()
            .node("com/smartys/iptv/downloads").get("items_json", null) ?: return
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return
        items.clear()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items.add(
                DownloadItem(
                    o.getString("id"), o.getString("title"), o.getString("url"),
                    o.getString("fileName"), o.optLong("total", 0L),
                    o.optLong("downloaded", 0L), o.optString("status", "error"),
                    o.optString("error").takeIf { it.isNotBlank() }
                )
            )
        }
    }

    private fun prefs() = java.util.prefs.Preferences.userRoot().node("com/smartys/iptv/downloads")

    private fun persist() {
        val arr = org.json.JSONArray()
        items.forEach { d ->
            arr.put(
                org.json.JSONObject()
                    .put("id", d.id).put("title", d.title).put("url", d.url)
                    .put("fileName", d.fileName).put("total", d.totalBytes)
                    .put("downloaded", d.downloadedBytes).put("status", d.status)
                    .put("error", d.error ?: "")
            )
        }
        prefs().put("items_json", arr.toString())
    }

    private fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) items[idx] = transform(items[idx])
    }

    fun start(id: String, title: String, url: String, ext: String) {
        if (items.any { it.id == id && it.status == "complete" }) return
        if (jobs.containsKey(id)) return
        val fileName = "$id.$ext"
        paused[id] = false
        val existing = items.indexOfFirst { it.id == id }
        if (existing >= 0) update(id) { it.copy(status = "running", error = null) }
        else items.add(DownloadItem(id, title, url, fileName, 0L, 0L, "running"))
        persist()
        jobs[id] = scope.launch { runDownload(id) }
    }

    private suspend fun runDownload(id: String) {
        val dir = downloadsDir(); dir.mkdirs()
        val item = items.firstOrNull { it.id == id } ?: return
        val partFile = File(dir, item.fileName + ".part")
        val finalFile = File(dir, item.fileName)

        var raf: RandomAccessFile? = null
        try {
            var startFrom = if (partFile.exists()) partFile.length() else 0L
            var conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=$startFrom-")
                setRequestProperty("User-Agent", "SmartysIPTV/1.5.0 (okhttp)")
            }
            var code = conn.responseCode
            val resume = code == 206 && startFrom > 0L
            var total = if (resume)
                conn.getHeaderField("Content-Range")?.substringAfter("/")?.toLongOrNull()
            else null
            if (total == null) total = conn.contentLength.toLong().takeIf { it > 0 } ?: -1L

            if (code == 200 && startFrom > 0L) {
                partFile.delete(); startFrom = 0L
                conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000
                    setRequestProperty("Range", "bytes=0-")
                    setRequestProperty("User-Agent", "SmartysIPTV/1.5.0 (okhttp)")
                }
                code = conn.responseCode
            }

            raf = RandomAccessFile(partFile, "rw")
            if (code == 206) raf.seek(startFrom) else raf.setLength(0)
            update(id) { it.copy(totalBytes = total, downloadedBytes = startFrom) }

            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var downloaded = startFrom
                var lastUiUpdate = 0L
                while (true) {
                    if (paused[id] == true) { persist(); return }
                    val read = input.read(buf)
                    if (read < 0) break
                    raf.write(buf, 0, read)
                    downloaded += read
                    // Fortschritt drosseln (nicht bei jedem 64-KB-Chunk in die UI schreiben).
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= 250) {
                        update(id) { it.copy(downloadedBytes = downloaded) }
                        lastUiUpdate = now
                    }
                }
                update(id) { it.copy(downloadedBytes = downloaded) }
            }
            raf.close(); raf = null

            // Vollständigkeits-Prüfung: nie "complete" bei 0 Bytes oder Truncation.
            val got = partFile.length()
            when {
                got <= 0L -> {
                    partFile.delete()
                    update(id) { it.copy(downloadedBytes = 0L, status = "error", error = "Server lieferte keine Daten (0 Bytes)") }
                    persist()
                }
                total > 0L && got < total -> {
                    update(id) { it.copy(downloadedBytes = got, status = "error", error = "Unvollständig: $got von $total Bytes") }
                    persist()
                }
                else -> {
                    partFile.renameTo(finalFile)
                    update(id) { it.copy(downloadedBytes = finalFile.length(), status = "complete", error = null) }
                    persist()
                }
            }
        } catch (e: Exception) {
            if (paused[id] != true && jobs[id]?.isCancelled != true) {
                update(id) { it.copy(status = "error", error = e.message) }
                persist()
            }
        } finally {
            raf?.close()
            jobs.remove(id)
        }
    }

    fun pause(id: String) {
        paused[id] = true
        update(id) { it.copy(status = "paused") }
        jobs[id]?.cancel(); jobs.remove(id)
    }

    fun resume(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.status == "complete") return
        paused[id] = false
        update(id) { it.copy(status = "running", error = null) }
        persist()
        jobs[id] = scope.launch { runDownload(id) }
    }

    fun delete(id: String) {
        jobs[id]?.cancel(); jobs.remove(id); paused.remove(id)
        val item = items.firstOrNull { it.id == id }
        items.removeAll { it.id == id }
        persist()
        if (item != null) {
            val dir = downloadsDir()
            File(dir, item.fileName).delete()
            File(dir, item.fileName + ".part").delete()
        }
    }

    fun localFile(id: String): File? {
        val item = items.firstOrNull { it.id == id } ?: return null
        if (item.status != "complete") return null
        val f = File(downloadsDir(), item.fileName)
        return if (f.exists()) f else null
    }
}
