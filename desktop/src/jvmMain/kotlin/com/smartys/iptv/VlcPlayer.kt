package com.smartys.iptv

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.DeinterlaceMode
import uk.co.caprica.vlcj.player.base.MarqueePosition
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.awt.Canvas
import java.io.File
import java.util.zip.ZipInputStream
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Desktop-Wiedergabe über libVLC (vlcj). Einbettung des Videos in ein
 * AWT-Canvas, das via SwingPanel in Compose eingebettet wird.
 *
 * Self-contained: libVLC-Core (.so) + Plugins werden als Ressource
 * (libvlc/libvlc-linux-x64.zip) mit ausgeliefert und beim ersten Start nach
 * ~/.smartys-iptv/libvlc/ extrahiert — es ist KEINE externe VLC-Installation
 * nötig. Falls der gebündelte Build nicht zum System passt, fällt der Loader
 * auf ein systemweit installiertes libVLC zurück.
 */
class VlcPlayer : VodPlayerApi {

    private var factory: MediaPlayerFactory? = null
    private var mediaPlayer: EmbeddedMediaPlayer? = null

    @Volatile
    var playing: Boolean = false
        private set

    /** Video-Ziel (muss vor play() gesetzt sein, damit das Bild dort erscheint). */
    override var videoCanvas: Canvas? = null

    /** Wird beim natürlichen Ende eines VOD-Titels aufgerufen. */
    override var onVodEnded: (() -> Unit)? = null

    /** Feuert, sobald libVLC den Video-Output erstellt (kurz vor Frame 1).
     *  Wird genutzt, um den Canvas erst dann sichtbar zu schalten — das
     *  VLC-Child-Fenster mappt sonst WEISS und blendet bis zum ersten Frame. */
    var onVideoOutput: (() -> Unit)? = null

    private var currentUrl: String? = null
    private var currentIsVod = false

