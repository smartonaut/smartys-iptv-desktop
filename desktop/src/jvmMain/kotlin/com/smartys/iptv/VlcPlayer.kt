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
class VlcPlayer {

    private var factory: MediaPlayerFactory? = null
    private var mediaPlayer: EmbeddedMediaPlayer? = null

    @Volatile
    var playing: Boolean = false
        private set

    /** Video-Ziel (muss vor play() gesetzt sein, damit das Bild dort erscheint). */
    var videoCanvas: Canvas? = null

    /** Wird beim natürlichen Ende eines VOD-Titels aufgerufen. */
    var onVodEnded: (() -> Unit)? = null

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
    fun init(): Boolean {
        if (mediaPlayer != null) return true
        return runCatching {
            val bundled = ensureLibVlc()
            if (bundled != null) {
                // 1) libvlccore zuerst laden (transitive Abhängigkeit von libvlc)
                runCatching { System.load(File(bundled, coreFileName).absolutePath) }
                // 2) JNA-Suchpfad für libvlc
                NativeLibrary.addSearchPath(jnaLibName, bundled.absolutePath)
                // 3) Plugin-Pfad über VLC_PLUGIN_PATH (--plugin-path existiert in libVLC 3 nicht mehr)
                //    Windows: plugins/ liegt direkt unter dem libvlc-Ordner.
                //    Linux:   plugins/ liegt neben dem lib/-Ordner (eine Ebene drüber).
                val pluginDir = if (isWindows)
                    File(bundled, "plugins")
                else
                    File(bundled.parentFile, "plugins")
                if (pluginDir.exists()) setenv("VLC_PLUGIN_PATH", pluginDir.absolutePath)
            }

            val factoryArgs = mutableListOf("--no-video-title-show", "--network-caching=1500")
            AppSettings.hwDecodingArg().takeIf { it.isNotBlank() }?.let { factoryArgs.add(it) }
            val f = MediaPlayerFactory(factoryArgs)
            val mp = f.mediaPlayers().newEmbeddedMediaPlayer()
            val surface = f.videoSurfaces().newVideoSurface(videoCanvas)
            mp.videoSurface().set(surface)
            // Deinterlacing: Live-TV ist interlaced — ohne das gibt es den
            // "Kamm-Effekt" (verzerrte Kanten bei Bewegung).
            mp.video().setDeinterlace(DeinterlaceMode.YADIF)
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
    fun play(url: String, isVod: Boolean = false, timeshift: Boolean = false, timeshiftDir: String? = null) {
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

    fun pause() {
        mediaPlayer?.controls()?.setPause(true)
    }

    fun resume() {
        mediaPlayer?.controls()?.setPause(false)
    }

    fun stop() {
        currentUrl = null
        mediaPlayer?.controls()?.stop()
        playing = false
    }

    fun seekTo(posMs: Long) {
        mediaPlayer?.controls()?.setTime(posMs)
    }

    fun currentPositionMs(): Long =
        mediaPlayer?.status()?.time() ?: 0L

    fun durationMs(): Long =
        mediaPlayer?.status()?.length() ?: 0L

    /** Lautstärke (0–200, 100 = normal). */
    fun setVolume(v: Int) {
        mediaPlayer?.audio()?.setVolume(v)
    }

    fun volume(): Int =
        mediaPlayer?.audio()?.volume() ?: 100

    /** Helligkeit (0.0–2.0, 1.0 = neutral). */
    fun setBrightness(b: Float) {
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
            m.setSize(28)
            m.setOpacity(230)
            m.setTimeout(timeoutMs)
            m.setPosition(position)
            m.setText(text)
            m.enable(true)
        }
    }

    fun hideOsd() {
        runCatching { mediaPlayer?.marquee()?.enable(false) }
    }

    /** Frame-Grab für die Abspann-Erkennung (Downsample 48×27). */
    fun snapshot(): java.awt.image.BufferedImage? =
        runCatching { mediaPlayer?.snapshots()?.get(48, 27) }.getOrNull()

    fun release() {
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
