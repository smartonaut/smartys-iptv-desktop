package com.smartys.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

data class XmltvProgramme(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String?
)

/**
 * EPG aus der XMLTV-Quelle des Providers (xmltv.php). Desktop-Variante:
 * StAX-Parser (JDK) statt Android XmlPullParser, Cache in ~/.smartys-iptv/.
 */
object XmltvEpg {

    private val index = HashMap<String, MutableList<XmltvProgramme>>()
    private val fallbackIndex = HashMap<String, MutableList<XmltvProgramme>>()
    @Volatile private var loaded = false
    @Volatile private var loading = false
    var lastRefreshMs: Long = 0
        private set

    fun isReady(): Boolean = loaded

    private fun cacheDir(): File {
        val d = File(System.getProperty("user.home"), ".smartys-iptv/cache")
        d.mkdirs()
        return d
    }

    suspend fun ensureLoaded(xmltvUrl: String, forceRefresh: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (loaded && !forceRefresh) return@withContext true
            if (loading) return@withContext loaded
            loading = true
            try {
                val cache = File(cacheDir(), "xmltv.xml")
                val stale = !cache.exists() ||
                    System.currentTimeMillis() - cache.lastModified() > 12 * 3600 * 1000L
                if (forceRefresh || stale) {
                    val tmp = File(cacheDir(), "xmltv.xml.part")
                    URL(xmltvUrl).openStream().use { input ->
                        tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
                    }
                    if (!tmp.renameTo(cache)) {
                        // Rename kann auf manchen Dateisystemen schiefgehen, wenn
                        // das Ziel noch offen ist — dann direkt kopieren + Rest löschen.
                        tmp.copyTo(cache, overwrite = true)
                        tmp.delete()
                    }
                }
                parse(cache)
                loaded = true
                lastRefreshMs = System.currentTimeMillis()
                true
            } catch (e: Exception) {
                loaded = try {
                    val cache = File(cacheDir(), "xmltv.xml")
                    if (cache.exists()) { parse(cache); true } else false
                } catch (e2: Exception) { false }
                loaded
            } finally {
                loading = false
            }
        }

