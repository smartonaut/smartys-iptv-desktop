package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.player.base.MarqueePosition
import java.awt.Canvas
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.SwingUtilities

enum class AdhocRecMode { CURRENT_SHOW, ENDLESS }

// ---------- Gemeinsame Player-Gesten ----------

/**
 * ESC (global) → onBack. Wird als AWT-KeyEventDispatcher registriert, damit die
 * Taste auch greift, wenn der eingebettete Video-Canvas (Swing) den Fokus hält.
 */
@Composable
private fun EscapeToBack(onBack: () -> Unit) {
    val dispatcher = remember {
        object : KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE) {
                    onBack()
                    return true
                }
                return false
            }
        }
    }
    DisposableEffect(Unit) {
        val mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        mgr.addKeyEventDispatcher(dispatcher)
        onDispose { mgr.removeKeyEventDispatcher(dispatcher) }
    }
}

/** Globaler Vollbild-Toggle (Doppelklick). Nutzt das AWT GraphicsDevice-Fullscreen. */
object FullscreenController {
    @Volatile
    var window: java.awt.Window? = null

    private var controlsWindow: javax.swing.JWindow? = null

    fun toggle() {
        val w = window ?: return
        SwingUtilities.invokeLater {
            val gd = w.graphicsConfiguration?.device ?: return@invokeLater
            if (gd.fullScreenWindow == null) {
                gd.fullScreenWindow = w
                showControls(gd)
            } else {
                gd.fullScreenWindow = null
                hideControls()
            }
        }
    }

    fun isFullscreen(): Boolean {
        val w = window ?: return false
        val gd = w.graphicsConfiguration?.device ?: return false
        return gd.fullScreenWindow != null
    }

    fun minimize() {
        val w = window ?: return
        SwingUtilities.invokeLater {
            val gd = w.graphicsConfiguration?.device
            if (gd?.fullScreenWindow != null) gd.fullScreenWindow = null
            hideControls()
            if (w is java.awt.Frame) (w as java.awt.Frame).state = java.awt.Frame.ICONIFIED
        }
    }

    fun close() {
        val w = window ?: return
        SwingUtilities.invokeLater {
            val gd = w.graphicsConfiguration?.device
            if (gd?.fullScreenWindow != null) gd.fullScreenWindow = null
            hideControls()
            w.dispose()
            System.exit(0)
        }
    }

    /**
     * Fenstersteuerung (Minimieren + Schließen) als eigenes, immer-oben JWindow.
     * Ein Compose-Overlay würde vom heavyweight Video-Canvas verdeckt — ein
     * separates heavyweight AWT-Fenster liegt dagegen garantiert darüber.
     */
    private fun showControls(gd: java.awt.GraphicsDevice) {
        hideControls()
        val jw = javax.swing.JWindow()
        jw.isAlwaysOnTop = true
        val panel = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 4))
        panel.background = java.awt.Color(0x11, 0x17, 0x2A, 0xCC)

        val min = javax.swing.JButton("—")
        min.isFocusable = false
        min.isBorderPainted = false
        min.foreground = java.awt.Color(0xE8, 0xEE, 0xFC)
        min.background = java.awt.Color(0x11, 0x17, 0x2A)
        min.addActionListener { minimize() }

        val close = javax.swing.JButton("✕")
        close.isFocusable = false
        close.isBorderPainted = false
        close.foreground = java.awt.Color(0xFF, 0x6B, 0x6B)
        close.background = java.awt.Color(0x11, 0x17, 0x2A)
        close.addActionListener { close() }

        panel.add(min)
        panel.add(close)
        jw.contentPane.add(panel)
        jw.pack()

        val bounds = gd.defaultConfiguration.bounds
        jw.setLocation(bounds.x + bounds.width - jw.width - 12, bounds.y + 12)
        jw.isVisible = true
        controlsWindow = jw
    }

    private fun hideControls() {
        controlsWindow?.dispose()
        controlsWindow = null
    }
}

