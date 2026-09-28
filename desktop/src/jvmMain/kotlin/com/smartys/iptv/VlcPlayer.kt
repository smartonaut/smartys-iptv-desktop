package com.smartys.iptv

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
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

        /** Extrahiert das gebündelte libVLC und liefert den lib-Ordner (oder null). */
        fun ensureLibVlc(): File? {
            extracted?.let { return File(it) }
            if (System.getProperty("os.name").lowercase().contains("win")) return null
            return runCatching {
                val home = File(System.getProperty("user.home"), ".smartys-iptv/libvlc")
                // Marker = eine Abhängigkeit, die NUR das VOLLSTÄNDIGE Bundle
                // enthält (das frühere, unvollständige Bundle hatte libdvbpsi
                // & Co. nicht). So erzwingt der Marker eine Neu-Extraktion,
                // wenn ein Rechner noch das alte kaputte Bundle liegen hat.
                val marker = File(home, "lib/libdvbpsi.so.10")
                if (!marker.exists()) {
                    val zipRes = VlcPlayer::class.java.getResourceAsStream("/libvlc/libvlc-linux-x64.zip")
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
                val libDir = File(home, "lib")
                extracted = libDir.absolutePath
                libDir
            }.getOrNull()
        }
    }

    /**
     * Initialisiert die libVLC-Instanz (einmal pro Player). Muss auf dem EDT
     * laufen, da Swing-Komponenten beteiligt sind.
     *
     * Ladereihenfolge (self-contained): erst libvlccore.so.9 via System.load
     * (damit der OS-Loader die transitive NEEDED-Abhängigkeit von libvlc.so.5
     * bereits erfüllt sieht), dann libvlc via JNA-Suchpfad.
     */
    fun init(): Boolean {
        if (mediaPlayer != null) return true
        return runCatching {
            val bundled = ensureLibVlc()
            if (bundled != null) {
                // 1) libvlccore zuerst laden (transitive Abhängigkeit von libvlc.so.5)
                runCatching { System.load(File(bundled, "libvlccore.so.9").absolutePath) }
                // 2) JNA-Suchpfad für libvlc
                NativeLibrary.addSearchPath("vlc", bundled.absolutePath)
                // 3) Plugin-Pfad über VLC_PLUGIN_PATH (--plugin-path existiert in libVLC 3 nicht mehr)
                val pluginDir = File(bundled.parentFile, "plugins")
                if (pluginDir.exists()) setenv("VLC_PLUGIN_PATH", pluginDir.absolutePath)
            }

            val f = MediaPlayerFactory("--no-video-title-show", "--network-caching=1500")
            val mp = f.mediaPlayers().newEmbeddedMediaPlayer()
            val surface = f.videoSurfaces().newVideoSurface(videoCanvas)
            mp.videoSurface().set(surface)
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
            val libc = Native.load("c", LibC::class.java)
            libc.setenv(name, value, 1)
        }
    }

    private interface LibC : Library {
        fun setenv(name: String, value: String, overwrite: Int): Int
    }

    /** Stream/MRL abspielen. isVod = true verhindert Reconnect und feuert onVodEnded. */
    fun play(url: String, isVod: Boolean = false) {
        val mp = mediaPlayer ?: return
        currentUrl = url
        currentIsVod = isVod
        mp.media().play(url)
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
