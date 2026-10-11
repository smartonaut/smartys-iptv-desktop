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
import javax.swing.SwingUtilities

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
                val marker = if (isWindows)
                    File(home, "libvlccore.dll")
                else
                    File(home, "lib/libdvbpsi.so.10")
                if (!marker.exists()) {
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
                "--network-caching=1500",
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
    override fun play(url: String, isVod: Boolean, timeshift: Boolean, timeshiftDir: String?) {
        val mp = mediaPlayer ?: return
        currentUrl = url
        currentIsVod = isVod
        if (timeshift) {
            val opts = mutableListOf(":input-timeshift-access=file")
            timeshiftDir?.takeIf { it.isNotBlank() }?.let {
                opts.add(":input-timeshift-dir=$it")
            }
            mp.media().play(url, *opts.toTypedArray())
        } else {
            mp.media().play(url)
        }
    }

    override fun pause() {
        mediaPlayer?.controls()?.setPause(true)
    }

    override fun resume() {
        mediaPlayer?.controls()?.setPause(false)
    }

    override fun stop() {
        currentUrl = null
        mediaPlayer?.controls()?.stop()
        playing = false
    }

    override fun seekTo(posMs: Long) {
        mediaPlayer?.controls()?.setTime(posMs)
    }

    override fun currentPositionMs(): Long =
        mediaPlayer?.status()?.time() ?: 0L

    override fun durationMs(): Long =
        mediaPlayer?.status()?.length() ?: 0L

    /** Lautstärke (0–200, 100 = normal). */
    override fun setVolume(v: Int) {
        mediaPlayer?.audio()?.setVolume(v)
    }

    fun volume(): Int =
        mediaPlayer?.audio()?.volume() ?: 100

    /** Helligkeit (0.0–2.0, 1.0 = neutral). */
    override fun setBrightness(b: Float) {
        mediaPlayer?.video()?.setBrightness(b)
    }

    fun brightness(): Float =
        mediaPlayer?.video()?.brightness() ?: 1.0f

    /**
     * OSD direkt ins Videobild (libVLC Marquee). Im Gegensatz zu Compose-Overlays
     * verschwindet das NICHT hinter dem heavyweight Video-Canvas — libVLC zeichnet
     * es in den Frame. timeoutMs = 0 blendet nie automatisch aus.
     */
    fun showOsd(text: String, timeoutMs: Int = 0,
                position: MarqueePosition = MarqueePosition.BOTTOM,
                color: java.awt.Color = java.awt.Color.WHITE) {
        val mp = mediaPlayer ?: return
        runCatching {
            val m = mp.marquee()
            m.setColour(color)
            m.setSize(20)
            m.setOpacity(220)
            m.setTimeout(timeoutMs)
            m.setPosition(position)
            m.setText(text)
            m.enable(true)
        }
    }

    /** Senderlogo als VLC-Logo-Overlay (ins Videobild, links unten). */
    fun setLogo(path: String?) {
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
        runCatching { mediaPlayer?.logo()?.enable(v) }
    }

    /** Relatives Spulen (±ms) — für Timeshift im Archiv-Stream. */
    fun seekBy(deltaMs: Long) {
        runCatching { mediaPlayer?.controls()?.skipTime(deltaMs) }
    }

    fun hideOsd() {
        runCatching { mediaPlayer?.marquee()?.enable(false) }
    }

    /** Frame-Grab für die Abspann-Erkennung (Downsample 48×27). */
    override fun snapshot(): java.awt.image.BufferedImage? =
        runCatching { mediaPlayer?.snapshots()?.get(48, 27) }.getOrNull()

    override fun release() {
        runCatching {
            mediaPlayer?.release()
            factory?.release()
        }
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
