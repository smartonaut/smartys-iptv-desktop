package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ---------- Desktop-State (kein AndroidViewModel) ----------

sealed class AppState {
    object Pairing : AppState()
    object Loading : AppState()
    data class Ready(val categories: List<Category>, val channels: List<LiveStream>) : AppState()
    data class Error(val message: String) : AppState()
}

class DesktopViewModel {
    var state by mutableStateOf<AppState>(AppState.Pairing)
        private set
    var creds: CredStore.Creds? = null
        private set
    var allProviders: List<CredStore.Creds> = emptyList()
        private set
    private var apis: List<XtreamApi> = emptyList()

    fun restoreSession() {
        val providers = CredStore.loadProviders()
        if (providers.isEmpty()) {
            state = AppState.Pairing
            return
        }
        creds = providers.first()
        allProviders = providers
        apis = providers.map { buildApi(it.url) }
        loadContent()
    }

    /**
     * IBPlayer-Flow (identisch zur Android-App): Pairing mit panel.smartys.tech,
     * dann zugewiesene Playlist laden. Rückgabe true = Kanäle geladen.
     */
    fun pairAndLoad(playerId: String, pairingPw: String): Boolean = runCatching {
        val url = java.net.URL("https://panel.smartys.tech/api/v1/pair")
        val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use {
            it.write("mac=${URLEncoder.encode(playerId, "UTF-8")}&password=${URLEncoder.encode(pairingPw, "UTF-8")}".toByteArray())
        }
        if (conn.responseCode != 200) return@runCatching false
        val body = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        val token = body.getString("token")

        // Provider-Liste des Players abrufen:
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
            CredStore.Creds(
                p.getString("server_url"),
                p.getString("username"),
                p.getString("password")
            )
        }

        CredStore.saveProviders(providers)
        creds = providers.first()
        allProviders = providers
        apis = providers.map { buildApi(it.url) }
        loadContent()
        true
    }.getOrDefault(false)

    private fun buildApi(url: String): XtreamApi {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("$url/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XtreamApi::class.java)
    }

    private fun loadContent() {
        state = AppState.Loading
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val all = mutableListOf<LiveStream>()
                val allCats = mutableListOf<Category>()
                allProviders.forEachIndexed { idx, c ->
                    val a = apis.getOrNull(idx) ?: return@forEachIndexed
                    val cats = a.liveCategories(c.user, c.pass)
                    val streams = a.liveStreams(c.user, c.pass)
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
            } catch (e: Exception) {
                state = AppState.Error("Kanäle laden fehlgeschlagen: ${e.message}")
            }
        }
    }
}

// ---------- UI ----------

@Composable
fun App() {
    val vm = remember { DesktopViewModel() }

    // Session beim Start wiederherstellen (analog restoreSession() in Android)
    LaunchedEffect(Unit) { vm.restoreSession() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        when (val s = vm.state) {
            is AppState.Pairing -> PairingScreen(vm)
            is AppState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFFC94D))
            }
            is AppState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.message, color = Color(0xFFE8EEFC))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.restoreSession() }) { Text("Erneut versuchen") }
                }
            }
            is AppState.Ready -> ChannelList(s)
        }
    }
}

// ---------- Pairing (IBPlayer-Start, portiert aus der Android-App) ----------

@Composable
fun PairingScreen(vm: DesktopViewModel) {
    val playerId = remember { CredStore.playerId() }
    val pairingPw = remember { CredStore.pairingPw() }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Smarty Player", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC94D))
        Spacer(Modifier.height(4.dp))
        Text("Player freischalten", color = Color.Gray, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))

        Column(
            Modifier.background(Color(0xFF10141F), MaterialTheme.shapes.large).padding(horizontal = 36.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Deine Player-ID", color = Color(0xFF8FA3C8), fontSize = 13.sp)
            Text(playerId, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EEFC))
            Spacer(Modifier.height(14.dp))
            Text("Dein Passwort", color = Color(0xFF8FA3C8), fontSize = 13.sp)
            Text(pairingPw, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC94D))
        }
        Spacer(Modifier.height(24.dp))

        Text(
            "1. Gehe auf panel.smartys.tech\n" +
            "2. Melde dich mit Player-ID und Passwort an\n" +
            "3. Hinterlege dort deine Playlist — danach hier auf „Prüfen“ klicken",
            color = Color(0xFF8FA3C8), fontSize = 15.sp, textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
        Spacer(Modifier.height(22.dp))

        if (busy) {
            CircularProgressIndicator(color = Color(0xFFFFC94D))
            msg?.let { Text(it, color = Color(0xFF8FA3C8), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
        } else {
            Button(
                onClick = {
                    busy = true; msg = "Verbinde mit panel.smartys.tech…"
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { vm.pairAndLoad(playerId, pairingPw) }
                        busy = false
                        msg = when {
                            ok -> "Playlist gefunden! Lade Kanäle…"
                            else -> "Noch keine Playlist hinterlegt. Auf der Website einrichten, dann erneut prüfen."
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC94D), contentColor = Color(0xFF0B0E14))
            ) {
                Text("Prüfen — Playlist laden", Modifier.padding(horizontal = 26.dp, vertical = 6.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            msg?.let { Text(it, color = Color(0xFF8FA3C8), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp), textAlign = TextAlign.Center) }
        }
    }
}

// ---------- Kanal-Übersicht ----------

@Composable
fun ChannelList(state: AppState.Ready) {
    var selectedCat by remember { mutableStateOf<String?>(null) }
    val shown = remember(state.channels, selectedCat) {
        if (selectedCat == null) state.channels
        else state.channels.filter { it.categoryId == selectedCat }
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        LazyColumn(Modifier.width(260.dp).fillMaxHeight().background(Color(0xFF11172A))) {
            item {
                Text("Alle Kanäle", color = if (selectedCat == null) Color(0xFFFFC94D) else Color(0xFF8FA3C8),
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { selectedCat = null }.padding(16.dp))
            }
            items(state.categories) { cat ->
                Text(cat.name, color = if (selectedCat == cat.id) Color(0xFFFFC94D) else Color(0xFF8FA3C8),
                    fontWeight = if (selectedCat == cat.id) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clickable { selectedCat = cat.id }.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        LazyColumn(Modifier.weight(1f).padding(16.dp)) {
            items(shown, key = { it.streamId }) { ch ->
                Text("${ch.num ?: ""}  ${ch.name}".trimStart(), color = Color(0xFFE8EEFC), fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Smartys IPTV") {
        App()
    }
}
