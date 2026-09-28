package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.launch

// ---------- Farben (identisch zur Android-App) ----------

val Bg = Color(0xFF0B0E14)
val Sidebar = Color(0xFF11172A)
val Card = Color(0xFF10141F)
val TextHi = Color(0xFFE8EEFC)
val TextMid = Color(0xFF8FA3C8)
val Accent = Color(0xFFFFC94D)
val AccentDark = Color(0xFF0B0E14)
val Danger = Color(0xFFFF6B6B)

// ---------- Navigation ----------

enum class Tab(val label: String) {
    LIVE("Live-TV"), MOVIES("Filme"), SERIES("Serien"), DOWNLOADS("Downloads"), SETTINGS("Einstellungen")
}

data class VodPlaying(
    val title: String,
    val url: String,
    val key: String,      // Resume-Schlüssel
    val streamId: String, // für Zuletzt-gesehen
    val poster: String?,
    val isEpisode: Boolean = false,
    val next: (() -> Unit)? = null  // Auto-Advance zur nächsten Episode
)

@Composable
fun App() {
    val vm = remember { DesktopViewModel() }
    LaunchedEffect(Unit) { vm.restoreSession() }

    var tab by remember { mutableStateOf(Tab.LIVE) }
    var liveChannel by remember { mutableStateOf<LiveStream?>(null) }
    var vodPlaying by remember { mutableStateOf<VodPlaying?>(null) }
    var showSeries by remember { mutableStateOf<SeriesStream?>(null) }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, background = Bg, surface = Card,
        onPrimary = AccentDark, onBackground = TextHi, onSurface = TextHi
    )) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            when (val s = vm.state) {
                is AppState.Pairing -> PairingScreen(vm)
                is AppState.Loading -> CenteredBox { CircularProgressIndicator(color = Accent) }
                is AppState.Error -> CenteredBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message, color = TextHi, fontSize = 15.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.retry() }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentDark)) { Text("Erneut versuchen") }
                    }
                }
                is AppState.Ready -> {
                    // Haupt-Layout: Tab-Leiste + Inhalt
                    Column(Modifier.fillMaxSize()) {
                        TabBar(tab) { tab = it }
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                Tab.LIVE -> LiveTvScreen(vm, s, onPlay = { liveChannel = it })
                                Tab.MOVIES -> MoviesScreen(vm,
                                    onOpenSeries = { showSeries = it },
                                    onPlayMovie = { m ->
                                        val c = vm.creds
                                        val url = c?.movieUrl(m.streamId, m.containerExtension ?: "mp4") ?: return@MoviesScreen
                                        val key = "vod:movie:${m.streamId}"
                                        vodPlaying = VodPlaying(m.name, url, key, m.streamId, m.poster(), false, null)
                                    })
                                Tab.SERIES -> SeriesScreen(vm, onOpen = { showSeries = it })
                                Tab.DOWNLOADS -> DownloadsScreen(vm,
                                    onPlay = { file, title, id ->
                                        vodPlaying = VodPlaying(title, file.toURI().toString(), "vod:download:$id", id, null, false, null)
                                    })
                                Tab.SETTINGS -> SettingsScreen(vm)
                            }
                        }
                    }

                    // Serien-Detail (Overlay)
                    showSeries?.let { show ->
                        SeriesDetailScreen(vm, show,
                            onBack = { showSeries = null },
                            onPlayEpisode = { allEps, idx ->
                                val ep = allEps.getOrNull(idx) ?: return@SeriesDetailScreen
                                val c = vm.creds ?: return@SeriesDetailScreen
                                val url = c.seriesUrl(ep.id, ep.containerExtension ?: "mp4")
                                val key = "series:${ep.id}"
                                val title = "${show.name} — ${ep.title ?: "Episode ${ep.episodeNum}"}"
                                // Auto-Advance: nächste Episode (falls vorhanden)
                                val next: (() -> Unit)? = allEps.getOrNull(idx + 1)?.let { nextEp ->
                                    {
                                        val nextKey = "series:${nextEp.id}"
                                        val nextTitle = "${show.name} — ${nextEp.title ?: "Episode ${nextEp.episodeNum}"}"
                                        vodPlaying = VodPlaying(
                                            nextTitle,
                                            c.seriesUrl(nextEp.id, nextEp.containerExtension ?: "mp4"),
                                            nextKey, nextEp.id, show.cover, true, null
                                        )
                                    }
                                }
                                vodPlaying = VodPlaying(title, url, key, ep.id, show.cover, true, next)
                            })
                    }

                    // Live-Player (Overlay)
                    liveChannel?.let { ch ->
                        val url = vm.streamUrlFor(ch)
                        if (url != null) {
                            LivePlayerScreen(ch, url, vm, onBack = {
                                liveChannel = null
                            })
                        } else {
                            liveChannel = null
                        }
                    }

                    // VOD-Player (Overlay, inkl. Auto-Advance bei Serien)
                    vodPlaying?.let { vp ->
                        VodPlayerScreen(vp, vm, onBack = {
                            vodPlaying = null
                        })
                    }
                }
            }
        }
    }
}

