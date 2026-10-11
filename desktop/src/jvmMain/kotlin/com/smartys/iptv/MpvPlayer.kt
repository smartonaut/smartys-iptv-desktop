package com.smartys.iptv

import java.awt.Canvas
import java.io.File
import javax.swing.SwingUtilities

/**
 * Desktop-Wiedergabe über libmpv (nativ, JNA-Bindings in MpvLib.kt).
 *
 * Gleiche öffentliche API wie VlcPlayer, damit der Rest des Players
 * (Players.kt, RecordingEngine) die Engine austauschen kann, ohne die
 * Aufrufstellen zu ändern.
 *
 * Rendering: libmpv zeichnet in das native Window des AWT-Canvas (wid),
 * d.h. Hardware-Beschleunigung (VAAPI/VDPAU/DXVA) bleibt erhalten.
 */
class MpvPlayer : VodPlayerApi {

    private var handle: com.sun.jna.Pointer? = null
    private var eventThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var playing: Boolean = false
        private set

    /** Video-Ziel (muss vor play() gesetzt sein, damit das Bild dort erscheint). */
    override var videoCanvas: Canvas? = null

    /** Wird beim natürlichen Ende eines VOD-Titels aufgerufen. */
    override var onVodEnded: (() -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    var onChannelStep: ((Int) -> Unit)? = null

    private var currentUrl: String? = null
    private var currentIsVod = false

    /**
     * Initialisiert libmpv (einmal pro Player). Muss auf dem EDT laufen,
     * da der Canvas dort lebt und sein natives Fenster sichtbar sein muss.
     */
    override fun init(): Boolean {
        if (handle != null) return true
        return runCatching {
            val ctx = MpvLib.INSTANCE.mpv_create()
            if (ctx == null) {
                System.err.println("[MpvPlayer] mpv_create gab NULL zurück")
                return@runCatching false
            }
            System.err.println("[MpvPlayer] mpv_create OK, setze Optionen")
            // Deinterlacing auto + Puffer fürs Netzwerk
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "deinterlace", "auto")
            // Fenster ohne native Titelleiste (eigene X/Minimieren-Buttons statt des nativen X)
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "border", "no")
            // Eingabe: Mausrad + Pfeiltasten = Lautstärke, +/- = Senderwechsel (per Property-Signal)
            runCatching {
                val conf = File(System.getProperty("user.home"), ".smartys-iptv/input.conf")
                conf.parentFile?.mkdirs()
                conf.writeText(
                    "WHEEL_UP add volume 5\n" +
                    "WHEEL_DOWN add volume -5\n" +
                    "UP add volume 5\n" +
                    "DOWN add volume -5\n" +
                    "+ set property \"smartys-next\" yes\n" +
                    "- set property \"smartys-prev\" yes\n"
                )
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "input-conf", conf.absolutePath)
            }
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "network-timeout", "2")
            // Schnellerer Senderwechsel: kein Stream-Probing + kein Pausieren beim Puffern
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-lavf-probe-info", "nostreams")
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "cache-pause", "no")
            when (AppSettings.cacheMode()) {
                "none" -> {
                    // Kein Cache — Bild startet sofort (stottert bei schwacher Leitung)
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "cache", "no")
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-readahead-secs", "1")
                }
                "max" -> {
                    // Großer Cache für schwache Leitungen (längere Startzeit)
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "cache", "yes")
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-max-bytes", "64MiB")
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-readahead-secs", "3")
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "cache-secs", "10")
                }
                else -> {
                    // Auto: moderater Cache (schneller Start)
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "cache", "yes")
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-max-bytes", "16MiB")
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-readahead-secs", "1")
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "cache-secs", "2")
                }
            }
            // Software-Decoding: initiiert schneller als hwdec-Erkennung (auto-safe)
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "hwdec", "no")
            // Vulkan (gpu-next) schlägt auf Hybrid-GPU/Wayland fehl (VK_ERROR_OUT_OF_DEVICE_MEMORY)
            // — OpenGL-Renderer erzwingen, der hier zuverlässig läuft (VAAPI).
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "vo", "gpu")
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "gpu-api", "opengl")

            // Native Window-ID des Canvas setzen (X11/HWND), damit libmpv
            // direkt hinein rendert.
            videoCanvas?.let { canvas ->
                nativeWindowId(canvas)?.let { wid ->
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "wid", wid.toString())
                } ?: System.err.println("[MpvPlayer] Warnung: nativeWindowId null — mpv rendert evtl. in separates Fenster")
            }

            MpvLib.INSTANCE.mpv_set_option_string(ctx, "terminal", "yes")
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "msg-level", "all=info")
            val initCode = MpvLib.INSTANCE.mpv_initialize(ctx)
            if (initCode < 0) {
                System.err.println("[MpvPlayer] mpv_initialize FEHLER-Code: $initCode")
                MpvLib.INSTANCE.mpv_terminate_destroy(ctx)
                return@runCatching false
            }
            System.err.println("[MpvPlayer] mpv_initialize OK")

            // Playback-Events beobachten (Ende eines VODs)
            MpvLib.INSTANCE.mpv_observe_property(ctx, 1L, "playback-time", MpvFormat.DOUBLE)
            // Senderwechsel-Signale (+/-) beobachten
            MpvLib.INSTANCE.mpv_observe_property(ctx, 2L, "smartys-next", MpvFormat.FLAG)
            MpvLib.INSTANCE.mpv_observe_property(ctx, 3L, "smartys-prev", MpvFormat.FLAG)

            handle = ctx
            running = true
            startEventLoop()
            true
        }.getOrElse { e ->
            System.err.println("[MpvPlayer] init Exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun startEventLoop() {
        eventThread = Thread {
            val ctx = handle ?: return@Thread
            while (running) {
                val evPtr = MpvLib.INSTANCE.mpv_wait_event(ctx, 0.5)
                val ev = MpvEvent(evPtr)
                when (ev.event_id) {
                    MpvEvent.EVENT_PLAYBACK_RESTART -> playing = true
                    MpvEvent.EVENT_PROPERTY_CHANGE -> {
                        when (ev.reply_userdata) {
                            2L -> {
                                onChannelStep?.invoke(+1)
                                MpvLib.INSTANCE.mpv_set_property_string(ctx, "smartys-next", "no")
                            }
                            3L -> {
                                onChannelStep?.invoke(-1)
                                MpvLib.INSTANCE.mpv_set_property_string(ctx, "smartys-prev", "no")
                            }
                        }
                    }
                    MpvEvent.EVENT_END_FILE -> {
                        if (currentIsVod) {
                            playing = false
                            onVodEnded?.invoke()
                        } else {
                            // Live (Floating): reason auslesen — mpv_event_end_file.reason
                            // 0=EOF, 2=STOP, 3=QUIT, 4=ERROR, 5=REDIRECT
                            val reason = ev.data?.getInt(0) ?: -1
                            // QUIT (3) = mpv-Fenster wurde geschlossen (X)
                            if (reason == 3) {
                                running = false
                                onClosed?.invoke()
                            }
                            // STOP/REDIRECT (Senderwechsel) bewusst ignorieren
                        }
                    }
                    MpvEvent.EVENT_CLOSE_WIN -> {
                        // Nutzer hat das mpv-Fenster (X) geschlossen → mpv beenden
                        running = false
                        onClosed?.invoke()
                    }
                    MpvEvent.EVENT_SHUTDOWN -> running = false
                }
            }
            // mpv sauber beenden, damit das Fenster wirklich schließt
            val h = handle
            handle = null
            h?.let { runCatching { MpvLib.INSTANCE.mpv_terminate_destroy(it) } }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Ermittelt die native Window-ID des AWT-Canvas (X11 auf Linux, HWND auf Windows). */
    private fun nativeWindowId(canvas: Canvas): Long? {
        return runCatching {
            // Canvas muss sichtbar sein, damit der native Peer existiert.
            if (!canvas.isDisplayable) return@runCatching null
            // getPeer() ist public, aber sein Rückgabetyp (java.awt.peer) ist in
            // JDK 9+ nicht exportiert — deshalb via Reflection aufrufen.
            val getPeer = java.awt.Component::class.java.getMethod("getPeer")
            val peer: Any = getPeer.invoke(canvas) ?: return@runCatching null
            // X11: sun.awt.X11.XWindowPeer.getWindow(); Windows: WComponentPeer.getHWnd()
            val methods = (peer.javaClass.methods + peer.javaClass.declaredMethods)
                .distinctBy { it.name to it.parameterTypes.size }
            val m = methods.firstOrNull { it.name == "getWindow" || it.name == "getHWnd" }
                ?: return@runCatching null
            m.isAccessible = true
            (m.invoke(peer) as? Number)?.toLong()
        }.getOrNull()
    }

    /** Fenster-Geometrie (x, y, Breite, Höhe) in Pixeln — zum Mitziehen des Overlays. */
    fun windowGeom(): IntArray {
        val ctx = handle ?: return intArrayOf(0, 0, 0, 0)
        fun getInt(name: String): Int = runCatching {
            val mem = com.sun.jna.Memory(8)
            MpvLib.INSTANCE.mpv_get_property(ctx, name, MpvFormat.INT64, mem)
            mem.getLong(0).toInt()
        }.getOrDefault(0)
        return intArrayOf(getInt("window-x"), getInt("window-y"), getInt("osd-width"), getInt("osd-height"))
    }

    /** Stream/MRL abspielen. isVod = true verhindert Reconnect und feuert onVodEnded. */
    override fun play(url: String, isVod: Boolean, timeshift: Boolean, timeshiftDir: String?) {
        val ctx = handle ?: return
        currentUrl = url
        currentIsVod = isVod
        val args = arrayOf("loadfile", url, "replace")
        MpvLib.INSTANCE.mpv_command(ctx, args)
        // Timeshift: auf die Platte puffern (wie libVLC input-timeshift)
        if (timeshift) {
            MpvLib.INSTANCE.mpv_set_property_string(ctx, "stream-buffer-size", "64MiB")
            timeshiftDir?.takeIf { it.isNotBlank() }?.let {
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "record-file", it + "/timeshift.ts")
            }
        }
    }

    override fun pause() {
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "pause", "yes") }
    }

    override fun resume() {
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "pause", "no") }
    }

    override fun stop() {
        currentUrl = null
        handle?.let { MpvLib.INSTANCE.mpv_command(it, arrayOf("stop")) }
        playing = false
    }

    fun minimize() {
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "window-minimized", "yes") }
    }

    override fun seekTo(posMs: Long) {
        handle?.let {
            MpvLib.INSTANCE.mpv_command(it, arrayOf("seek", (posMs / 1000.0).toString(), "absolute"))
        }
    }

    override fun currentPositionMs(): Long =
        (getDouble("time-pos") * 1000.0).toLong()

    override fun durationMs(): Long =
        (getDouble("duration") * 1000.0).toLong()

    /** Lautstärke (0–200, 100 = normal). libmpv erwartet 0–100. */
    override fun setVolume(v: Int) {
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "volume", (v / 2).toString()) }
    }

    fun volume(): Int =
        (getDouble("volume") * 2.0).toInt()

    /** Multi-View: nur der aktive Stream hat Ton, die anderen sind stumm. */
    fun setMuted(muted: Boolean) {
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "mute", if (muted) "yes" else "no") }
    }

    /** Helligkeit (0.0–2.0, 1.0 = neutral). libmpv: -100..100, 0 = neutral. */
    override fun setBrightness(b: Float) {
        handle?.let {
            MpvLib.INSTANCE.mpv_set_property_string(it, "brightness", ((b - 1.0f) * 100).toInt().toString())
        }
    }

    fun brightness(): Float =
        (getDouble("brightness") / 100.0).toFloat() + 1.0f

    /** OSD direkt ins Videobild. libmpv (show-text) kennt keine Position/Farbe,
     *  daher werden position/color akzeptiert, aber ignoriert (zentriert angezeigt). */
    fun showOsd(
        text: String,
        timeoutMs: Int = 0,
        position: uk.co.caprica.vlcj.player.base.MarqueePosition = uk.co.caprica.vlcj.player.base.MarqueePosition.BOTTOM,
        color: java.awt.Color = java.awt.Color.WHITE
    ) {
        handle?.let {
            MpvLib.INSTANCE.mpv_command(it, arrayOf("show-text", text, (timeoutMs / 1000).toString()))
        }
    }

    fun hideOsd() {
        handle?.let { MpvLib.INSTANCE.mpv_command(it, arrayOf("show-text", "", "0")) }
    }

    /** Screenshot für die Abspann-Erkennung (CreditsWatcher). */
    override fun snapshot(): java.awt.image.BufferedImage? {
        val ctx = handle ?: return null
        val path = System.getProperty("java.io.tmpdir") + "/smartys_snapshot.png"
        return runCatching {
            MpvLib.INSTANCE.mpv_command(ctx, arrayOf("screenshot-to-file", path, "video"))
            val f = java.io.File(path)
            var waited = 0
            while (!f.exists() && waited < 2000) {
                Thread.sleep(20)
                waited += 20
            }
            if (f.exists()) javax.imageio.ImageIO.read(f) else null
        }.getOrNull()
    }

    override fun release() {
        running = false
        runCatching {
            handle?.let { MpvLib.INSTANCE.mpv_terminate_destroy(it) }
        }
        handle = null
        playing = false
    }

    /** Muss auf dem EDT gerufen werden. */
    fun ensureOnEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw IllegalStateException("MpvPlayer.init() muss auf dem EDT laufen")
        }
    }

    // ---- Property-Helfer ----

    private fun getDouble(name: String): Double {
        val ctx = handle ?: return 0.0
        return runCatching {
            val ref = com.sun.jna.ptr.DoubleByReference()
            if (MpvLib.INSTANCE.mpv_get_property(ctx, name, MpvFormat.DOUBLE, ref.pointer) < 0) return 0.0
            ref.value
        }.getOrDefault(0.0)
    }
}