/**
 * Maus-Steuerung am Video-Canvas:
 *  - Einzelklick (links) → zurück ins Menü
 *  - Doppelklick (links) → Vollbild an/aus
 *  - Mausrad hoch/runter → Lautstärke +/−
 *
 * Einzel-/Doppelklick werden über einen kurzen Timer unterschieden: erst wenn
 * binnen ~250 ms kein zweiter Klick folgt, gilt der erste als Einzelklick.
 */
private fun attachMouseControls(
    canvas: Canvas,
    onBack: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit
) {
    var clickTimer: javax.swing.Timer? = null
    canvas.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (e.button != MouseEvent.BUTTON1) return
            if (e.clickCount >= 2) {
                clickTimer?.stop()
                SwingUtilities.invokeLater { FullscreenController.toggle() }
            } else {
                // Einzelklick erst nach kurzer Verzögerung auslösen, um
                // Doppelklick nicht fälschlich als "zurück" zu werten.
                clickTimer?.stop()
                clickTimer = javax.swing.Timer(250) {
                    SwingUtilities.invokeLater(onBack)
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        }
    })
    canvas.addMouseWheelListener { e ->
        val clicks = e.wheelRotation
        if (clicks < 0) repeat(-clicks) { SwingUtilities.invokeLater(onVolumeUp) }
        else if (clicks > 0) repeat(clicks) { SwingUtilities.invokeLater(onVolumeDown) }
    }
}

/** Pfeiltasten bei VOD: Links = -10s, Rechts = +10s (an die Länge geklemmt). */
@Composable
private fun ArrowSeek(player: VlcPlayer) {
    val dispatcher = remember(player) {
        object : KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.id == KeyEvent.KEY_PRESSED) {
                    when (e.keyCode) {
                        KeyEvent.VK_LEFT -> { seekBy(-10_000); return true }
                        KeyEvent.VK_RIGHT -> { seekBy(10_000); return true }
                    }
                }
                return false
            }
            private fun seekBy(deltaMs: Long) {
                val dur = player.durationMs()
                if (dur <= 0) return
                val target = (player.currentPositionMs() + deltaMs).coerceIn(0L, dur)
                player.seekTo(target)
            }
        }
    }
    DisposableEffect(Unit) {
        val mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        mgr.addKeyEventDispatcher(dispatcher)
        onDispose { mgr.removeKeyEventDispatcher(dispatcher) }
    }
}

/** R = Aufnahme-Dialog öffnen, S = laufende Aufnahme stoppen. */
@Composable
private fun RecordKeys(onStartRequest: () -> Unit, onStop: () -> Unit) {
    val dispatcher = remember {
        object : KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.id == KeyEvent.KEY_PRESSED) {
                    when (e.keyCode) {
                        KeyEvent.VK_R -> { onStartRequest(); return true }
                        KeyEvent.VK_S -> { onStop(); return true }
                    }
                }
                return false
            }
        }
    }
    DisposableEffect(Unit) {
        val mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        mgr.addKeyEventDispatcher(dispatcher)
        onDispose { mgr.removeKeyEventDispatcher(dispatcher) }
    }
}

/** Startet eine Ad-hoc-Aufnahme des laufenden Kanals in eine dauerhafte Datei. */
private fun startAdhocRecording(engine: RecordingEngine, vm: DesktopViewModel, ch: LiveStream, mode: AdhocRecMode) {
    val url = vm.streamUrlFor(ch) ?: return
    val dir = File(AppSettings.recordingsDir())
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val fname = "${ch.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}_$ts.ts"
    engine.start(url, File(dir, fname))
}

/** Pfeil hoch/runter = Lautstärke +10/-10. */
@Composable
private fun VolumeArrows(onUp: () -> Unit, onDown: () -> Unit) {
    val dispatcher = remember {
        object : KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.id == KeyEvent.KEY_PRESSED) {
                    when (e.keyCode) {
                        KeyEvent.VK_UP -> { onUp(); return true }
                        KeyEvent.VK_DOWN -> { onDown(); return true }
                    }
                }
                return false
            }
        }
    }
    DisposableEffect(Unit) {
        val mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        mgr.addKeyEventDispatcher(dispatcher)
        onDispose { mgr.removeKeyEventDispatcher(dispatcher) }
    }
}

