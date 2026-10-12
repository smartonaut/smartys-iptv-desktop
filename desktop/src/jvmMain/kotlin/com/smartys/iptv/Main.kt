package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.shape.RoundedCornerShape
import javax.swing.SwingUtilities
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
    LIVE("Live-TV"), MOVIES("Filme"), SERIES("Serien"), SEARCH("Suche"), DOWNLOADS("Downloads"), RECORDINGS("Aufnahmen"), SETTINGS("Einstellungen")
}

data class VodPlaying(
    val title: String,
    val url: String,
    val key: String,      // Resume-Schlüssel
    val streamId: String, // für Zuletzt-gesehen
    val poster: String?,
    val isEpisode: Boolean = false,
    val next: (() -> Unit)? = null,  // Auto-Advance zur nächsten Episode
    val forceStartPos: Long? = null,  // null = auto-resume, 0L = von vorne, >0 = feste Position
    val seriesId: String? = null     // Serie: "Weiter schauen"-Pointer (ResumeStore.saveSeriesLast)
)

@Composable
fun App() {
    val vm = remember { DesktopViewModel() }
    LaunchedEffect(Unit) { vm.restoreSession(); vm.startScheduler() }

    var tab by remember { mutableStateOf(Tab.LIVE) }
    var liveChannel by remember { mutableStateOf<LiveStream?>(null) }
    var multiView by remember { mutableStateOf(false) }
    var vodPlaying by remember { mutableStateOf<VodPlaying?>(null) }
    var showSeries by remember { mutableStateOf<SeriesStream?>(null) }
    var selectedMovie by remember { mutableStateOf<VodStream?>(null) }

    // Weißes Fenster nach Screen-Wechsel (Player ↔ Menü) verhindern:
    // Compose Desktop hat einen bekannten Bug, bei dem das Fenster nach dem
    // Ein-/Aushängen eines SwingPanel (heavyweight Video-Canvas) unbemalt
    // bleibt. Nach jedem Player-Wechsel das AWT-Fenster zum Neuzeichnen
    // zwingen — sofort und nochmal nach ~150ms (falls der Wechsel noch in
    // der Layout-Phase steckte).
    LaunchedEffect(liveChannel, vodPlaying, multiView) {
        kotlinx.coroutines.delay(50)
        SwingUtilities.invokeLater {
            FullscreenController.window?.invalidate()
            FullscreenController.window?.repaint()
        }
        kotlinx.coroutines.delay(150)
        SwingUtilities.invokeLater {
            FullscreenController.window?.invalidate()
            FullscreenController.window?.repaint()
        }
    }

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
                                Tab.LIVE -> LiveTvScreen(vm, s, onPlay = { liveChannel = it }, onMultiView = { multiView = true })
                                Tab.MOVIES -> MoviesScreen(vm,
                                    onOpenSeries = { showSeries = it },
                                    onOpenMovie = { selectedMovie = it })
                                Tab.SERIES -> SeriesScreen(vm, onOpen = { showSeries = it })
                                Tab.SEARCH -> SearchScreen(vm, s,
                                    onPlayChannel = { liveChannel = it },
                                    onOpenMovie = { selectedMovie = it },
                                    onOpenSeries = { showSeries = it })
                                Tab.DOWNLOADS -> DownloadsScreen(vm,
                                    onPlay = { file, title, id ->
                                        vodPlaying = VodPlaying(title, file.toURI().toString(), "vod:download:$id", id, null, false, null)
                                    })
                                Tab.RECORDINGS -> RecordingsScreen(vm, s, onPlay = { file, title, id ->
                                    vodPlaying = VodPlaying(title, file.toURI().toString(), "vod:download:$id", id, null, false, null)
                                })
                                Tab.SETTINGS -> SettingsScreen(vm)
                            }
                        }
                    }

                    // Film-Detail (Overlay): Beschreibung + Resume/von-vorne + Trailer
                    selectedMovie?.let { m ->
                        MovieDetailScreen(vm, m,
                            onBack = { selectedMovie = null },
                            onPlay = { forceStartPos ->
                                val c = vm.creds
                                if (c != null) {
                                    val url = c.movieUrl(m.streamId, m.containerExtension ?: "mp4")
                                    val key = "vod:movie:${m.streamId}"
                                    vodPlaying = VodPlaying(m.name, url, key, m.streamId, m.poster(), false, null, forceStartPos)
                                }
                            })
                    }

                    // Serien-Detail (Overlay)
                    showSeries?.let { show ->
                        SeriesDetailScreen(vm, show,
                            onBack = { showSeries = null },
                            onPlayEpisode = { allEps, idx, forceStartPos ->
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
                                            nextKey, nextEp.id, show.cover, true, null, null, show.seriesId
                                        )
                                    }
                                }
                                vodPlaying = VodPlaying(title, url, key, ep.id, show.cover, true, next, forceStartPos, show.seriesId)
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

                    // Multi-View (Overlay, bis zu 4 Kanäle gleichzeitig)
                    if (multiView) {
                        MultiViewScreen(s.channels, vm, onBack = { multiView = false })
                    }

                    // VOD-Player (Overlay, inkl. Auto-Advance bei Serien).
                    // key(vp.key): Beim Auto-Advance wechselt vp OHNE dass vodPlaying
                    // zwischendurch null wird — ohne key würde VodPlayerScreen nur
                    // rekombinieren (derselbe VlcPlayer, inited bleibt true) und die
                    // neue URL NIE per play() starten. Mit key wird der Player sauber
                    // disposed + neu initialisiert (wie beim manuellen Öffnen).
                    vodPlaying?.let { vp ->
                        key(vp.key) {
                            VodPlayerScreen(vp, vm, onBack = {
                                vodPlaying = null
                            })
                        }
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
    var copied by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
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
            Text(
                playerId, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextHi,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(playerId))
                    copied = "Player-ID kopiert"
                }
            )
            Spacer(Modifier.height(14.dp))
            Text("Dein Passwort", color = TextMid, fontSize = 13.sp)
            Text(
                pairingPw, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Accent,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(pairingPw))
                    copied = "Passwort kopiert"
                }
            )
            copied?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Accent, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            // QR-Code: scannen statt abtippen (wie Tizen-Vorlage)
            QrCode(
                "https://panel.smartys.tech/pair?mac=${CredStore.playerId()}&pw=${CredStore.pairingPw()}",
                modifier = Modifier.size(180.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.White).padding(10.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text("QR-Code mit dem Handy scannen", color = TextMid, fontSize = 12.sp)
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
            Spacer(Modifier.height(10.dp))
            // Demo-Modus: sofort reinschauen ohne eigene Playlist
            OutlinedButton(
                onClick = { vm.startDemoMode() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
            ) {
                Text("Demo starten — ohne Einrichtung", Modifier.padding(horizontal = 22.dp, vertical = 4.dp), fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            msg?.let { Text(it, color = TextMid, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

fun main(args: Array<String>) {
    // Headless-Aufnahme-Daemon: Aufnahmen laufen auch bei geschlossener App.
    if (args.contains("--recording-daemon")) {
        runRecordingDaemon()
        return
    }
    application {
        extractReadme()
        val icon = loadWindowIcon()
        Window(onCloseRequest = ::exitApplication, title = "Smartys IPTV", icon = icon) {
            FullscreenController.window = this.window
            App()
        }
    }
}

/** Extrahiert die gebündelte README (Versionslog + Steuerung) beim ersten Start
 *  nach ~/.smartys-iptv/README.md, damit sie nach der Installation auffindbar ist. */
private fun extractReadme() {
    runCatching {
        val home = java.io.File(System.getProperty("user.home"), ".smartys-iptv")
        val target = java.io.File(home, "README.md")
        if (target.exists()) return
        val bytes = Thread.currentThread().contextClassLoader
            .getResourceAsStream("README.md")?.readBytes() ?: return
        home.mkdirs()
        target.writeBytes(bytes)
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
