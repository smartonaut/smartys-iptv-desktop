package com.smartys.iptv

import java.awt.Canvas
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
class MpvPlayer {

    private var handle: com.sun.jna.Pointer? = null
    private var eventThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var playing: Boolean = false
        private set

    /** Video-Ziel (muss vor play() gesetzt sein, damit das Bild dort erscheint). */
    var videoCanvas: Canvas? = null

    /** Wird beim natürlichen Ende eines VOD-Titels aufgerufen. */
    var onVodEnded: (() -> Unit)? = null

    private var currentUrl: String? = null
    private var currentIsVod = false

    /**
     * Initialisiert libmpv (einmal pro Player). Muss auf dem EDT laufen,
     * da der Canvas dort lebt und sein natives Fenster sichtbar sein muss.
     */
    fun init(): Boolean {
        if (handle != null) return true
        return runCatching {
            val ctx = MpvLib.INSTANCE.mpv_create()
                ?: return@runCatching false
            // Deinterlacing auto + Puffer fürs Netzwerk
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "deinterlace", "auto")
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "network-timeout", "5")
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "cache", "yes")
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-max-bytes", "64MiB")
            // Hardware-Decoding (wenn verfügbar; fällt sonst auf Software zurück)
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "hwdec", "auto-safe")

            // Native Window-ID des Canvas setzen (X11/HWND), damit libmpv
            // direkt hinein rendert.
            videoCanvas?.let { canvas ->
                nativeWindowId(canvas)?.let { wid ->
                    MpvLib.INSTANCE.mpv_set_option_string(ctx, "wid", wid.toString())
                }
            }

            if (MpvLib.INSTANCE.mpv_initialize(ctx) < 0) {
                MpvLib.INSTANCE.mpv_terminate_destroy(ctx)
                return@runCatching false
            }

            // Playback-Events beobachten (Ende eines VODs)
            MpvLib.INSTANCE.mpv_observe_property(ctx, 1L, "playback-time", MpvFormat.DOUBLE)

            handle = ctx
            running = true
            startEventLoop()
            true
        }.getOrDefault(false)
    }

    private fun startEventLoop() {
        eventThread = Thread {
            val ctx = handle ?: return@Thread
            while (running) {
                val evPtr = MpvLib.INSTANCE.mpv_wait_event(ctx, 0.5)
                val ev = MpvEvent(evPtr)
                when (ev.event_id) {
                    MpvEvent.EVENT_PLAYBACK_RESTART -> playing = true
                    MpvEvent.EVENT_END_FILE -> {
                        if (currentIsVod) {
                            playing = false
                            onVodEnded?.invoke()
                        }
                    }
                    MpvEvent.EVENT_SHUTDOWN -> running = false
                }
            }
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
            val m = peer.javaClass.methods.firstOrNull {
                it.name == "getWindow" || it.name == "getHWnd"
            } ?: return@runCatching null
            (m.invoke(peer) as? Number)?.toLong()
        }.getOrNull()
    }

    /** Stream/MRL abspielen. isVod = true verhindert Reconnect und feuert onVodEnded. */
    fun play(url: String, isVod: Boolean = false, timeshift: Boolean = false, timeshiftDir: String? = null) {
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

    fun pause() {
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "pause", "yes") }
    }

    fun resume() {
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "pause", "no") }
    }

    fun stop() {
        currentUrl = null
        handle?.let { MpvLib.INSTANCE.mpv_command(it, arrayOf("stop")) }
        playing = false
    }

    fun seekTo(posMs: Long) {
        handle?.let {
            MpvLib.INSTANCE.mpv_command(it, arrayOf("seek", (posMs / 1000.0).toString(), "absolute"))
        }
    }

    fun currentPositionMs(): Long =
        (getDouble("time-pos") * 1000.0).toLong()

    fun durationMs(): Long =
        (getDouble("duration") * 1000.0).toLong()

    /** Lautstärke (0–200, 100 = normal). libmpv erwartet 0–100. */
    fun setVolume(v: Int) {
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "volume", (v / 2).toString()) }
    }

    fun volume(): Int =
        (getDouble("volume") * 2.0).toInt()

    /** Multi-View: nur der aktive Stream hat Ton, die anderen sind stumm. */
    fun setMuted(muted: Boolean) {
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "mute", if (muted) "yes" else "no") }
    }

    /** Helligkeit (0.0–2.0, 1.0 = neutral). libmpv: -100..100, 0 = neutral. */
    fun setBrightness(b: Float) {
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
    fun snapshot(): java.awt.image.BufferedImage? {
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

    fun release() {
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
