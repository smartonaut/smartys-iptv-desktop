package com.smartys.iptv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

sealed class AppState {
    object Pairing : AppState()
    object Loading : AppState()
    data class Ready(val categories: List<Category>, val channels: List<LiveStream>) : AppState()
    data class Error(val message: String) : AppState()
}

class DesktopViewModel {

    private val scope = CoroutineScope(Dispatchers.IO)

    var state by mutableStateOf<AppState>(AppState.Pairing)
        private set

    var creds by mutableStateOf<CredStore.Creds?>(null)
        private set
    var allProviders by mutableStateOf<List<CredStore.Creds>>(emptyList())
        private set
    private var apis: List<XtreamApi> = emptyList()
    private var api: XtreamApi? = null

    // Favoriten + zuletzt gesehen (pro Profil)
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var recentIds by mutableStateOf<List<String>>(emptyList())
        private set

    // VOD (Filme + Serien)
    var vodCategories by mutableStateOf<List<Category>>(emptyList())
        private set
    var vodMovies by mutableStateOf<List<VodStream>>(emptyList())
        private set
    var seriesCategories by mutableStateOf<List<Category>>(emptyList())
        private set
    var seriesShows by mutableStateOf<List<SeriesStream>>(emptyList())
        private set
    var vodLoading by mutableStateOf(false)
        private set
    var vodError by mutableStateOf<String?>(null)
        private set

    // EPG
    val epgCache = mutableStateMapOf<String, List<EpgEntry>>()
    private val epgInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val epgSemaphore = Semaphore(6)
    val xmltvReady = mutableStateOf(false)
    val fallbackReady = mutableStateOf(false)

    // Aufnahme-Scheduler (nutzt die im Timer gespeicherte Stream-URL; läuft in
    // der App UND headless im RecordingDaemon)
    private val scheduler = RecordingScheduler()

    /** Startet den Scheduler-Tick-Loop (einmal pro App-Lauf aufrufen). */
    fun startScheduler() {
        scope.launch {
            while (true) {
                scheduler.tick()
                kotlinx.coroutines.delay(10_000)
            }
        }
    }

    fun isRecording(): Boolean = scheduler.isRecording()