    private fun parse(file: File) {
        val newIndex = HashMap<String, MutableList<XmltvProgramme>>()
        val f = XMLInputFactory.newFactory()
        FileInputStream(file).use { fis ->
            val r = f.createXMLStreamReader(fis, "utf-8")
            var curChannel: String? = null
            var curStart = 0L
            var curStop = 0L
            var curTitle: String? = null
            var curDesc: String? = null
            var inTitle = false
            var inDesc = false
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "programme" -> {
                            curChannel = r.getAttributeValue(null, "channel")
                            curStart = parseXmltvTime(r.getAttributeValue(null, "start"))
                            curStop = parseXmltvTime(r.getAttributeValue(null, "stop"))
                            curTitle = null; curDesc = null
                        }
                        "title" -> inTitle = true
                        "desc" -> inDesc = true
                    }
                    XMLStreamConstants.CHARACTERS -> {
                        if (inTitle) curTitle = r.text
                        if (inDesc) curDesc = r.text
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                        "programme" -> {
                            val ch = curChannel
                            if (ch != null && curTitle != null && curStart > 0) {
                                newIndex.getOrPut(ch) { mutableListOf() }
                                    .add(XmltvProgramme(curStart, curStop, curTitle!!.trim(), curDesc?.trim()))
                            }
                            inTitle = false; inDesc = false
                        }
                    }
                }
            }
            r.close()
        }
        newIndex.values.forEach { it.sortBy { p -> p.start } }
        synchronized(index) { index.clear(); index.putAll(newIndex) }
    }

    suspend fun loadFallback(): Boolean = withContext(Dispatchers.IO) {
        try {
            val cache = File(cacheDir(), "epg_pw_de.xml")
            val stale = !cache.exists() ||
                System.currentTimeMillis() - cache.lastModified() > 24 * 3600 * 1000L
            if (stale) {
                val tmp = File(cacheDir(), "epg_pw_de.xml.part")
                val conn = URL("https://epg.pw/xmltv/epg_DE.xml.gz").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("Accept-Encoding", "gzip")
                conn.inputStream.use { input ->
                    java.util.zip.GZIPInputStream(input).use { gz ->
                        tmp.outputStream().use { gz.copyTo(it, 1 shl 16) }
                    }
                }
                tmp.renameTo(cache)
            }
            parseFallback(cache)
            true
        } catch (e: Exception) { false }
    }

    private fun parseFallback(file: File) {
        val newIndex = HashMap<String, MutableList<XmltvProgramme>>()
        val f = XMLInputFactory.newFactory()
        FileInputStream(file).use { fis ->
            val r = f.createXMLStreamReader(fis, "utf-8")
            val idToName = HashMap<String, String>()
            var curId: String? = null
            var inDisplayName = false
            var inTitle = false
            var inDesc = false
            var curChannel: String? = null
            var curStart = 0L
            var curStop = 0L
            var curTitle: String? = null
            var curDesc: String? = null
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "channel" -> curId = r.getAttributeValue(null, "id")
                        "display-name" -> inDisplayName = true
                        "programme" -> {
                            curChannel = r.getAttributeValue(null, "channel")
                            curStart = parseXmltvTime(r.getAttributeValue(null, "start"))
                            curStop = parseXmltvTime(r.getAttributeValue(null, "stop"))
                            curTitle = null; curDesc = null
                        }
                        "title" -> inTitle = true
                        "desc" -> inDesc = true
                    }
                    XMLStreamConstants.CHARACTERS -> {
                        if (inDisplayName && curId != null) idToName[curId!!] = r.text.trim()
                        if (inTitle) curTitle = r.text
                        if (inDesc) curDesc = r.text
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "display-name" -> inDisplayName = false
                        "channel" -> curId = null
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                        "programme" -> {
                            val ch = curChannel
                            if (ch != null && curTitle != null && curStart > 0) {
                                val key = idToName[ch]?.let { normName(it) } ?: ch
                                newIndex.getOrPut(key) { mutableListOf() }
                                    .add(XmltvProgramme(curStart, curStop, curTitle!!.trim(), curDesc?.trim()))
                            }
                            inTitle = false; inDesc = false
                        }
                    }
                }
            }
            r.close()
        }
        newIndex.values.forEach { it.sortBy { p -> p.start } }
        synchronized(fallbackIndex) { fallbackIndex.clear(); fallbackIndex.putAll(newIndex) }
    }

    private fun parseXmltvTime(s: String?): Long = try {
        if (s == null) 0L else {
            val core = s.substringBefore(" ").take(14)
            val tzSecs: Int = when {
                "+" in s -> s.substringAfterLast("+").let { o ->
                    (o.take(2).toIntOrNull() ?: 0) * 3600 + (o.drop(2).take(2).toIntOrNull() ?: 0) * 60
                }
                "-" in s.substring(9) -> s.substringAfterLast("-").let { o ->
                    -((o.take(2).toIntOrNull() ?: 0) * 3600 + (o.drop(2).take(2).toIntOrNull() ?: 0) * 60)
                }
                else -> 0
            }
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(core)?.time?.minus(tzSecs * 1000L) ?: 0L
        }
    } catch (e: Exception) { 0L }

    fun normName(n: String): String = n.lowercase()
        .replace("de - ", "").replace("de:", "").replace("de-", "")
        .replace("uhd", "").replace("fhd", "").replace("hd", "")
        .replace("4k", "").replace("ä", "a").replace("ö", "o").replace("ü", "u")
        .replace("ß", "ss").replace(".", "").replace(":", "").replace("+", "")
        .replace("&", "").replace("(", "").replace(")", "").replace("!", "")
        .replace(" ", "").trim()

    fun getNowAndNext(channelId: String?, now: Long = System.currentTimeMillis()): Pair<XmltvProgramme?, XmltvProgramme?> {
        if (channelId.isNullOrBlank()) return null to null
        val progs = synchronized(index) { index[channelId] } ?: return null to null
        return nowAndNextFrom(progs, now)
    }

    fun fallbackNowAndNext(channelName: String?, now: Long = System.currentTimeMillis()): Pair<XmltvProgramme?, XmltvProgramme?> {
        if (channelName.isNullOrBlank()) return null to null
        val key = normName(channelName)
        val progs = synchronized(fallbackIndex) { fallbackIndex[key] } ?: return null to null
        return nowAndNextFrom(progs, now)
    }

    fun programmesFor(channelId: String?): List<XmltvProgramme> {
        if (channelId.isNullOrBlank()) return emptyList()
        return synchronized(index) { index[channelId]?.toList() } ?: emptyList()
    }

    fun programmesForName(channelName: String?): List<XmltvProgramme> {
        if (channelName.isNullOrBlank()) return emptyList()
        val key = normName(channelName)
        return synchronized(fallbackIndex) { fallbackIndex[key]?.toList() } ?: emptyList()
    }

    private fun nowAndNextFrom(progs: List<XmltvProgramme>, now: Long): Pair<XmltvProgramme?, XmltvProgramme?> {
        if (progs.isEmpty()) return null to null
        var lo = 0; var hi = progs.size - 1
        var cur: XmltvProgramme? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (progs[mid].start <= now) { cur = progs[mid]; lo = mid + 1 } else hi = mid - 1
        }
        if (cur != null && cur.stop in 1..now) cur = null
        val next: XmltvProgramme? = if (cur != null) {
            val byStop = progs.indexOfFirst { cur.stop > 0 && it.start >= cur.stop }
            if (byStop >= 0) progs[byStop] else progs.getOrNull(progs.indexOf(cur) + 1)
        } else {
            progs.getOrNull(progs.indexOfFirst { it.start > now })
        }
        return cur to next
    }

    fun hhmm(epoch: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch))

    fun clearCache() {
        synchronized(this) { index.clear(); fallbackIndex.clear(); loaded = false }
    }
}
