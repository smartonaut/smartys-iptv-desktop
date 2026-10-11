package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import kotlin.concurrent.thread
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

/** Ziel-OS = Windows? Bestimmt die VOD-Engine: Windows = mpv, Linux = VLC. */
internal val isWindowsOS: Boolean =
    System.getProperty("os.name").lowercase().contains("win")

enum class AdhocRecMode { CURRENT_SHOW, ENDLESS }

// ---------- Gemeinsame Player-Gesten ----------

/**
 * ESC (global) → onBack. Wird als AWT-KeyEventDispatcher registriert, damit die
 * Taste auch greift, wenn der eingebettete Video-Canvas (Swing) den Fokus hält.
 */
@Composable
private fun EscapeToBack(onBack: () -> Unit) {
    // onBack in einer val-Kopie halten, damit der Dispatcher immer die AKTUELLE
    // Lamba aufruft (remember ohne keys würde sonst die erste onBack-Instanz
    // für die gesamte Lebensdauer einfrieren).
    val currentOnBack by rememberUpdatedState(onBack)
    val dispatcher = remember {
        object : KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE) {
                    currentOnBack()
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
        val w = window
        if (w == null) { PlayerLog.log("toggle ABGEBROCHEN: window==null!"); return }
        SwingUtilities.invokeLater {
            val gd = w.graphicsConfiguration?.device
            if (gd == null) { PlayerLog.log("toggle ABGEBROCHEN: kein GraphicsDevice!"); return@invokeLater }
            PlayerLog.mark("toggle fullscreen (jetzt=${if (gd.fullScreenWindow == null) "aus->an" else "an->aus"})")
            runCatching {
                if (gd.fullScreenWindow == null) {
                    gd.fullScreenWindow = w
                    showControls(gd)
                } else {
                    gd.fullScreenWindow = null
                    hideControls()
                }
            }.onFailure { PlayerLog.log("toggle FEHLER: ${it.javaClass.simpleName} ${it.message}") }
            PlayerLog.done("toggle fullscreen")
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

    /** Verlässt den Vollbild-Modus (No-op, wenn nicht im Vollbild).
     *  Wird beim Verlassen der Player-Screens aufgerufen: ESC im Vollbild beendet
     *  den Player — bliebe fullScreenWindow gesetzt, hinge der User im AWT-
     *  Vollbild im Menü fest (kein ESC-Dispatcher mehr, kein Video für Doppelklick). */
    fun exit() {
        val w = window ?: return
        SwingUtilities.invokeLater {
            val gd = w.graphicsConfiguration?.device ?: return@invokeLater
            if (gd.fullScreenWindow != null) gd.fullScreenWindow = null
            hideControls()
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
    onVolumeDown: () -> Unit,
    onMouseMove: (() -> Unit)? = null
) {
    var clickTimer: javax.swing.Timer? = null
    if (onMouseMove != null) {
        // Throttle: maximal ~7 Events pro Sekunde — sonst flutet die
        // Mausbewegung den EDT und zerlegt die Doppelklick-Erkennung.
        // Events kommen auf dem EDT an → direkt aufrufen.
        val lastTs = java.util.concurrent.atomic.AtomicLong(0L)
        canvas.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val now = System.currentTimeMillis()
                if (now - lastTs.get() < 150) return
                lastTs.set(now)
                PlayerLog.mark("mouseMoved (OSD)")
                onMouseMove()
                PlayerLog.done("mouseMoved")
            }
        })
    }
    canvas.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            PlayerLog.mark("mouseClicked btn=${e.button} count=${e.clickCount}")
            if (e.button != MouseEvent.BUTTON1) return
            if (e.clickCount >= 2) {
                clickTimer?.stop()
                PlayerLog.log("Doppelklick -> Vollbild toggle")
                SwingUtilities.invokeLater { FullscreenController.toggle() }
            } else {
                // Einzelklick erst nach kurzer Verzögerung auslösen, um
                // Doppelklick nicht fälschlich als "zurück" zu werten.
                clickTimer?.stop()
                clickTimer = javax.swing.Timer(250) {
                    PlayerLog.log("Einzelklick-Timer feuert -> onBack")
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
        PlayerLog.mark("wheel $clicks")
        if (clicks < 0) repeat(-clicks) { SwingUtilities.invokeLater(onVolumeUp) }
        else if (clicks > 0) repeat(clicks) { SwingUtilities.invokeLater(onVolumeDown) }
        PlayerLog.done("wheel")
    }
}

/** Pfeiltasten bei VOD: Links = -10s, Rechts = +10s (an die Länge geklemmt). */
@Composable
private fun ArrowSeek(player: VodPlayerApi, onSeek: (() -> Unit)? = null) {
    val dispatcher = remember(player) {
        object : KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.id == KeyEvent.KEY_PRESSED) {
                    when (e.keyCode) {
                        KeyEvent.VK_LEFT -> { seekBy(-10_000); onSeek?.invoke(); return true }
                        KeyEvent.VK_RIGHT -> { seekBy(10_000); onSeek?.invoke(); return true }
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

/** Startet eine Ad-hoc-Aufnahme des laufenden Kanals in eine dauerhafte Datei.
 *  Liefert false, wenn die Aufnahme nicht gestartet werden konnte (z. B. weil
 *  bereits eine andere Aufnahme läuft). */
fun startAdhocRecording(engine: RecordingEngine, vm: DesktopViewModel, ch: LiveStream, mode: AdhocRecMode): Boolean {
    val url = vm.streamUrlFor(ch) ?: return false
    val dir = File(AppSettings.recordingsDir())
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val fname = "${ch.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}_$ts.ts"
    return engine.start(url, File(dir, fname))
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
    // Video-Output bereit? Erst dann den Canvas sichtbar schalten — das
    // VLC-Child-Fenster mappt vorher WEISS (blendender Screen bis Frame 1).
    // Bis dahin läuft der Canvas unsichtbar (1px) und der Compose-
    // Lade-Indikator bleibt stehen.
    var videoReady by remember { mutableStateOf(false) }
    // OSD: Marquee (Text + Laufzeitbalken) + Senderlogo als VLC-Overlay DIREKT
    // ins Videobild — kein separates Fenster (JWindow-Transparenz scheitert
    // unter XWayland), dadurch automatisch mit dem Bild skaliert und echt
    // "transparent". Einmal zeigen für 5 s, weitere Bewegungen ignorieren.
    val osdBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    fun showOsdNow() {
        if (osdBusy.getAndSet(true)) return
        val (nowE, _) = XmltvEpg.getNowAndNext(ch.epgChannelId)
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val clock = fmt.format(Date())
        val epgTxt = if (nowE != null) {
            "${nowE.title}  ${fmt.format(Date(nowE.start))}–${fmt.format(Date(nowE.stop))}"
        } else ""
        val prog = if (nowE != null && nowE.stop > nowE.start) {
            ((System.currentTimeMillis() - nowE.start).toFloat() /
                (nowE.stop - nowE.start)).coerceIn(0f, 1f)
        } else 0f
        val filled = (prog * 24).toInt().coerceIn(0, 24)
        // Schlichter Balken: nur der gefüllte Teil (▬ = zusammenhängende
        // Linie), Rest bleibt unsichtbar — kein Karo-Muster, kein Kasten.
        val bar = "▬".repeat(filled)
        val line1 = if (epgTxt.isBlank()) "${ch.name}   $clock"
                    else "${ch.name} — $epgTxt   $clock"
        player.showOsd("$line1\n$bar", 5000, MarqueePosition.BOTTOM, java.awt.Color.WHITE)
        player.showLogo(true)
        thread {
            Thread.sleep(5200)
            player.showLogo(false)
            osdBusy.set(false)
        }
    }

    // Senderlogo laden (einmal pro Kanal) und als VLC-Logo-Overlay setzen.
    DisposableEffect(Unit) {
        val icon = ch.streamIcon
        if (!icon.isNullOrBlank()) {
            thread {
                runCatching {
                    val dir = File(System.getProperty("user.home"), ".smartys-iptv/cache/logos")
                    dir.mkdirs()
                    val target = File(dir, ch.streamId + ".png")
                    if (!target.exists() || target.length() == 0L) {
                        val conn = java.net.URI(icon).toURL().openConnection()
                        conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/145.0.0.0 Safari/537.36")
                        conn.connectTimeout = 6000
                        conn.readTimeout = 8000
                        conn.getInputStream().use { ins ->
                            target.outputStream().use { ins.copyTo(it) }
                        }
                    }
                    if (target.length() > 0L) player.setLogo(target.absolutePath)
                }
            }
        }
        onDispose { }
    }
    var paused by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(100) }
    var brightness by remember { mutableStateOf(1.0f) }
    val timeshift = remember { AppSettings.timeshiftEnabled() }
    val timeshiftDir = remember { AppSettings.timeshiftDir() }

    // ---- Ad-hoc-Aufnahme (R startet, S stoppt) ----
    val recEngine = remember { RecordingEngine() }
    var recMode by remember { mutableStateOf<AdhocRecMode?>(null) }  // null = keine Aufnahme
    var showRecDialog by remember { mutableStateOf(false) }
    // Timeshift: zwei Mechanismen —
    // 1) VLC-Disk-Timeshift (Einstellung "timeshift_enabled"): VLC puffert
    //    den Stream in Dateien, Pause/Seek funktionieren nativ im Buffer.
    // 2) Server-Archiv (tv_archive=1): Wechsel auf /timeshift/-URL ab dem
    //    Pause-Zeitpunkt. tsServerActive + behindSec tracken diesen Fall.
    var tsServerActive by remember { mutableStateOf(false) }
    var behindSec by remember { mutableStateOf(0L) }

    fun doTogglePause() {
        if (paused) {
            paused = false
            player.resume()
        } else if (timeshift) {
            // VLC-Disk-Timeshift: einfacher Pause — VLC puffert weiter,
            // Resume läuft ab der Stelle, ◀/▶ spulen im Buffer.
            player.pause()
            paused = true
            player.showOsd("⏸ Pause — ◀ ▶ spulen", 2000, MarqueePosition.BOTTOM, java.awt.Color.WHITE)
        } else if (ch.hasArchive() && !tsServerActive) {
            val u = vm.timeshiftUrlFor(
                ch, System.currentTimeMillis() / 1000, ch.archiveHours()
            )
            if (u != null) {
                tsServerActive = true
                player.stop()
                player.play(u, isVod = false, timeshift = false, timeshiftDir = null, startPosMs = 0)
                player.pause()
                paused = true
                player.showOsd("⏸ Pause — zurück mit ◀", 2000, MarqueePosition.BOTTOM, java.awt.Color.WHITE)
            } else {
                player.pause()
                paused = true
            }
        } else {
            player.pause()
            paused = true
        }
    }

    fun seekTs(deltaSec: Long) {
        if (tsServerActive) {
            behindSec = (behindSec + deltaSec).coerceAtLeast(0L)
            player.seekBy(deltaSec * 1000)
            if (behindSec == 0L) {
                player.showOsd("● LIVE", 1800, MarqueePosition.BOTTOM, java.awt.Color.WHITE)
            } else {
                val mm = behindSec / 60
                val ss = behindSec % 60
                player.showOsd("⏪ -%02d:%02d".format(mm, ss), 1800, MarqueePosition.BOTTOM, java.awt.Color.WHITE)
            }
        } else {
            // VLC-Disk-Timeshift: VLC klemmt automatisch an Buffer-Anfang
            // bzw. Live-Ende.
            player.seekBy(deltaSec * 1000)
            player.showOsd(
                if (deltaSec < 0) "⏪ ${-deltaSec}s" else "⏩ ${deltaSec}s",
                1500, MarqueePosition.BOTTOM, java.awt.Color.WHITE
            )
        }
    }

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
                        player.showOsd("Lautstärke  $volume%", 1500, MarqueePosition.BOTTOM, java.awt.Color.WHITE)
                        return true
                    }
                    KeyEvent.VK_DOWN -> {
                        volume = (volume - 10).coerceAtLeast(0); player.setVolume(volume)
                        player.showOsd("Lautstärke  $volume%", 1500, MarqueePosition.BOTTOM, java.awt.Color.WHITE)
                        return true
                    }
                    KeyEvent.VK_LEFT -> {
                        if (timeshift || tsServerActive) { seekTs(10); return true }
                        brightness = (brightness - 0.1f).coerceAtLeast(0.0f); player.setBrightness(brightness)
                        player.showOsd("Helligkeit  ${(brightness * 100).toInt()}%", 1500, MarqueePosition.BOTTOM, java.awt.Color.WHITE)
                        return true
                    }
                    KeyEvent.VK_RIGHT -> {
                        if (timeshift || tsServerActive) { seekTs(-10); return true }
                        brightness = (brightness + 0.1f).coerceAtMost(2.0f); player.setBrightness(brightness)
                        player.showOsd("Helligkeit  ${(brightness * 100).toInt()}%", 1500, MarqueePosition.BOTTOM, java.awt.Color.WHITE)
                        return true
                    }
                    KeyEvent.VK_SPACE -> { doTogglePause(); return true }
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

    // OSD beim Kanalstart: sobald der Player initialisiert ist, die OSD-Leiste
    // (Kanalname + Sendung + Uhrzeit + Laufzeitbalken) kurz einblenden.
    LaunchedEffect(inited) {
        if (!inited) return@LaunchedEffect
        showOsdNow()
    }

    // Bei „laufende Sendung": automatisch am EPG-Ende stoppen
    LaunchedEffect(recMode) {
        if (recMode == AdhocRecMode.CURRENT_SHOW) {
            val end = XmltvEpg.getNowAndNext(ch.epgChannelId).first?.stop
            if (end != null && end > System.currentTimeMillis()) {
                kotlinx.coroutines.delay(end - System.currentTimeMillis())
                recEngine.stop()
                recMode = null
            } else {
                // Kein EPG-Ende bekannt: dauerhaft aufnehmen (bis S).
                recMode = AdhocRecMode.ENDLESS
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recEngine.stop()
            // Sauberer libVLC-Stop VOR release: sonst hält der Provider die
            // Session noch offen (Max-Connections) und der nächste Kanal
            // bekommt HTTP 407 → weißer Screen. release() leicht verzögert,
            // damit der TEARDOWN den Server noch erreicht.
            player.stop()
            thread {
                Thread.sleep(500)
                player.release()
            }
            FullscreenController.exit()
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
                        onVolumeUp = { volume = (volume + 10).coerceAtMost(100); player.setVolume(volume); player.showOsd("Lautstärke  $volume%", 1200, MarqueePosition.BOTTOM, java.awt.Color.WHITE) },
                        onVolumeDown = { volume = (volume - 10).coerceAtLeast(0); player.setVolume(volume); player.showOsd("Lautstärke  $volume%", 1200, MarqueePosition.BOTTOM, java.awt.Color.WHITE) },
                        onMouseMove = { showOsdNow() }
                    )
                    val start = {
                        if (player.init()) {
                            // vlcj setzt den Canvas-Hintergrund beim Binden evtl. auf weiß —
                            // schwarz halten, bis der erste Frame kommt.
                            canvas.background = java.awt.Color.BLACK
                            // Canvas erst sichtbar, wenn VLC den Video-Output hat
                            // (weißer Blitz des Child-Fensters wird so verhindert).
                            player.onVideoOutput = { videoReady = true }
                            player.play(url, isVod = false, timeshift = timeshift, timeshiftDir = timeshiftDir)
                        }
                    }
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
            modifier = if (videoReady) Modifier.fillMaxSize() else Modifier.size(1.dp)
        )

        // Lade-Indikator: bleibt stehen, bis der Video-Output wirklich da ist —
        // kein weißer Blitz beim Stream-Start, dunkler Screen statt Blendung.
        if (!videoReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(14.dp))
                    Text("Verbinde…", color = TextHi, fontSize = 15.sp)
                }
            }
        }

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
                    doTogglePause()
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
                    recMode = if (startAdhocRecording(recEngine, vm, ch, AdhocRecMode.CURRENT_SHOW)) AdhocRecMode.CURRENT_SHOW else null
                }) { Text("Laufende Sendung", color = Accent) }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showRecDialog = false
                        recMode = if (startAdhocRecording(recEngine, vm, ch, AdhocRecMode.ENDLESS)) AdhocRecMode.ENDLESS else null
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
    // VOD läuft auf VLC (wie Live-TV). 1.5.11: auch Windows — das mpv-Embedding
    // frierte unter Windows strukturell ein (saubere DLL + erster Steuerbefehl
    // = Bild/App tot). Linux-Verhalten unveraendert (VlcPlayer war dort aktiv).
    val player = remember { VlcPlayer() }
    val canvas = remember { Canvas().apply { background = java.awt.Color.BLACK } }
    var inited by remember { mutableStateOf(false) }
    // Weiß-Screen-Fix (wie Live-TV): das VLC-Child-Fenster mappt WEISS bis
    // Frame 1 — der Canvas bleibt deshalb 1px klein und der Lade-Indikator
    // sichtbar, bis onVideoOutput feuert. mpv (Windows) rendert ohne Weiß-
    // Flash — dort kippt videoReady direkt nach init.
    var videoReady by remember { mutableStateOf(false) }
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

    // Mausover-OSD (User-Vorgabe): kurz einblenden, was laeuft + Restlaufzeit.
    // Marquee geht DIREKT ins Videobild — Compose-Overlays blieben hinter dem
    // heavyweight Canvas unsichtbar. attachMouseControls drosselt auf ~7/s;
    // der 5-s-Timeout blendet aus, sobald die Maus ruht.
    fun showVodOsd() {
        val dur = player.durationMs()
        val rest = if (dur > 0) (dur - player.currentPositionMs()).coerceAtLeast(0L) else 0L
        val icon = if (paused) "\u23F8" else "\u25B6"
        val restTxt = if (dur > 0) "  \u00B7  noch ${fmt(rest)}" else ""
        player.showOsd("$icon ${vp.title}$restTxt", 5000, position = MarqueePosition.TOP)
    }

    EscapeToBack(onBack)

    ArrowSeek(player, onSeek = { showVodOsd() })

    // Leertaste = Pause/Weiter (User-Vorgabe: bei VOD einfach nur Pause —
    // Live-TV pausiert ueber den Disk-Timeshift weiter, VOD pausiert simpel).
    DisposableEffect(Unit) {
        val d = object : KeyEventDispatcher {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.id != KeyEvent.KEY_PRESSED) return false
                if (e.keyCode == KeyEvent.VK_SPACE) {
                    paused = !paused
                    if (paused) player.pause() else player.resume()
                    showVodOsd()
                    return true
                }
                return false
            }
        }
        val mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        mgr.addKeyEventDispatcher(d)
        onDispose { mgr.removeKeyEventDispatcher(d) }
    }

    // OSD einmal beim Start (Titel + Restlaufzeit), sobald das Bild steht
    LaunchedEffect(videoReady) { if (videoReady) showVodOsd() }

    VolumeArrows(
        onUp = { volume = (volume + 10).coerceAtMost(100); player.setVolume(volume) },
        onDown = { volume = (volume - 10).coerceAtLeast(0); player.setVolume(volume) }
    )

    DisposableEffect(Unit) {
        onDispose {
            val endPos = player.currentPositionMs()
            val endDur = player.durationMs()
            ResumeStore.save(vp.key, endPos, endDur)
            vp.seriesId?.let { sid -> ResumeStore.saveSeriesLast(sid, vp.streamId, endPos, endDur) }
            watcher?.stop()
            // Sauberer libVLC-Stop VOR release (sonst 407 beim nächsten Titel).
            player.stop()
            thread {
                Thread.sleep(500)
                player.release()
            }
            FullscreenController.exit()
        }
    }

    // Positions-Polling + Abspann-Erkennung + Resume-Punkt alle 60 s:
    // Unterbrechung (Stop, Crash, Strom) verliert maximal 60 Sekunden —
    // "Weiter schauen" startet dann fast exakt an der Abbruchstelle.
    LaunchedEffect(Unit) {
        var lastSavedPos = 0L
        while (true) {
            positionMs = player.currentPositionMs()
            durationMs = player.durationMs()
            if (positionMs > 0 && positionMs - lastSavedPos >= 60_000L) {
                lastSavedPos = positionMs
                ResumeStore.save(vp.key, positionMs, durationMs)
                vp.seriesId?.let { sid ->
                    ResumeStore.saveSeriesLast(sid, vp.streamId, positionMs, durationMs)
                }
            }
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
                        onVolumeDown = { volume = (volume - 10).coerceAtLeast(0); player.setVolume(volume) },
                        onMouseMove = { showVodOsd() }
                    )
                    val doStart = {
                        if (!inited) {
                            inited = true
                            if (player.init()) {
                                if (player is VlcPlayer) {
                                    player.onVideoOutput = { videoReady = true }
                                } else {
                                    videoReady = true
                                }
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
                                player.play(vp.url, isVod = true, startPosMs = startPos)
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
            modifier = if (videoReady) Modifier.fillMaxSize() else Modifier.size(1.dp)
        )

        // Lade-Indikator: überdeckt den leeren Bereich, bis das Video rendert.
        if (!videoReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(14.dp))
                    Text("Lade…", color = TextHi, fontSize = 15.sp)
                }
            }
        }

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