    private fun buildApi(c: CredStore.Creds): XtreamApi {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("${c.url}/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XtreamApi::class.java)
    }

    fun restoreSession() {
        val providers = CredStore.loadProviders()
        if (providers.isEmpty()) {
            state = AppState.Pairing
            return
        }
        creds = providers.first()
        allProviders = providers
        apis = providers.map { buildApi(it) }
        api = apis.firstOrNull()
        loadContent()
        reloadProfileState()
    }

    /** IBPlayer-Flow: Pairing mit panel.smartys.tech, dann zugewiesene Playlist laden. */
    fun pairAndLoad(playerId: String, pairingPw: String): Boolean = runCatching {
        val url = java.net.URL("https://panel.smartys.tech/api/v1/pair")
        val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use {
            it.write("mac=${java.net.URLEncoder.encode(playerId, "UTF-8")}&password=${java.net.URLEncoder.encode(pairingPw, "UTF-8")}".toByteArray())
        }
        if (conn.responseCode != 200) return@runCatching false
        val body = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        val token = body.getString("token")
        val purl = java.net.URL("https://panel.smartys.tech/api/v1/providers")
        val pconn = purl.openConnection() as javax.net.ssl.HttpsURLConnection
        pconn.setRequestProperty("Authorization", "Bearer $token")
        pconn.connectTimeout = 8000; pconn.readTimeout = 8000
        if (pconn.responseCode != 200) return@runCatching false
        val respBody = org.json.JSONObject(pconn.inputStream.bufferedReader().readText())
        val providersArr = respBody.optJSONArray("providers") ?: org.json.JSONArray()
        if (providersArr.length() == 0) return@runCatching false
        val providers = (0 until providersArr.length()).map { i ->
            val p = providersArr.getJSONObject(i)
            CredStore.Creds(p.getString("server_url"), p.getString("username"), p.getString("password"))
        }
        CredStore.saveProviders(providers)
        creds = providers.first()
        allProviders = providers
        apis = providers.map { buildApi(it) }
        api = apis.firstOrNull()
        loadContent()
        true
    }.getOrDefault(false)

    private fun loadContent() {
        state = AppState.Loading
        scope.launch {
            try {
                val providers = allProviders
                if (providers.isEmpty()) return@launch
                val all = mutableListOf<LiveStream>()
                val allCats = mutableListOf<Category>()
                providers.forEachIndexed { idx, c ->
                    val a = apis.getOrNull(idx) ?: return@forEachIndexed
                    val cats = withTimeout(30000) { a.liveCategories(c.user, c.pass) }
                    val streams = withTimeout(60000) { a.liveStreams(c.user, c.pass) }
                    val catNames = cats.associate { it.id to it.name }
                    streams.forEach {
                        it.categoryName = catNames[it.categoryId] ?: ""
                        it.providerIndex = idx
                    }
                    all.addAll(streams)
                    allCats.addAll(cats)
                }
                val clean = all.filter { !it.name.contains("####") }
                state = AppState.Ready(allCats, clean)
                // EPG + VOD/Serien laden
                loadXmltvEpg()
                loadVod()
                loadSeries()
            } catch (e: Exception) {
                state = AppState.Error("Kanäle laden fehlgeschlagen: ${e.message}")
            }
        }
    }

    fun retry() = loadContent()

    fun loadVod() {
        val c = creds ?: return
        val a = api ?: return
        if (vodLoading) return
        vodLoading = true; vodError = null
        scope.launch {
            try {
                val cats = withTimeout(30000) { a.vodCategories(c.user, c.pass) }
                val movies = withTimeout(60000) { a.vodStreams(c.user, c.pass) }
                val catNames = cats.associate { it.id to it.name }
                movies.forEach { it.categoryName = catNames[it.categoryId] ?: "" }
                vodCategories = cats
                vodMovies = movies
            } catch (e: Exception) {
                vodError = "VOD laden fehlgeschlagen: ${e.message}"
            } finally {
                vodLoading = false
            }
        }
    }

    fun loadSeries() {
        val c = creds ?: return
        val a = api ?: return
        scope.launch {
            try {
                val cats = withTimeout(30000) { a.seriesCategories(c.user, c.pass) }
                val shows = withTimeout(60000) { a.seriesStreams(c.user, c.pass) }
                val catNames = cats.associate { it.id to it.name }
                shows.forEach { it.categoryName = catNames[it.categoryId] ?: "" }
                seriesCategories = cats
                seriesShows = shows
            } catch (e: Exception) {
                // Fail-soft: Serien sind optional
            }
        }
    }

    suspend fun fetchSeriesInfo(seriesId: String): SeriesInfo? = runCatching {
        val c = creds ?: return@runCatching null
        val a = api ?: return@runCatching null
        withTimeout(15000) { a.seriesInfo(c.user, c.pass, seriesId = seriesId) }
    }.getOrNull()

    fun streamUrlFor(channel: LiveStream): String? {
        val c = allProviders.getOrNull(channel.providerIndex) ?: creds ?: return null
        return c.streamUrl(channel.streamId)
    }

    fun epgFor(channel: LiveStream) {
        val id = channel.streamId
        if (epgCache.containsKey(id)) return
        if (!epgInFlight.add(id)) return
        val c = creds ?: run { epgInFlight.remove(id); return }
        scope.launch {
            epgSemaphore.withPermit {
                try {
                    val epg = withTimeout(8000) { api?.shortEpg(c.user, c.pass, streamId = id) }
                    val list = (epg?.epgListings.orEmpty()).map { e ->
                        e.copy(
                            title = e.title?.let { decodeB64(it) } ?: e.title,
                            start = e.start?.let { fmtTime(it) },
                            end = e.end?.let { fmtTime(it) }
                        )
                    }
                    epgCache[id] = list
                } catch (e: Exception) {
                    epgCache[id] = emptyList()
                } finally {
                    epgInFlight.remove(id)
                }
            }
        }
    }

    fun prefetchEpg(channels: List<LiveStream>, count: Int = 30) {
        channels.take(count).forEach { epgFor(it) }
    }

    private fun loadXmltvEpg() {
        val c = creds ?: return
        scope.launch {
            val url = "${c.url}/xmltv.php?username=${c.user}&password=${c.pass}"
            val ok = XmltvEpg.ensureLoaded(url)
            if (ok) {
                xmltvReady.value = true
                val fbOk = XmltvEpg.loadFallback()
                if (fbOk) fallbackReady.value = true
            }
        }
    }

    fun nowNext(ch: LiveStream): Pair<XmltvProgramme?, XmltvProgramme?> {
        val (cur, next) = XmltvEpg.getNowAndNext(ch.epgChannelId)
        if (cur != null || next != null) return cur to next
        if (ch.epgChannelId.isNullOrBlank()) return XmltvEpg.fallbackNowAndNext(ch.name)
        return null to null
    }

    private fun decodeB64(s: String): String? = try {
        String(java.util.Base64.getDecoder().decode(s.trim()), Charsets.UTF_8).trim()
    } catch (e: Exception) { s }

    private fun fmtTime(xt: String): String = try {
        xt.split(" ").getOrNull(1)?.take(5) ?: xt
    } catch (e: Exception) { xt }

    fun toggleFavorite(streamId: String) {
        favorites = FavoritesStore.toggle(streamId)
    }

    fun markWatched(streamId: String) {
        recentIds = FavoritesStore.markWatched(streamId)
    }

    fun reloadProfileState() {
        favorites = FavoritesStore.favorites()
        recentIds = FavoritesStore.recentIds()
    }

    fun fetchPlayerStatus(playerId: String): Pair<Boolean, Boolean>? = runCatching {
        val url = java.net.URL("https://panel.smartys.tech/api/v1/player/info?mac=$playerId")
        val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        if (conn.responseCode != 200) return@runCatching null
        val body = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        Pair(body.optBoolean("exists"), body.optBoolean("active"))
    }.getOrNull()

    fun sendSupport(mac: String, message: String): Boolean = runCatching {
        val payload = org.json.JSONObject().put("mac", mac).put("message", message).put("app_version", "1.4")
        val conn = java.net.URL("https://panel.smartys.tech/api/v1/support").openConnection() as javax.net.ssl.HttpsURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        conn.responseCode == 200
    }.getOrDefault(false)

    fun clearEpg() {
        xmltvReady.value = false
        XmltvEpg.clearCache()
    }
}
