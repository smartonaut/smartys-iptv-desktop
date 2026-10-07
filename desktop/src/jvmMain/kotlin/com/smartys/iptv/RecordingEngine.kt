package com.smartys.iptv

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File

/**
 * Dauerhafte Stream-Aufnahme (kein flüchtiger Timeshift-Puffer).
 *
 * Nutzt einen headless libVLC-MediaPlayer (kein Video-Fenster) mit einer
 * `:sout=#standard{access=file,mux=ts,dst=...}`-Option — der Stream wird direkt
 * in eine Datei geschrieben, die nach der Aufnahme bestehen bleibt.
 */
class RecordingEngine {

    private var factory: MediaPlayerFactory? = null
    private var player: MediaPlayer? = null

    @Volatile
    var recording = false
        private set

    var onFinished: (() -> Unit)? = null

    /**
     * Initialisiert libVLC (headless) und startet die Aufnahme.
     * Liefert true, wenn die Aufnahme gestartet wurde.
     */
    fun start(url: String, outputFile: File): Boolean {
        if (recording) return false
        return runCatching {
            outputFile.parentFile?.mkdirs()

            // 1) libVLC-Bundle laden (wie VlcPlayer)
            val bundled = VlcPlayer.ensureLibVlc()
            if (bundled != null) {
                val core = File(bundled, if (isWindows()) "libvlccore.dll" else "libvlccore.so.9")
                runCatching { System.load(core.absolutePath) }
                NativeLibrary.addSearchPath(VlcPlayer.libName(), bundled.absolutePath)
                val plugins = VlcPlayer.pluginDir(bundled)
                if (plugins.exists()) VlcPlayer.setEnv("VLC_PLUGIN_PATH", plugins.absolutePath)
            }

            val f = MediaPlayerFactory()
            val mp = f.mediaPlayers().newMediaPlayer()
            mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) {
                    stopInternal()
                    onFinished?.invoke()
                }
                override fun error(mediaPlayer: MediaPlayer) {
                    stopInternal()
                    onFinished?.invoke()
                }
            })

            factory = f
            player = mp
            recording = true

            val sout = ":sout=#standard{access=file,mux=ts,dst=${outputFile.absolutePath}}"
            mp.media().play(url, sout, ":no-sout-all", ":sout-keep")
            true
        }.getOrElse {
            recording = false
            false
        }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        runCatching { player?.controls()?.stop() }
        runCatching { player?.release() }
        runCatching { factory?.release() }
        player = null
        factory = null
        recording = false
    }

    fun release() {
        stopInternal()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}