    companion object {
        @Volatile private var extracted: String? = null

        private val isWindows =
            System.getProperty("os.name").lowercase().contains("win")

        /** JNA-Bibliotheksname: vlcj lädt "libvlc" unter Windows, "vlc" sonst. */
        private val jnaLibName = if (isWindows) "libvlc" else "vlc"

        /** Dateiname der Core-Bibliothek (transitive Abhängigkeit von libvlc). */
        private val coreFileName = if (isWindows) "libvlccore.dll" else "libvlccore.so.9"

        /** Setzt eine Umgebungsvariable (plattformneutral). Öffentlich, damit die
         *  RecordingEngine dieselbe libVLC-Init wiederverwenden kann. */
        fun setEnv(name: String, value: String) {
            runCatching {
                if (isWindows) {
                    com.sun.jna.platform.win32.Kernel32.INSTANCE.SetEnvironmentVariable(name, value)
                } else {
                    val libc = Native.load("c", LibC::class.java)
                    libc.setenv(name, value, 1)
                }
            }
        }

        /** Plugins-Pfad (VLC_PLUGIN_PATH) — siehe ensureLibVlc. Öffentlich für die Engine. */
        fun pluginDir(bundled: File): File =
            if (isWindows) File(bundled, "plugins")
            else File(bundled.parentFile, "plugins")

        /** JNA-Libraryname (libvlc vs vlc). */
        fun libName(): String = jnaLibName

        /** Ergebnis der System-libVLC-Suche: libvlc-Ordner + Plugin-Ordner. */
        data class SystemVlc(val pluginDir: String)

        /**
         * Sucht ein System-libVLC (Linux). Liefert null, wenn keins installiert ist.
         * Typische Pfade: /usr/lib/x86_64-linux-gnu (Debian/Ubuntu multiarch).
         */
        private fun findSystemLibVlc(): SystemVlc? {
            val libDirs = listOf(
                "/usr/lib/x86_64-linux-gnu",
                "/usr/lib/aarch64-linux-gnu",
                "/usr/lib/arm-linux-gnueabihf",
                "/usr/lib"
            )
            for (d in libDirs) {
                val lib = File(d, "libvlc.so.5")
                val core = File(d, "libvlccore.so.9")
                val plugins = File(d, "vlc/plugins")
                if (lib.exists() && core.exists() && plugins.exists()) {
                    return SystemVlc(plugins.absolutePath)
                }
            }
            return null
        }

        private val zipResource = if (isWindows)
            "/libvlc/libvlc-windows-x64.zip" else "/libvlc/libvlc-linux-x64.zip"

        /** Erwartete Größe der Marker-Datei im gebündelten Zip (-1 = unbekannt). */
        private fun markerEntrySize(): Long = runCatching {
            val wanted = if (isWindows) "libvlccore.dll" else "lib/libdvbpsi.so.10"
            val zs = VlcPlayer::class.java.getResourceAsStream(zipResource) ?: return@runCatching -1L
            ZipInputStream(zs).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == wanted) return@use e.size
                    zis.closeEntry()
                    e = zis.nextEntry
                }
                -1L
            }
        }.getOrDefault(-1L)

        /**
         * Extrahiert das gebündelte libVLC und liefert den Ordner, der die
         * Core-Bibliothek enthält (oder null).
         *
         * Layout-Unterschied:
         *  - Windows: flach, alles unter ~/.smartys-iptv/libvlc/ (libvlc.dll,
         *    libvlccore.dll, plugins/)
         *  - Linux:   ~/.smartys-iptv/libvlc/lib/ + ../plugins/
         */
        fun ensureLibVlc(): File? {
            extracted?.let { return File(it) }
            return runCatching {
                val home = File(System.getProperty("user.home"), ".smartys-iptv/libvlc")
                // Marker = Datei, die NUR das VOLLSTÄNDIGE Bundle enthält. So
                // wird bei einer alten/fehlenden Extraktion neu extrahiert.
                // 1.5.10: ZUSÄTZLICH Größen-Verifikation — passt die Marker-Datei
                // nicht zu ihrem Zip-Eintrag (fremde/veraltete Dateien im Ordner),
                // wird NEU extrahiert. Grundlage war der 1.5.9-Windows-Freezer
                // durch ein Relikt im Zielordner.
                val marker = if (isWindows)
                    File(home, "libvlccore.dll")
                else
                    File(home, "lib/libdvbpsi.so.10")
                val expected = markerEntrySize()
                if (!marker.exists() || (expected > 0 && marker.length() != expected)) {
                    val zipRes = VlcPlayer::class.java.getResourceAsStream(zipResource)
                        ?: return@runCatching null
                    home.mkdirs()
                    ZipInputStream(zipRes).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val target = File(home, entry.name)
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { zis.copyTo(it, 1 shl 16) }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                val libDir = if (isWindows) home else File(home, "lib")
                extracted = libDir.absolutePath
                libDir
            }.getOrNull()
        }
    }

    /**
     * Initialisiert die libVLC-Instanz (einmal pro Player). Muss auf dem EDT
     * laufen, da Swing-Komponenten beteiligt sind.
     *
     * Ladereihenfolge (self-contained): erst libvlccore via System.load (damit
     * der OS-Loader die transitive Abhängigkeit von libvlc bereits erfüllt
     * sieht), dann libvlc via JNA-Suchpfad. Unter Windows heißt die JNA-Library
     * "libvlc" (→ libvlc.dll), unter Linux "vlc" (→ libvlc.so).
     */
    override fun init(): Boolean {
        if (mediaPlayer != null) return true
        return runCatching {
            // Auf Linux bevorzugen wir das System-libVLC (korrekt für das System
            // gebaut). Das gebündelte libVLC-Bundle hat auf manchen Systemen
            // (Hybrid-GPU, brandneue Intel-CPUs) defekte VAAPI/VDPAU-Pfade, die
            // grüne Blöcke erzeugen — das System-VLC nutzt dagegen saubere Pfade.
            // Nur wenn kein System-libVLC vorhanden ist, fällt das Bundle ein.
            val bundled = ensureLibVlc()
            val systemLibVlc = if (isWindows) null else findSystemLibVlc()
            if (systemLibVlc != null) {
                // System-libVLC: kein System.load nötig, JNA findet es über den
                // Standard-Suchpfad. Plugin-Pfad auf die System-Plugins setzen.
                setenv("VLC_PLUGIN_PATH", systemLibVlc.pluginDir)
            } else if (bundled != null) {
                // 1) libvlccore zuerst laden (transitive Abhängigkeit von libvlc)
                runCatching { System.load(File(bundled, coreFileName).absolutePath) }
                // 2) JNA-Suchpfad für libvlc
                NativeLibrary.addSearchPath(jnaLibName, bundled.absolutePath)
                // 3) Plugin-Pfad über VLC_PLUGIN_PATH
                val pluginDir = if (isWindows)
                    File(bundled, "plugins")
                else
                    File(bundled.parentFile, "plugins")
                if (pluginDir.exists()) setenv("VLC_PLUGIN_PATH", pluginDir.absolutePath)
            }

            val factoryArgs = mutableListOf(
                "--no-video-title-show",
                // Stream-Puffer (Einstellung "Stream-Puffer" in Sekunden):
                // mehr Puffer = weniger Freezer bei schwacher Leitung, dafür
                // mehr Verzögerung. 0 s = nur minimales Puffern (schneller Start).
                "--network-caching=${(AppSettings.cacheSeconds().coerceAtLeast(0) * 1000).coerceAtLeast(300)}",
                "--live-caching=${(AppSettings.cacheSeconds().coerceAtLeast(0) * 1000).coerceAtLeast(300)}",
                // Marquee UND Logo gleichzeitig: beide sind VLC-Subfilter, ohne
                // explizite Filterkette überschreiben sie sich gegenseitig.
                "--sub-filter=marq:logo",
                // Browser-User-Agent: viele Xtream-Panels blocken Requests ohne
                // Browser-UA (407) — ohne UA wartet VLC lange bzw. der Kanal
                // kommt gar nicht. Gleicher UA wie Box/exteplayer3.
                "--http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
            )
            factoryArgs.addAll(AppSettings.hwDecodingArgs())
            // 1.5.14 Windows: VLCs Video-Output baut ein eigenes Kind-Fenster in die
            // Canvas und verschluckt Maus-KLICKS fuer seine eigene Fenster-Prozedur
            // (Maus-BEWEGUNG laesst er durch — Beweis app.log 1.5.13: mouseMoved kam
            // an, mouseClicked nie). --no-mouse-events stellt VLC ab: Klicks und
            // Mausrad erreichen wieder den AWT-Canvas. Nur Windows: auf Linux
            // kommen die Klicks bereits an (Plattform-Regel: nichts anfassen, was geht).
            if (isWindows) factoryArgs.add("--no-mouse-events")
            val f = MediaPlayerFactory(factoryArgs)
            val mp = f.mediaPlayers().newEmbeddedMediaPlayer()
            val surface = f.videoSurfaces().newVideoSurface(videoCanvas)
            mp.videoSurface().set(surface)
            // Deinterlacing: AUTO statt hartem YADIF. libVLC erkennt dann selbst,
            // ob ein Stream interlaced ist (deinterlacen) oder progressiv
            // (nichts tun). Erzwungenes YADIF erzeugt auf progressivem Material
            // (z. B. Kinowelt, 50fps) grüne/blaue Blöcke an den Frame-Rändern.
            mp.video().setDeinterlace(DeinterlaceMode.AUTO)
            // Video-Adjust aktivieren, sonst wirken setBrightness/setContrast nicht.
            mp.video().setAdjustVideo(true)
            mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) {
                    if (currentIsVod) {
                        playing = false
                        onVodEnded?.invoke()
                    }
                }
                override fun error(mediaPlayer: MediaPlayer) {
                    playing = false
                }
                override fun playing(mediaPlayer: MediaPlayer) {
                    playing = true
                }
                override fun stopped(mediaPlayer: MediaPlayer) {
                    playing = false
                }
                override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
                    // 1.5.16 Windows: VLC legt seine zwei Kindfenster
                    // ('VLC video main' + 'VLC video output') an und FRISST alle
                    // Maus-KLICKS (Beweis: EnumChildWindows-Dump; app.log 1.5.13-15
                    // zeigt mouseMoved kam an, mouseClicked NIEMALS). Hier und per
                    // Retry-Timer WS_EX_TRANSPARENT darauf setzen -> Klicks und
                    // Mausrad erreichen wieder die AWT-Canvas.
                    if (isWindows) scheduleVlcClickThrough()
                    onVideoOutput?.invoke()
                }
            })
            factory = f
            this.mediaPlayer = mp
            true
        }.getOrDefault(false)
    }

    private fun setenv(name: String, value: String) {
        runCatching {
            if (isWindows) {
                // Windows: setenv existiert nicht; Kernel32.SetEnvironmentVariable
                // (jna-platform ist über vlcj auf dem Classpath).
                com.sun.jna.platform.win32.Kernel32.INSTANCE.SetEnvironmentVariable(name, value)
            } else {
                val libc = Native.load("c", LibC::class.java)
                libc.setenv(name, value, 1)
            }
        }
    }

    private interface LibC : Library {
        fun setenv(name: String, value: String, overwrite: Int): Int
    }

    /** Stream/MRL abspielen. isVod = true verhindert Reconnect und feuert onVodEnded.
     *  timeshift = true puffert den Stream auf die Platte (libVLC input-timeshift),
     *  sodass Pause + Zurückspulen am Live-Kanal möglich sind. */
    override fun play(url: String, isVod: Boolean, timeshift: Boolean, timeshiftDir: String?, startPosMs: Long) {
        PlayerLog.log("VLC play vod=$isVod timeshift=$timeshift start=$startPosMs url=${url.take(80)}")
        val mp = mediaPlayer ?: return
        currentUrl = url
        currentIsVod = isVod
        if (timeshift) {
            val opts = mutableListOf(":input-timeshift-access=file")
            timeshiftDir?.takeIf { it.isNotBlank() }?.let {
                opts.add(":input-timeshift-dir=$it")
            }
            mp.media().play(url, *opts.toTypedArray())
        } else if (startPosMs > 0) {
            // Resume: VLC öffnet den Titel DIREKT ab dieser Position — ein
            // seekTo() direkt nach dem asynchronen play() verpufft, weil die
            // Media zu dem Zeitpunkt noch nicht geladen ist.
            mp.media().play(url, ":start-time=${startPosMs / 1000.0}")
        } else {
            mp.media().play(url)
        }
    }

    override fun pause() {
        PlayerLog.mark("VLC pause")
        mediaPlayer?.controls()?.setPause(true)
        PlayerLog.done("pause")
    }

    override fun resume() {
        PlayerLog.mark("VLC resume")
        mediaPlayer?.controls()?.setPause(false)
        PlayerLog.done("resume")
    }

    override fun stop() {
        PlayerLog.mark("VLC stop")
        currentUrl = null
        mediaPlayer?.controls()?.stop()
        playing = false
        PlayerLog.done("stop")
    }

    override fun seekTo(posMs: Long) {
        PlayerLog.mark("VLC seekTo $posMs")
        mediaPlayer?.controls()?.setTime(posMs)
        PlayerLog.done("seekTo")
    }

    override fun currentPositionMs(): Long =
        mediaPlayer?.status()?.time() ?: 0L

    override fun durationMs(): Long =
        mediaPlayer?.status()?.length() ?: 0L

    /** Lautstärke (0–200, 100 = normal). */
    override fun setVolume(v: Int) {
        PlayerLog.mark("VLC setVolume $v")
        mediaPlayer?.audio()?.setVolume(v)
        PlayerLog.done("setVolume")
    }

    fun volume(): Int =
        mediaPlayer?.audio()?.volume() ?: 100

    /** Multi-View: nur der aktive Stream hat Ton, die anderen sind stumm. */
    override fun setMuted(muted: Boolean) {
        mediaPlayer?.audio()?.setMute(muted)
    }

    /** Helligkeit (0.0–2.0, 1.0 = neutral). */
    override fun setBrightness(b: Float) {
        PlayerLog.mark("VLC setBrightness $b")
        mediaPlayer?.video()?.setBrightness(b)
        PlayerLog.done("setBrightness")
    }

    fun brightness(): Float =
        mediaPlayer?.video()?.brightness() ?: 1.0f

    /**
     * OSD direkt ins Videobild (libVLC Marquee). Im Gegensatz zu Compose-Overlays
     * verschwindet das NICHT hinter dem heavyweight Video-Canvas — libVLC zeichnet
     * es in den Frame. timeoutMs = 0 blendet nie automatisch aus.
     */
    override fun showOsd(text: String, timeoutMs: Int,
                position: MarqueePosition,
                color: java.awt.Color) {
        // 1.5.12 Plattform-Split: Das VLC-Marquee verkeilt sich unter Windows
        // im D3D-Video-Output (Beweis: app.log 1.5.11, 'marquee' ohne Rueckkehr,
        // Bild+App tot bei schnellem Zweit-OSD). Linux laeuft sauber und bleibt
        // beim Marquee. Windows bekommt ein reines Java-Overlay-Fenster ueber
        // dem Canvas — KEINE native Calls, kann nicht deadlocken, klick-durchlaessig.
        if (isWindows) { showOsdOverlay(text, timeoutMs, color); return }
        val mp = mediaPlayer ?: return
        runCatching {
            val m = mp.marquee()
            PlayerLog.mark("VLC marquee '${text.take(20)}'")
            m.setColour(color)
            m.setSize(20)
            m.setOpacity(220)
            m.setTimeout(timeoutMs)
            m.setPosition(position)
            m.setText(text)
            m.enable(true)
            PlayerLog.done("marquee")
        }
    }

    // ---------------- Windows-OSD: Java-Overlay statt VLC-Filter ----------------

    private var osdWin: JWindow? = null
    private var osdLabel: JLabel? = null
    private var osdHide: Timer? = null

    /** user32 für WS_EX_TRANSPARENT (OSD + VLC-Video-Kindfenster). */
    private interface User32Lib : Library {
        fun GetWindowLongW(hwnd: Long, index: Int): Int
        fun SetWindowLongW(hwnd: Long, index: Int, style: Int): Int
        fun EnumChildWindows(parent: Long, cb: WndEnumProc, lParam: Long): Boolean
        fun GetClassNameW(hwnd: Long, buf: CharArray, max: Int): Int
    }

    private interface WndEnumProc : com.sun.jna.Callback {
        fun callback(hwnd: Long, lParam: Long): Boolean
    }

    private fun showOsdOverlay(text: String, timeoutMs: Int, color: java.awt.Color) {
        SwingUtilities.invokeLater {
            runCatching {
                PlayerLog.mark("OSD overlay '${text.take(20)}'")
                val canvas = videoCanvas ?: return@runCatching
                val win = ensureOsdWindow() ?: return@runCatching
                val label = osdLabel ?: return@runCatching
                label.text = "<html><div style='text-align:center'>${text.replace("\n", "<br>")}</div></html>"
                label.foreground = color
                val cs = canvas.size
                val pref = label.preferredSize
                val w = (pref.width + 36).coerceAtMost(cs.width - 24)
                val h = pref.height + 18
                val loc = runCatching { canvas.locationOnScreen }.getOrNull() ?: return@runCatching
                win.setBounds(
                    loc.x + (cs.width - w) / 2,
                    loc.y + cs.height - h - 40,
                    w, h
                )
                win.isVisible = true
                makeClickThrough(win)
                if (timeoutMs > 0) {
                    osdHide?.stop()
                    osdHide = Timer(timeoutMs) { win.isVisible = false }
                        .apply { isRepeats = false; start() }
                }
                PlayerLog.done("OSD overlay")
            }.onFailure { PlayerLog.log("OSD overlay FEHLER: ${it.javaClass.simpleName} ${it.message}") }
        }
    }

    /** Einmalig das Overlay-Fenster erzeugen (Owner = Fenster des Canvas,
     *  damit es im Z-Stack ueber dem Video bleibt). */
    private fun ensureOsdWindow(): JWindow? {
        if (osdWin != null) return osdWin
        var owner: java.awt.Container? = videoCanvas?.parent
        while (owner?.parent != null) owner = owner.parent
        if (owner !is java.awt.Window) return null
        val w = JWindow(owner)
        val label = JLabel()
        label.foreground = java.awt.Color.WHITE
        label.background = java.awt.Color(0f, 0f, 0f, 0.72f)
        label.isOpaque = true
        label.border = BorderFactory.createEmptyBorder(8, 16, 8, 16)
        label.horizontalAlignment = SwingConstants.CENTER
        label.font = label.font.deriveFont(java.awt.Font.BOLD, 16f)
        w.contentPane.add(label)
        w.setFocusableWindowState(false)
        w.isVisible = false
        osdWin = w
        osdLabel = label
        return w
    }

    // 1.5.16: VLC-Video-Kindfenster klick-durchlaessig machen.
    // VLC baut die Fenster erst Millisekunden nach dem videoOutput-Event ->
    // Retry-Timer, bis beide Fenster gefunden wurden (oder aufgeben nach 5s).
    private var vlcCtTimer: Timer? = null
    private var vlcCtAttempts = 0

    private fun scheduleVlcClickThrough() {
        vlcCtAttempts = 0
        vlcCtTimer?.stop()
        vlcCtTimer = Timer(600) {
            vlcCtAttempts++
            val applied = applyVlcClickThrough()
            if (applied >= 2 || vlcCtAttempts >= 8) {
                vlcCtTimer?.stop()
                PlayerLog.log("VLC click-through fertig: $applied Fenster, Versuch $vlcCtAttempts")
            }
        }.apply { isRepeats = true; initialDelay = 300; start() }
    }

    /** Alle 'VLC video*'-Kindfenster unter dem Top-Level-Fenster finden und
     *  WS_EX_TRANSPARENT setzen. Rueckgabe: Anzahl behandelter Fenster. */
    private fun applyVlcClickThrough(): Int {
        return runCatching {
            val canvas = videoCanvas ?: return 0
            val top = Native.getComponentID(canvas)
            if (top == 0L) return 0
            // Kindfenster haengen am Fenster-Baum der App -> vom Top-Level-Window
            // des Canvas ausgehen (nicht nur von der Canvas selbst).
            var root: java.awt.Container? = canvas.parent
            while (root?.parent != null) root = root.parent
            val hwndRoot = if (root is java.awt.Window) Native.getComponentID(root) else top
            if (hwndRoot == 0L) return 0
            val u32 = Native.load("user32", User32Lib::class.java)
            val applied = intArrayOf(0)
            val proc = object : WndEnumProc {
                override fun callback(hwnd: Long, lParam: Long): Boolean {
                    val buf = CharArray(64)
                    val n = u32.GetClassNameW(hwnd, buf, 64)
                    val cls = if (n > 0) String(buf, 0, n) else ""
                    if (cls.startsWith("VLC video")) {
                        val ex = u32.GetWindowLongW(hwnd, -20) // GWL_EXSTYLE
                        if (ex and 0x20 == 0) { // WS_EX_TRANSPARENT noch nicht gesetzt
                            u32.SetWindowLongW(hwnd, -20, ex or 0x20)
                            PlayerLog.log("WS_EX_TRANSPARENT gesetzt auf '$cls' (hwnd=$hwnd)")
                            applied[0]++
                        }
                    }
                    return true
                }
            }
            u32.EnumChildWindows(hwndRoot, proc, 0)
            // auch den Canvas-Teilbaum abdecken (falls VLC dort einhaengt)
            if (hwndRoot != top) u32.EnumChildWindows(top, proc, 0)
            applied[0]
        }.onFailure {
            PlayerLog.log("VLC click-through FEHLER: ${it.javaClass.simpleName} ${it.message}")
        }.getOrDefault(0)
    }

    /** WS_EX_TRANSPARENT setzen: Maus-Klicks gehen DURCH das OSD hindurch. */
    private fun makeClickThrough(w: JWindow) {
        runCatching {
            val hwnd = Native.getComponentID(w)
            if (hwnd == 0L) return
            val u32 = Native.load("user32", User32Lib::class.java)
            val style = u32.GetWindowLongW(hwnd, -20) // GWL_EXSTYLE
            u32.SetWindowLongW(hwnd, -20, style or 0x20) // WS_EX_TRANSPARENT
        }
    }

    /** Senderlogo als VLC-Logo-Overlay (ins Videobild, links unten).
     *  Windows 1.5.12: no-op — der Logo-Filter ist dieselbe Deadlock-Klasse
     *  wie Marquee (VLC-Filter-Churn im D3D-Output). Kosmetik entfaellt dort. */
    fun setLogo(path: String?) {
        if (isWindows) return
        val mp = mediaPlayer ?: return
        if (path == null || path.isBlank()) {
            mp.logo().enable(false)
            return
        }
        runCatching {
            mp.logo().setFile(path)
            mp.logo().setPosition(uk.co.caprica.vlcj.player.base.LogoPosition.BOTTOM_LEFT)
            mp.logo().setOpacity(0.95f)
        }
    }

    /** Logo ein-/ausblenden (zusammen mit dem OSD). */
    fun showLogo(v: Boolean) {
        if (isWindows) return
        runCatching { mediaPlayer?.logo()?.enable(v) }
    }

    /** Relatives Spulen (±ms) — für Timeshift im Archiv-Stream. */
    fun seekBy(deltaMs: Long) {
        runCatching { mediaPlayer?.controls()?.skipTime(deltaMs) }
    }

    fun hideOsd() {
        if (isWindows) {
            SwingUtilities.invokeLater {
                osdHide?.stop()
                osdWin?.isVisible = false
            }
            return
        }
        runCatching { mediaPlayer?.marquee()?.enable(false) }
    }

    /** Frame-Grab für die Abspann-Erkennung (Downsample 48×27). */
    override fun snapshot(): java.awt.image.BufferedImage? =
        runCatching { mediaPlayer?.snapshots()?.get(48, 27) }.getOrNull()

    override fun release() {
        PlayerLog.mark("VLC release")
        runCatching {
            mediaPlayer?.release()
            factory?.release()
        }
        if (isWindows) SwingUtilities.invokeLater {
            osdHide?.stop()
            osdWin?.dispose()
            osdWin = null
            osdLabel = null
        }
        PlayerLog.done("release")
        mediaPlayer = null
        factory = null
        playing = false
    }

    /** Muss auf dem EDT gerufen werden. */
    fun ensureOnEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw IllegalStateException("VlcPlayer.init() muss auf dem EDT laufen")
        }
    }
}
