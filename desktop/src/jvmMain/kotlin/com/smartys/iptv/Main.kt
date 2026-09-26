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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

// ---------- Desktop-State (kein AndroidViewModel) ----------

sealed class AppState {
    object Login : AppState()
    object Loading : AppState()
    data class Ready(val categories: List<Category>, val channels: List<LiveStream>) : AppState()
    data class Error(val message: String) : AppState()
}

class DesktopViewModel {
    var state by mutableStateOf<AppState>(AppState.Login)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set
    var creds: CredStore.Creds? = null
        private set

    fun login(url: String, user: String, pass: String, scope: kotlinx.coroutines.CoroutineScope) {
        state = AppState.Loading
        loginError = null
        scope.launch {
            try {
                val clean = url.trim().trimEnd('/')
                val api = buildApi(clean)
                val auth = api.auth(user, pass)
                if (auth.userInfo.username != user) {
                    state = AppState.Login
                    loginError = "Login fehlgeschlagen (falscher Benutzername)"
                    return@launch
                }
                val creds = CredStore.Creds(clean, user, pass)
                this@DesktopViewModel.creds = creds
                val cats = api.liveCategories(user, pass)
                val streams = api.liveStreams(user, pass)
                val catNames = cats.associate { it.id to it.name }
                streams.forEach { it.categoryName = catNames[it.categoryId] ?: "" }
                state = AppState.Ready(cats, streams.filter { !it.name.contains("####") })
            } catch (e: Exception) {
                state = AppState.Login
                loginError = "Keine Verbindung: ${e.message}"
            }
        }
    }

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
}

// ---------- UI ----------

@Composable
fun App() {
    val vm = remember { DesktopViewModel() }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme()) {
        when (val s = vm.state) {
            is AppState.Login -> LoginScreen(vm, scope)
            is AppState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFFC94D))
            }
            is AppState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = Color(0xFFE8EEFC))
            }
            is AppState.Ready -> ChannelList(s)
        }
    }
}

@Composable
fun LoginScreen(vm: DesktopViewModel, scope: kotlinx.coroutines.CoroutineScope) {
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0E14)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp).background(Color(0xFF11172A), MaterialTheme.shapes.large).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Smartys IPTV", color = Color(0xFFFFC94D), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Desktop", color = Color(0xFF8FA3C8), fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(url, { url = it }, label = { Text("Provider-URL") },
                placeholder = { Text("http://line.dndnscloud.ru") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(user, { user = it }, label = { Text("Benutzername") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(pass, { pass = it }, label = { Text("Passwort") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            vm.loginError?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.login(url, user, pass, scope) },
                enabled = url.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC94D), contentColor = Color(0xFF0B0E14)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Anmelden", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ChannelList(state: AppState.Ready) {
    var selectedCat by remember { mutableStateOf<String?>(null) }
    val shown = remember(state.channels, selectedCat) {
        if (selectedCat == null) state.channels
        else state.channels.filter { it.categoryId == selectedCat }
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        // Kategorien
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
        // Kanalliste
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