@Composable
internal fun CenteredBox(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
}

@Composable
fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Sidebar).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Smartys IPTV", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        Tab.entries.forEach { t ->
            val active = t == selected
            Text(
                t.label,
                color = if (active) Accent else TextMid,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onSelect(t) }.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

// ---------- Pairing (portiert aus der Android-App) ----------

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
        Text("Smartys Player", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Accent)
        Spacer(Modifier.height(4.dp))
        Text("Player freischalten", color = Color.Gray, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))

        Column(
            Modifier.background(Card, MaterialTheme.shapes.large).padding(horizontal = 36.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Deine Player-ID", color = TextMid, fontSize = 13.sp)
            Text(playerId, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextHi)
            Spacer(Modifier.height(14.dp))
            Text("Dein Passwort", color = TextMid, fontSize = 13.sp)
            Text(pairingPw, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Accent)
        }
        Spacer(Modifier.height(24.dp))

        Text(
            "1. Gehe auf panel.smartys.tech\n" +
            "2. Melde dich mit Player-ID und Passwort an\n" +
            "3. Hinterlege dort deine Playlist — danach hier auf „Prüfen“ klicken",
            color = TextMid, fontSize = 15.sp,
            lineHeight = 24.sp
        )
        Spacer(Modifier.height(22.dp))

        if (busy) {
            CircularProgressIndicator(color = Accent)
            msg?.let { Text(it, color = TextMid, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
        } else {
            Button(
                onClick = {
                    busy = true; msg = "Verbinde mit panel.smartys.tech…"
                    scope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.pairAndLoad(playerId, pairingPw) }
                        busy = false
                        msg = when {
                            ok -> "Playlist gefunden! Lade Kanäle…"
                            else -> "Noch keine Playlist hinterlegt. Auf der Website einrichten, dann erneut prüfen."
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentDark)
            ) {
                Text("Prüfen — Playlist laden", Modifier.padding(horizontal = 26.dp, vertical = 6.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            msg?.let { Text(it, color = TextMid, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

fun main() = application {
    val icon = loadWindowIcon()
    Window(onCloseRequest = ::exitApplication, title = "Smartys IPTV", icon = icon) {
        App()
    }
}

/** Lädt das App-Icon als Fenster-/Taskleisten-Icon (statt GNOME-Zahnrad). */
private fun loadWindowIcon(): androidx.compose.ui.graphics.painter.Painter? {
    return runCatching {
        val bytes = Thread.currentThread().contextClassLoader
            .getResourceAsStream("icon.png")?.readBytes() ?: return null
        val bmp = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return null
        BitmapPainter(bmp.toComposeImageBitmap())
    }.getOrNull()
}
