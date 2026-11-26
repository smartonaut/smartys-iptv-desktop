package com.smartys.iptv

import java.awt.Canvas
import java.io.File
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

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
    /**
     * Video-Output bereit. mpv liefert kein eigenes videoOutput-Event wie VLC,
     * deshalb feuert der Player das selbst: sobald play() den Befehl angenommen
     * hat und der Canvas gemappt ist. Der Aufrufer blendet daraufhin den
     * Lade-Indikator aus.
     */
    override var onVideoOutput: (() -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    var onChannelStep: ((Int) -> Unit)? = null

    private var currentUrl: String? = null
    private var currentIsVod = false

    /** Position beim Pausieren — für den Resume-Reconnect-Fix (siehe resume()). */
    private var pausedPosMs = 0L

    /** Ausstehender Seek nach play(): analog VLC — seek direkt nach loadfile
     *  verpufft bei 302-Redirects (signierte URL), deshalb beim
     *  PLAYBACK_RESTART-Event nachholen, falls die Position nicht stimmt. */
    private var pendingSeekMs = 0L
    /** Live-Fallback: Original-.m3u8, falls der .ts-Sofortversuch scheitert. */
    private var liveHlsFallback: String? = null
    private var liveStartedAt = 0L

    /** Einfache Datei-Log für GUI-Diagnose (stderr versinkt bei jpackage-Apps). */
    private fun dbg(msg: String) {
        runCatching {
            val f = File(System.getProperty("user.home"), ".smartys-iptv/player.log")
            f.parentFile?.mkdirs()
            f.appendText("[${System.currentTimeMillis()}] $msg\n")
        }
    }

    /**
     * Initialisiert libmpv (einmal pro Player). Muss auf dem EDT laufen,
     * da der Canvas dort lebt und sein natives Fenster sichtbar sein muss.
     */
    override fun init(): Boolean {
        if (handle != null) return true
        return runCatching {
            val ctx = MpvLib.INSTANCE.mpv_create()
            if (ctx == null) {
                PlayerLog.log(
                    "[MpvPlayer] mpv_create gab NULL zurueck | Datei=${MpvLib.geladeneDatei} " +
                        "| API=${MpvLib.geladeneApi}"
                )
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
            // Live-Start am Live-RAND statt 3 Segmente dahinter (FFmpeg-Default
            // live_start_index=-3): Der Anbieter (smartytv/line.smartytv.xyz) rotiert
            // die .ts-Segment-URLs SOFORT — die letzten 3 Playlist-Einträge sind
            // beim neuen Einstieg bereits geloescht und liefern HTTP 403. Gemessen
            // (mpv.log 08.11.): Segmente 17-19 alle 403, 10 s Playlist-Reload-Warte-
            // schleife, erst Segment 20 -> 200 OK. Mit live_start_index=0 startet
            // der Stream in ~1,5 s statt ~11 s.
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-lavf-o", "live_start_index=0")
            // Stream-Puffer in Sekunden (Einstellung, User-Vorgabe):
            // 0 = kein Cache (Bild startet sofort), >0 = vorhalten gegen Freezer.
            val cacheSecs = AppSettings.cacheSeconds()
            if (cacheSecs <= 0) {
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "cache", "no")
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-readahead-secs", "1")
            } else {
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "cache", "yes")
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-readahead-secs", cacheSecs.toString())
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "cache-secs", cacheSecs.toString())
                val maxMiB = (cacheSecs * 8).coerceAtMost(128)
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "demuxer-max-bytes", "${maxMiB}MiB")
            }
            // Hardware-Decoding optional (Einstellungen → Wiedergabe-Engine).
            // "auto-safe" laesst libmpv den besten Pfad waehlen; "no" initiiert
            // etwas schneller, rechnet aber jeden Frame auf der CPU — bei
            // 4K/H.265 ist das unbrauchbar. Default ist deshalb Hardware an.
            MpvLib.INSTANCE.mpv_set_option_string(
                ctx, "hwdec", if (AppSettings.mpvHwDecoding()) "auto-safe" else "no"
            )
            // Vulkan (gpu-next) schlägt auf Hybrid-GPU/Wayland fehl (VK_ERROR_OUT_OF_DEVICE_MEMORY)
            // — OpenGL-Renderer erzwingen, der hier zuverlässig läuft (VAAPI).
            MpvLib.INSTANCE.mpv_set_option_string(ctx, "vo", "gpu")
            // Windows: D3D11 ist der native Weg für eingebettetes Rendering (wid).
            // Der OpenGL/WGL-Pfad auf einem fremden HWND ist unzuverlässig —
            // deshalb dort explizit D3D11 statt opengl.
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            if (isWin) {
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "gpu-api", "d3d11")
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "gpu-context", "d3d11")
            } else {
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "gpu-api", "opengl")
            }
            // Diagnose: mpv schreibt eine eigene Logdatei. Bei GUI-Apps (jpackage)
            // landet stderr nirgends, deshalb ist das die einzige Spur bei Abstürzen.
            runCatching {
                val mpvLog = File(System.getProperty("user.home"), ".smartys-iptv/mpv.log")
                mpvLog.parentFile?.mkdirs()
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "log-file", mpvLog.absolutePath)
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "log-file-level", "debug")
            }

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
                PlayerLog.log("[MpvPlayer] mpv_initialize FEHLER-Code: $initCode")
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
            dbg("init OK (os=${System.getProperty("os.name")}), Event-Loop startet")
            startEventLoop()
            true
        }.getOrElse { e ->
            // GUI-Apps haben kein stderr: Fehler ins app.log schreiben, sonst
            // ist "init=false" nicht zu deuten.
            PlayerLog.log("[MpvPlayer] init Exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun startEventLoop() {
        eventThread = Thread {
            val ctx = handle ?: return@Thread
            var restarts = 0
            while (running) {
                try {
                    val evPtr = MpvLib.INSTANCE.mpv_wait_event(ctx, 0.5)
                    if (evPtr == null) { dbg("wait_event -> NULL, Event-Loop endet"); break }
                    val ev = MpvEvent(evPtr)
                    // JNA liest die native Struktur NICHT automatisch beim direkten
                    // Feldzugriff (@JvmField). Ohne read() blieb event_id immer 0 —
                    // kein einziges mpv-Event wurde bearbeitet, videoReady blieb false,
                    // der Player zeigte ewig "Verbinde..." obwohl mpv längst spielte.
                    // Bewiesen (MpvReadProof, 08.11.): ohne read() 0 Events, mit 10.
                    ev.read()
                    if (ev.event_id != MpvEvent.EVENT_NONE && ev.event_id != MpvEvent.EVENT_PROPERTY_CHANGE) {
                        dbg("Event empfangen: id=${ev.event_id} (restart=21, file-loaded=8, end=7)")
                    }
                    when (ev.event_id) {
                    MpvEvent.EVENT_PLAYBACK_RESTART -> {
                        playing = true
                        restarts++
                        dbg("PLAYBACK_RESTART #$restarts empfangen, onVideoOutput gesetzt=${onVideoOutput != null}")
                        // Erster Frame ist gerendert — erst JETzt den Canvas
                        // sichtbar schalten (videoReady beim Aufrufer). Feuert
                        // auch nach Senderwechsel/Seek erneut; das ist harmlos,
                        // videoReady ist idempotent.
                        javax.swing.SwingUtilities.invokeLater {
                            dbg("invokeLater: onVideoOutput feuern (gesetzt=${onVideoOutput != null})")
                            onVideoOutput?.invoke()
                            dbg("invokeLater: fertig")
                        }
                        // Resume-Absicherung: seek direkt nach loadfile verpufft
                        // bei 302-Redirects (signierte URL) — Position hier, im
                        // spielenden Zustand, nachholen.
                        val target = pendingSeekMs
                        if (target > 0) {
                            pendingSeekMs = 0
                            thread(isDaemon = true, name = "mpv-pending-seek") {
                                Thread.sleep(400)
                                if (currentPositionMs() < target - 5000) {
                                    dbg("pending-seek: Position ${currentPositionMs()} < $target, hole nach")
                                    seekTo(target)
                                }
                            }
                        }
                    }
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
                            // Hintergrund-Fallback: .ts-Sofortstart gescheitert
                            // (Anbieter ohne TS-Endpunkt) -> sofort mit der
                            // Original-.m3u8 weitermachen, ohne Nutzer-Dialog.
                            if (reason == 4 && liveHlsFallback != null &&
                                System.currentTimeMillis() - liveStartedAt < 15_000) {
                                val fb = liveHlsFallback
                                liveHlsFallback = null
                                if (fb != null) {
                                    dbg("Live .ts gescheitert (reason=4) -> Fallback auf .m3u8")
                                    MpvLib.INSTANCE.mpv_command(ctx, arrayOf("loadfile", fb, "replace"))
                                }
                            }
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
                } catch (t: Throwable) {
                    // Der Event-Loop darf NICHT still sterben: playing/Watchdog und
                    // v.a. das onVideoOutput-Signal (Canvas sichtbar) hängen an ihm.
                    dbg("EVENT-LOOP EXCEPTION: ${t.javaClass.simpleName}: ${t.message} — laufe weiter")
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
            // Canvas muss sichtbar sein, damit das native Fenster existiert.
            if (!canvas.isDisplayable) return@runCatching null
            // JNA nutzt das AWT Native Interface (JAWT) und liefert die echte
            // native ID: HWND unter Windows, X11-Window unter Linux.
            //
            // FRÜHER: Reflection auf Component.getPeer() + getHWnd(). Das war
            // die Ursache für das weiße Bild unter Windows — getPeer() existiert
            // in JDK 17 nicht mehr (NoSuchMethodException), die Funktion gab
            // damit IMMER null zurück, "wid" blieb ungesetzt und libmpv öffnete
            // ein eigenes Fenster statt im Player-Canvas zu rendern.
            val id = com.sun.jna.Native.getComponentID(canvas)
            if (id == 0L) null else id
        }.getOrElse { e ->
            System.err.println("[MpvPlayer] nativeWindowId fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
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
    override fun play(url: String, isVod: Boolean, timeshift: Boolean, timeshiftDir: String?, startPosMs: Long) {
        val ctx = handle ?: return
        dbg("play: vod=$isVod url=${url.take(80)}")
        currentUrl = url
        currentIsVod = isVod
        pendingSeekMs = startPosMs
        // Live-TV: SOFORT den direkten TS-Strom laden (kein Vorab-Check, kein
        // Blockieren). Am Anbieter gemessen (08.11., PC Mama): .ts startet in
        // 0,4 s, HLS .m3u8 braucht 11-12 s, weil die Segment-Tokens des ersten
        // Playlist-Abrufs 403 liefern und FFmpeg erst nach einem Segment-Turnus
        // neu lädt. Schlägt .ts fehl (Anbieter ohne TS-Endpunkt), fällt der
        // Event-Loop im Hintergrund automatisch auf .m3u8 zurück — der Nutzer
        // wartet dann wie bisher, aber nie länger.
        var playUrl = url
        liveHlsFallback = null
        if (!isVod && url.endsWith(".m3u8")) {
            liveHlsFallback = url
            playUrl = url.removeSuffix(".m3u8") + ".ts"
            liveStartedAt = System.currentTimeMillis()
            dbg("Live: versuche .ts-Sofortstart, Fallback=${url.take(60)}")
        }
        val args = arrayOf("loadfile", playUrl, "replace")
        MpvLib.INSTANCE.mpv_command(ctx, args)
        // Resume: Seek direkt nach loadfile (mpv queued die Befehle — vor dem
        // VLC-Umbau so bewährt). "start"-Property bewusst NICHT setzen, sie
        // klebt an allen nachfolgenden loadfiles.
        if (startPosMs > 0) {
            MpvLib.INSTANCE.mpv_command(ctx, arrayOf("seek", (startPosMs / 1000.0).toString(), "absolute"))
        }
        // Timeshift: auf die Platte puffern (wie libVLC input-timeshift)
        if (timeshift) {
            MpvLib.INSTANCE.mpv_set_property_string(ctx, "stream-buffer-size", "64MiB")
            timeshiftDir?.takeIf { it.isNotBlank() }?.let {
                MpvLib.INSTANCE.mpv_set_option_string(ctx, "record-file", it + "/timeshift.ts")
            }
        }
        // mpv kennt kein videoOutput-Event wie VLC. "Bereit" melden wir deshalb
        // erst bei MPV_EVENT_PLAYBACK_RESTART im Event-Loop — dort ist der erste
        // Frame garantiert gerendert. Früher feuerte play() das Signal sofort
        // nach loadfile: der Canvas ging auf Vollbild, bevor mpv etwas gezeichnet
        // hatte, und das ungefüllte native Child-Fenster zeigte sich 3-5 s WEISS.
    }

    override fun pause() {
        pausedPosMs = currentPositionMs()
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "pause", "yes") }
    }

    override fun resume() {
        val pos = pausedPosMs
        handle?.let { MpvLib.INSTANCE.mpv_set_property_string(it, "pause", "no") }
        // Analog VLC: beim Pausieren stoppt mpv das HTTP-Lesen (v.a. bei
        // cache=no), der signierte Token laeuft ab, Resume startet dann bei 0.
        // Position nach kurzer Wartezeit nachholen, falls verloren.
        if (pos > 3000 && currentIsVod) {
            thread(isDaemon = true, name = "mpv-resume-check") {
                Thread.sleep(1000)
                if (currentPositionMs() < 2000) {
                    dbg("resume: Position verloren (war $pos ms), seek zurueck")
                    seekTo(pos)
                }
            }
        }
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
    override fun setMuted(muted: Boolean) {
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
    override fun showOsd(
        text: String,
        timeoutMs: Int,
        position: uk.co.caprica.vlcj.player.base.MarqueePosition,
        color: java.awt.Color
    ) {
        handle?.let {
            MpvLib.INSTANCE.mpv_command(it, arrayOf("show-text", text, (timeoutMs / 1000).toString()))
        }
    }

    override fun hideOsd() {
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