/** Rand-Streifen: bei Hover erscheint ▲/Wert/▼; Klick auf ▲/▼ verstellt den Wert. */
@Composable
private fun EdgeStrip(
    valueLabel: String,
    fraction: Float,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier.hoverable(interaction)) {
        if (hovered) {
            Column(
                Modifier.fillMaxHeight().width(44.dp).background(Color(0x660B0E14)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("▲", color = Accent, fontSize = 20.sp,
                    modifier = Modifier.clickable(onClick = onIncrement).padding(vertical = 10.dp))
                // Pegelbalken (vertikal, auf leicht dunklem Track für Kontrast)
                Box(
                    Modifier.width(10.dp).height(120.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0x66FFFFFF))
                ) {
                    Box(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(fraction.coerceIn(0f, 1f))
                            .background(Accent)
                    )
                }
                Text(valueLabel, color = TextHi, fontSize = 12.sp)
                Text("▼", color = Accent, fontSize = 20.sp,
                    modifier = Modifier.clickable(onClick = onDecrement).padding(vertical = 10.dp))
            }
        }
    }
}

// ---------- Live-Player ----------

@Composable
fun LivePlayerScreen(ch: LiveStream, url: String, vm: DesktopViewModel, onBack: () -> Unit) {
    val player = remember { VlcPlayer() }
    val canvas = remember { Canvas().apply { background = java.awt.Color.BLACK } }
    var inited by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(100) }
    var brightness by remember { mutableStateOf(1.0f) }
    val timeshift = remember { AppSettings.timeshiftEnabled() }
    val timeshiftDir = remember { AppSettings.timeshiftDir() }

    // ---- Ad-hoc-Aufnahme (R startet, S stoppt) ----
    val recEngine = remember { RecordingEngine() }
    var recMode by remember { mutableStateOf<AdhocRecMode?>(null) }  // null = keine Aufnahme
    var showRecDialog by remember { mutableStateOf(false) }

    EscapeToBack(onBack)

    // Live-TV-Tasten: ↑/↓ = Lautstärke, ←/→ = Helligkeit, R = Aufnahme-Dialog, S = Stopp.
    // Feedback läuft über libVLC-Marquee (ins Videobild), da Compose-Overlays
    // hinter dem heavyweight Video-Canvas unsichtbar bleiben.
    val liveTvDispatcher = remember {
        object : KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.id != KeyEvent.KEY_PRESSED) return false
                when (e.keyCode) {
                    KeyEvent.VK_UP -> {
                        volume = (volume + 10).coerceAtMost(100); player.setVolume(volume)
                        player.showOsd("Lautstärke  $volume%", 1500)
                        return true
                    }
                    KeyEvent.VK_DOWN -> {
                        volume = (volume - 10).coerceAtLeast(0); player.setVolume(volume)
                        player.showOsd("Lautstärke  $volume%", 1500)
                        return true
                    }
                    KeyEvent.VK_LEFT -> {
                        brightness = (brightness - 0.1f).coerceAtLeast(0.0f); player.setBrightness(brightness)
                        player.showOsd("Helligkeit  ${(brightness * 100).toInt()}%", 1500)
                        return true
                    }
                    KeyEvent.VK_RIGHT -> {
                        brightness = (brightness + 0.1f).coerceAtMost(2.0f); player.setBrightness(brightness)
                        player.showOsd("Helligkeit  ${(brightness * 100).toInt()}%", 1500)
                        return true
                    }
                    KeyEvent.VK_R -> { showRecDialog = true; return true }
                    KeyEvent.VK_S -> { recEngine.stop(); recMode = null; return true }
                }
                return false
            }
        }
    }
    DisposableEffect(Unit) {
        val mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        mgr.addKeyEventDispatcher(liveTvDispatcher)
        onDispose { mgr.removeKeyEventDispatcher(liveTvDispatcher) }
    }

    // OSD beim Kanalstart: sobald der Player initialisiert ist (inited), Kanalname
    // + laufende Sendung via Marquee einblenden.
    LaunchedEffect(inited) {
        if (!inited) return@LaunchedEffect
        val title = runCatching {
            XmltvEpg.getNowAndNext(ch.epgChannelId).first?.title
        }.getOrNull()
        val osd = if (title.isNullOrBlank()) ch.name else "${ch.name}  —  $title"
        player.showOsd(osd, 4000)
    }

    // Bei „laufende Sendung": automatisch am EPG-Ende stoppen
    LaunchedEffect(recMode) {
        if (recMode == AdhocRecMode.CURRENT_SHOW) {
            val end = XmltvEpg.getNowAndNext(ch.epgChannelId).first?.stop
            if (end != null && end > System.currentTimeMillis()) {
                kotlinx.coroutines.delay(end - System.currentTimeMillis())
                recEngine.stop()
                recMode = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recEngine.stop()
            player.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SwingPanel(
            factory = {
                if (!inited) {
                    player.videoCanvas = canvas
                    attachMouseControls(
                        canvas,
                        onBack = onBack,
                        onVolumeUp = { volume = (volume + 10).coerceAtMost(100); player.setVolume(volume); player.showOsd("Lautstärke  $volume%", 1200) },
                        onVolumeDown = { volume = (volume - 10).coerceAtLeast(0); player.setVolume(volume); player.showOsd("Lautstärke  $volume%", 1200) }
                    )
                    val start = { if (player.init()) player.play(url, isVod = false, timeshift = timeshift, timeshiftDir = timeshiftDir) }
                    if (canvas.isDisplayable) {
                        inited = true
                        start()
                    } else {
                        canvas.addHierarchyListener { _ ->
                            if (!inited && canvas.isDisplayable) {
                                inited = true
                                start()
                            }
                        }
                    }
                }
                canvas
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay-Kopfleiste
        Row(
            Modifier.fillMaxWidth().background(Color(0xAA0B0E14)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Accent, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text(ch.name, color = TextHi, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(if (paused) "▶" else "❚❚", color = TextHi, fontSize = 18.sp,
                modifier = Modifier.clickable {
                    paused = !paused
                    if (paused) player.pause() else player.resume()
                }.padding(horizontal = 10.dp))
        }

        // OSD + Pegel + Aufnahme-Status laufen über libVLC-Marquee (ins Videobild),
        // da Compose-Overlays hinter dem heavyweight Video-Canvas unsichtbar bleiben.
        // REC-Indikator als Marquee oben rechts anzeigen.
        LaunchedEffect(recMode) {
            if (recMode != null) {
                player.showOsd("● REC  ${if (recMode == AdhocRecMode.CURRENT_SHOW) "Sendung" else "endlos"}",
                    timeoutMs = 0, position = MarqueePosition.TOP_RIGHT, color = java.awt.Color.RED)
            } else {
                player.hideOsd()
            }
        }
    }

    // Aufnahme-Dialog (R gedrückt): laufende Sendung vs. endlos
    if (showRecDialog) {
        AlertDialog(
            onDismissRequest = { showRecDialog = false },
            containerColor = Card,
            title = { Text("Aufnahme starten", color = TextHi) },
            text = { Text("Wie soll aufgenommen werden?", color = TextMid) },
            confirmButton = {
                TextButton(onClick = {
                    showRecDialog = false
                    startAdhocRecording(recEngine, vm, ch, AdhocRecMode.CURRENT_SHOW)
                    recMode = AdhocRecMode.CURRENT_SHOW
                }) { Text("Laufende Sendung", color = Accent) }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showRecDialog = false
                        startAdhocRecording(recEngine, vm, ch, AdhocRecMode.ENDLESS)
                        recMode = AdhocRecMode.ENDLESS
                    }) { Text("Endlos (bis S)", color = TextHi) }
                    TextButton(onClick = { showRecDialog = false }) { Text("Abbrechen", color = TextMid) }
                }
            }
        )
    }
}

// ---------- VOD-Player (Resume + Abspann-Erkennung + Auto-Advance) ----------

@Composable
fun VodPlayerScreen(vp: VodPlaying, vm: DesktopViewModel, onBack: () -> Unit) {
    val player = remember { VlcPlayer() }
    val canvas = remember { Canvas().apply { background = java.awt.Color.BLACK } }
    var inited by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var watcher by remember { mutableStateOf<CreditsWatcher?>(null) }
    var volume by remember { mutableStateOf(100) }
    var brightness by remember { mutableStateOf(1.0f) }
    val scope = rememberCoroutineScope()

    // Startposition: forceStartPos (0 = von vorne) hat Vorrang vor Auto-Resume
    val startPos = remember(vp.key, vp.forceStartPos) {
        vp.forceStartPos ?: ResumeStore.position(vp.key)
    }

    EscapeToBack(onBack)

    ArrowSeek(player)

    VolumeArrows(
        onUp = { volume = (volume + 10).coerceAtMost(100); player.setVolume(volume) },
        onDown = { volume = (volume - 10).coerceAtLeast(0); player.setVolume(volume) }
    )

    DisposableEffect(Unit) {
        onDispose {
            ResumeStore.save(vp.key, player.currentPositionMs(), player.durationMs())
            watcher?.stop()
            player.release()
        }
    }

    // Positions-Polling + Abspann-Erkennung
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = player.currentPositionMs()
            durationMs = player.durationMs()
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SwingPanel(
            factory = {
                if (!inited) {
                    player.videoCanvas = canvas
                    attachMouseControls(
                        canvas,
                        onBack = onBack,
                        onVolumeUp = { volume = (volume + 10).coerceAtMost(100); player.setVolume(volume) },
                        onVolumeDown = { volume = (volume - 10).coerceAtLeast(0); player.setVolume(volume) }
                    )
                    val doStart = {
                        if (!inited) {
                            inited = true
                            if (player.init()) {
                                val advance = {
                                    vm.markWatched(vp.streamId)
                                    vp.next?.invoke()
                                }
                                player.onVodEnded = { advance() }
                                if (vp.next != null) {
                                    val w = CreditsWatcher(player) { advance() }
                                    watcher = w
                                    scope.launch { w.run() }
                                }
                                player.play(vp.url, isVod = true)
                                if (startPos > 0) player.seekTo(startPos)
                            }
                        }
                    }
                    if (canvas.isDisplayable) {
                        doStart()
                    } else {
                        canvas.addHierarchyListener { _ ->
                            if (canvas.isDisplayable) doStart()
                        }
                    }
                }
                canvas
            },
            modifier = Modifier.fillMaxSize()
        )

        // Kopfleiste
        Row(
            Modifier.fillMaxWidth().background(Color(0xAA0B0E14)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Accent, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text(vp.title, color = TextHi, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(if (paused) "▶" else "❚❚", color = TextHi, fontSize = 18.sp,
                modifier = Modifier.clickable {
                    paused = !paused
                    if (paused) player.pause() else player.resume()
                }.padding(horizontal = 10.dp))
        }

        // Fortschritts-/Seek-Leiste
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xAA0B0E14)).padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            if (durationMs > 0) {
                Slider(
                    value = (positionMs.coerceAtMost(durationMs)).toFloat(),
                    onValueChange = { player.seekTo(it.toLong()) },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0x33555555)
                    )
                )
            } else {
                LinearProgressIndicator(
                    progress = { 0f },
                    color = Accent,
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text(fmt(positionMs), color = TextMid, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(fmt(durationMs), color = TextMid, fontSize = 11.sp)
            }
        }

        // Rand-Steuerung: links Helligkeit, rechts Lautstärke
        EdgeStrip(
            valueLabel = "🔆 ${(brightness * 100).toInt()}%",
            fraction = (brightness / 2.0f).coerceIn(0f, 1f),
            onIncrement = { brightness = (brightness + 0.1f).coerceAtMost(2.0f); player.setBrightness(brightness) },
            onDecrement = { brightness = (brightness - 0.1f).coerceAtLeast(0.0f); player.setBrightness(brightness) },
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight()
        )
        EdgeStrip(
            valueLabel = "🔊 $volume%",
            fraction = volume / 100f,
            onIncrement = { volume = (volume + 10).coerceAtMost(100); player.setVolume(volume) },
            onDecrement = { volume = (volume - 10).coerceAtLeast(0); player.setVolume(volume) },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
    else String.format("%d:%02d", m, sec)
}
