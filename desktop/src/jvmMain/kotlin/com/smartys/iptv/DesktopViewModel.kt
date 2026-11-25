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
    data class NeedsUnlock(val name: String) : AppState()
    data class Ready(val categories: List<Category>, val channels: List<LiveStream>) : AppState()
    data class Error(val message: String) : AppState()
}

class DesktopViewModel {

    private val scope = CoroutineScope(Dispatchers.IO)

    var state by mutableStateOf<AppState>(AppState.Pairing)
        private set

    /** Verschlüsselte Panel-Payload, wartet auf die Passwort-Eingabe im Unlock-Screen. */
    var pendingUnlock: SecCrypto.UnlockPayload? = null

    var creds by mutableStateOf<CredStore.Creds?>(null)
        private set
    var allProviders by mutableStateOf<List<CredStore.Creds>>(emptyList())
        private set
    /** Anzeigename der aktiven Line ("Alex2") — für Einstellungen/Statuszeile. */
    var activeLineLabel by mutableStateOf<String>("")
        private set
    private var apis: List<XtreamApi> = emptyList()
    private var api: XtreamApi? = null

    // Favoriten + zuletzt gesehen (pro Profil)
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var recentIds by mutableStateOf<List<String>>(emptyList())
        private set
    /** Meine Liste (gemerkte Filme + Serien, profilgebunden, neueste zuerst). */
    var watchItems by mutableStateOf<List<WatchItem>>(emptyList())
        private set
    /** Aktive Profil-ID als Compose-State: Screens hängen ihre remember-Keys daran,
     *  damit profilgebundene Einstellungen (ausgeblendete Gruppen, Sortierung)
     *  beim Profilwechsel sofort neu gelesen werden. */
    var activeProfileId by mutableStateOf<String?>(ProfileStore.activeId())
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
        allProviders = providers
        apis = providers.map { buildApi(it) }
        applyActiveLine()
        loadContent()
        reloadProfileState()
    }

    // ---------- Profil -> Panel-Line ----------

    /** Index der Line, die am aktiven Profil hängt (null = nicht gebunden). */
    private fun boundProviderIndex(): Int? {
        val profileId = ProfileStore.activeId() ?: return null
        val key = ProfileStore.lineKey(profileId) ?: return null
        val idx = allProviders.indexOfFirst { it.key == key }
        return if (idx >= 0) idx else null
    }

    /** Alle Lines für die Auswahl in den Einstellungen: (Index, Anzeigename). */
    fun lines(): List<Pair<Int, String>> = allProviders.mapIndexed { i, c ->
        i to c.label.ifBlank { "Line ${i + 1}" }
    }

    /** Name der Line, die an einem Profil hängt (null = "Automatisch"). */
    fun lineLabelFor(profileId: String): String? {
        val key = ProfileStore.lineKey(profileId) ?: return null
        val idx = allProviders.indexOfFirst { it.key == key }
        if (idx < 0) return null
        return allProviders[idx].label.ifBlank { "Line ${idx + 1}" }
    }

    /** Profil an eine Line binden (index = null -> "Automatisch"). */
    fun setProfileLine(profileId: String, index: Int?) {
        ProfileStore.setLineKey(profileId, index?.let { allProviders.getOrNull(it)?.key })
        if (profileId == ProfileStore.activeId()) {
            applyActiveLine()
            loadContent()
        }
    }

    /**
     * Aktive Line aus der Profil-Bindung setzen: VOD/Serien/EPG/Aufnahmen laufen
     * über `creds`, Live-Kanäle werden in loadContent() auf die Line gefiltert.
     * Ohne Bindung: erste Line (bisheriges Verhalten).
     */
    private fun applyActiveLine() {
        val idx = boundProviderIndex() ?: 0
        val p = allProviders.getOrNull(idx) ?: return
        val changed = creds?.key != p.key
        creds = p
        api = apis.getOrNull(idx) ?: apis.firstOrNull()
        activeLineLabel = p.label.ifBlank { "Line ${idx + 1}" }
        if (changed) {
            vodCategories = emptyList(); vodMovies = emptyList()
            seriesCategories = emptyList(); seriesShows = emptyList()
            vodError = null; vodLoading = false
        }
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

        // Sicherheits-Passwort-Feature: Lines verschlüsselt (AES-256-GCM,
        // PBKDF2 310k, AAD=Player-MAC) — Klartext-Parsing hier unmoeglich.
        if (respBody.optBoolean("encrypted", false)) {
            pendingUnlock = SecCrypto.UnlockPayload(
                name = respBody.optString("name", "Player"),
                saltB64 = respBody.optString("salt", ""),
                iterations = respBody.optInt("iterations", 310000),
                providers = (0 until providersArr.length()).map { i ->
                    val p = providersArr.getJSONObject(i)
                    SecCrypto.EncProvider(
                        label = p.optString("label", ""),
                        cipherServerUrl = p.optString("cipher_server_url", ""),
                        cipherUsername = p.optString("cipher_username", ""),
                        cipherPassword = p.optString("cipher_password", ""),
                    )
                }
            )
            state = AppState.NeedsUnlock(respBody.optString("name", "Player"))
            return@runCatching true   // Pairing erfolgreich — Unlock folgt im eigenen Screen
        }

        val providers = (0 until providersArr.length()).map { i ->
            val p = providersArr.getJSONObject(i)
            CredStore.Creds(p.getString("server_url"), p.getString("username"),
                p.getString("password"), p.optString("label", ""))
        }
        CredStore.saveProviders(providers)
        allProviders = providers
        apis = providers.map { buildApi(it) }
        applyActiveLine()
        loadContent()
        true
    }.getOrDefault(false)

    /**
     * Unlock-Screen: verschluesselte Panel-Payload mit dem Sicherheits-Passwort
     * entschlüsseln (lokal, PBKDF2 310k + AES-256-GCM). Bei falschem Passwort
     * false (Felder bleiben leer, Backend-Vertrag).
     */
    fun unlockWithSecPw(password: String): Boolean {
        val data = pendingUnlock ?: return false
        val playerId = CredStore.playerId()
        val key = SecCrypto.deriveKey(password, data.saltB64, data.iterations)
        val provs = data.providers.map { ep ->
            CredStore.Creds(
                SecCrypto.decryptField(key, ep.cipherServerUrl, playerId),
                SecCrypto.decryptField(key, ep.cipherUsername, playerId),
                SecCrypto.decryptField(key, ep.cipherPassword, playerId),
                ep.label,
            )
        }.filter { it.url.isNotBlank() && it.user.isNotBlank() }
        if (provs.isEmpty()) return false
        CredStore.saveProviders(provs)
        allProviders = provs
        apis = provs.map { buildApi(it) }
        applyActiveLine()
        pendingUnlock = null
        loadContent()
        return true
    }

    /** Unlock abbrechen: zurueck zum Pairing-Screen, Payload verwerfen. */
    fun cancelUnlock() {
        pendingUnlock = null
        state = AppState.Pairing
    }

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
                // Mehrere Lines liefern dieselben Gruppennamen mit verschiedenen
                // category_id. Kanäle auf die kanonische id je Gruppe normalisieren,
                // damit jede Gruppe nur EINMAL erscheint und ein Klick auf die
                // Gruppe alle Kanäle über alle Lines zusammen zeigt.
                val canonBy = linkedMapOf<String, String>()   // Name(lower) -> erste id
                allCats.forEach { canonBy.putIfAbsent(it.name.trim().lowercase(), it.id) }
                clean.forEach { ch ->
                    val key = ch.categoryName.trim().lowercase()
                    if (key.isNotEmpty()) canonBy[key]?.let { ch.categoryId = it }
                }
                val dedupCats = allCats.distinctBy { it.name.trim().lowercase() }
                // Profil an eine Line gebunden? Dann nur deren Kanäle + Kategorien
                // zeigen. Ohne Bindung bleibt das bisherige Verhalten (alle Lines
                // zusammengeführt, Wiedergabe je Kanal über providerIndex).
                val bIdx = boundProviderIndex()
                if (bIdx != null) {
                    val chs = clean.filter { it.providerIndex == bIdx }
                    val catIds = chs.map { it.categoryId }.toSet()
                    state = AppState.Ready(dedupCats.filter { it.id in catIds }, chs)
                } else {
                    state = AppState.Ready(dedupCats, clean)
                }
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
        val c = creds
        val a = api
        if (vodLoading) return
        vodLoading = true; vodError = null
        scope.launch {
            var vonLine: List<VodStream> = emptyList()
            var cats: List<Category> = emptyList()
            if (c != null && a != null) {
                try {
                    cats = withTimeout(30000) { a.vodCategories(c.user, c.pass) }
                    vonLine = withTimeout(60000) { a.vodStreams(c.user, c.pass) }
                    val catNames = cats.associate { it.id to it.name }
                    vonLine.forEach { it.categoryName = catNames[it.categoryId] ?: "" }
                } catch (e: Exception) {
                    vodError = "VOD laden fehlgeschlagen: ${e.message}"
                }
            }
            // Zweite Quelle: Jellyfin (eigener Server im LAN). Faellt still aus,
            // wenn kein Token hinterlegt ist — die Line bleibt dann allein.
            val jf = jfFilme()
            vodCategories = if (jf.isEmpty()) cats
                            else listOf(Category("jf-filme", "Jellyfin")) + cats
            vodMovies = jf + vonLine
            vodLoading = false
        }
    }

    fun loadSeries() {
        val c = creds
        val a = api
        scope.launch {
            var vonLine: List<SeriesStream> = emptyList()
            var cats: List<Category> = emptyList()
            if (c != null && a != null) {
                try {
                    cats = withTimeout(30000) { a.seriesCategories(c.user, c.pass) }
                    vonLine = withTimeout(60000) { a.seriesStreams(c.user, c.pass) }
                    val catNames = cats.associate { it.id to it.name }
                    vonLine.forEach { it.categoryName = catNames[it.categoryId] ?: "" }
                } catch (e: Exception) {
                    // Fail-soft: Serien sind optional
                }
            }
            val jf = jfSerien()
            seriesCategories = if (jf.isEmpty()) cats
                               else listOf(Category("jf-serien", "Jellyfin")) + cats
            seriesShows = jf + vonLine
        }
    }

    // ---------- Jellyfin (zweite Quelle) ----------

    /** Infozeile fuer die Einstellungen: Server, Benutzer, Status. */
    var jfStatus by mutableStateOf<String?>(null)

    private suspend fun jfFilme(): List<VodStream> {
        val a = Jellyfin.gespeichert() ?: return emptyList()
        return runCatching { Jellyfin.filme(a) }.getOrElse { emptyList() }
            .also { if (it.isNotEmpty()) jfStatus = "${it.size} Filme von ${a.userName}" }
    }

    private suspend fun jfSerien(): List<SeriesStream> {
        val a = Jellyfin.gespeichert() ?: return emptyList()
        return runCatching { Jellyfin.serien(a) }.getOrElse { emptyList() }
    }

    /** Server im LAN suchen, anmelden, Token merken und Inhalte nachladen. */
    fun jfVerbinden(base: String?, user: String, pw: String) {
        scope.launch {
            try {
                jfStatus = "suche Server …"
                val server = if (!base.isNullOrBlank()) null else Jellyfin.suchen().firstOrNull()
                val ziel = base?.takeIf { it.isNotBlank() } ?: server?.base
                if (ziel == null) { jfStatus = "kein Jellyfin im Netz gefunden"; return@launch }
                jfStatus = "melde an bei $ziel …"
                val auth = Jellyfin.anmelden(ziel, user, pw)
                if (auth == null) { jfStatus = "Anmeldung abgelehnt — Benutzer oder Passwort falsch"; return@launch }
                jfStatus = "angemeldet als ${auth.userName}"
                loadVod()
                loadSeries()
            } catch (e: Exception) {
                jfStatus = "Jellyfin: ${e.message}"
            }
        }
    }

    fun jfAbmelden() {
        Jellyfin.merken(null)
        jfStatus = null
        vodMovies = vodMovies.filterNot { it.streamId.startsWith("jf:") }
        seriesShows = seriesShows.filterNot { it.seriesId.startsWith("jf:") }
    }

    // ---------- Wiedergabe-Adressen (zwei Quellen) ----------

    /** Film: Jellyfin-IDs ("jf:…") laufen ueber HLS, alles andere ueber die Line. */
    fun movieUrlFor(streamId: String, ext: String?): String? {
        if (streamId.startsWith("jf:")) {
            val a = Jellyfin.gespeichert() ?: return null
            return Jellyfin.videoUrl(a, streamId)
        }
        return creds?.movieUrl(streamId, ext ?: "mp4")
    }

    /** Folge: wie oben, nur mit dem Serien-Pfad der Line. */
    fun episodeUrlFor(episodeId: String, ext: String?): String? {
        if (episodeId.startsWith("jf:")) {
            val a = Jellyfin.gespeichert() ?: return null
            return Jellyfin.videoUrl(a, episodeId)
        }
        return creds?.seriesUrl(episodeId, ext ?: "mp4")
    }

    suspend fun fetchSeriesInfo(seriesId: String): SeriesInfo? {
        // Jellyfin-Serien haben ihre Folgen nicht bei Xtream — eigener Weg.
        if (seriesId.startsWith("jf:")) {
            val a = Jellyfin.gespeichert() ?: return null
            return runCatching { Jellyfin.folgen(a, seriesId) }.getOrNull()
        }
        return runCatching {
            val c = creds ?: return@runCatching null
            val a = api ?: return@runCatching null
            withTimeout(15000) { a.seriesInfo(c.user, c.pass, seriesId = seriesId) }
        }.getOrNull()
    }

    /** TMDB-Metadaten (Beschreibung, Genres, Laufzeit) für einen Film. */
    fun fetchTmdb(tmdbId: String?, onResult: (TmdbMeta.MovieMeta?) -> Unit) {
        val id = tmdbId ?: return
        scope.launch(Dispatchers.IO) {
            onResult(TmdbMeta.fetchMovie(id))
        }
    }

    fun streamUrlFor(channel: LiveStream): String? {
        channel.directUrl?.let { return it }   // Demo-Modus: direkte URL ohne Xtream
        val c = allProviders.getOrNull(channel.providerIndex) ?: creds ?: return null
        return c.streamUrl(channel.streamId)
    }

    /** Demo-Modus: legale öffentlich-rechtliche Sender ohne Pairing/Xtream abspielen. */
    fun startDemoMode() {
        state = AppState.Ready(DemoChannels.kategorien(), DemoChannels.build())
        // EPG ohne Xtream: freies Fallback-EPG (epg.pw) laden, nowNext() fällt
        // bei Demo-Kanälen (epgChannelId=null) auf fallbackNowAndNext(name) zurück.
        scope.launch {
            if (XmltvEpg.loadFallback()) fallbackReady.value = true
        }
    }

    /** Timeshift-URL: Archiv-Stream ab `startTs` (Unix-Sekunden). */
    fun timeshiftUrlFor(channel: LiveStream, startTs: Long, hours: Int): String? {
        val c = allProviders.getOrNull(channel.providerIndex) ?: creds ?: return null
        return c.timeshiftUrl(channel.streamId, startTs, hours)
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

    /** Öffentlich für das Programm-Raster: EPG erneut vom Provider holen. */
    fun epgNachladen() = loadXmltvEpg()

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

    // ---------- Meine Liste ----------

    /** true, wenn der Titel in "Meine Liste" steht (liest State → Compose reagiert). */
    fun isMarked(kind: String, id: String): Boolean =
        watchItems.any { it.kind == kind && it.id == id }

    /** Merkt den Titel bzw. nimmt ihn wieder heraus. */
    fun toggleWatch(item: WatchItem) {
        watchItems = WatchlistStore.toggle(item)
    }

    fun removeWatch(kind: String, id: String) {
        watchItems = WatchlistStore.remove(kind, id)
    }

    fun clearWatchlist() {
        watchItems = WatchlistStore.clear()
    }

    fun reloadProfileState() {
        activeProfileId = ProfileStore.activeId()
        favorites = FavoritesStore.favorites()
        recentIds = FavoritesStore.recentIds()
        watchItems = WatchlistStore.items()

        // Profilwechsel: Senderliste IMMER verwerfen und neu laden — die Profile
        // können an unterschiedliche Panel-Lines/Anbieter gebunden sein.
        // EPG/VOD/Serien-Caches gehören ebenfalls zur alten Line.
        epgCache.clear()
        epgInFlight.clear()
        vodCategories = emptyList(); vodMovies = emptyList()
        seriesCategories = emptyList(); seriesShows = emptyList()
        vodError = null; vodLoading = false
        applyActiveLine()
        loadContent()
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
        val payload = org.json.JSONObject().put("mac", mac).put("message", message).put("app_version", "1.5.0")
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
