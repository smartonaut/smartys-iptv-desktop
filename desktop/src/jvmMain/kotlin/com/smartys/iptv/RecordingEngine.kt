package com.smartys.iptv

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dauerhafte Stream-Aufnahme (kein flüchtiger Timeshift-Puffer).
 *
 * Nutzt einen headless libVLC-MediaPlayer (kein Video-Fenster) mit einer
 * `:sout=#standard{access=file,mux=ts,dst=...}`-Option — der Stream wird direkt
 * in eine Datei geschrieben, die nach der Aufnahme bestehen bleibt.
 *
 * start() ist ehrlich: libVLC play() ist asynchron, deshalb wird bis zu 15 s
 * auf das echte playing-Event gewartet. Ein Stream, der nicht öffnet (403/
 * Verbindungsfehler), liefert false — kein Schein-Erfolg mit 0-Byte-Datei.
 * Stirbt der Stream später, geht `recording` auf false; der Scheduler
 * startet die Aufnahme dann neu (Anhängen an die Teil-Datei).
 */
class RecordingEngine {

    private var factory: MediaPlayerFactory? = null
    private var player: MediaPlayer? = null

    /** Todesursache der letzten Engine ("Stream-Fehler…"/"Stream-Ende") — fürs Log. */
    @Volatile
    var deathReason: String? = null
        private set

    @Volatile
    var recording = false
        private set

    companion object {
        private val LOCK = File(System.getProperty("user.home"), ".smartys-iptv/recording.lock")

        /** true, wenn bereits ein anderer Prozess aufnimmt (Lock frisch, < 30s alt). */
        fun isLocked(): Boolean {
            if (!LOCK.exists()) return false
            return System.currentTimeMillis() - LOCK.lastModified() < 30_000
        }

        /** Lock-Herzschlag erneuern (vom Scheduler-Loop, solange wirklich aufgezeichnet wird). */
        fun touchLock() {
            runCatching { LOCK.writeText(System.currentTimeMillis().toString()) }
        }

        private fun clearLock() {
            runCatching { LOCK.delete() }
        }

        fun clear() = clearLock()
    }

    /**
     * Startet die Aufnahme und wartet bis zu 15 s auf den echten Stream-Start
     * (playing-Event). Liefert true erst, wenn Daten fließen.
     *
     * `append=true` hängt an eine bestehende Datei an (`file{append}`) — für den
     * Neustart einer abgebrochenen Aufnahme, statt die Teil-Datei zu verwerfen.
     */
    fun start(url: String, outputFile: File, append: Boolean = false): Boolean {
        if (recording) return false
        // Doppel-Daemon-Schutz: kein zweiter Prozess, wenn ein frischer Lock
        // (< 30s) bereits von einer anderen Instanz (Autostart vs. App) gesetzt
        // wurde — sonst nimmt derselbe Timer doppelt auf.
        if (isLocked()) {
            DaemonLog.log("rec", "Start verweigert: Lock einer anderen Instanz ist frisch")
            return false
        }
        touchLock()
        deathReason = null

        var f: MediaPlayerFactory? = null
        var mp: MediaPlayer? = null
        try {
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

            // Anhängen nur, wenn die Teil-Datei wirklich schon Daten enthält.
            val useAppend = append && outputFile.exists() && outputFile.length() > 0
            val access = if (useAppend) "file{append}" else "file"

            f = MediaPlayerFactory(
                "--no-video-title-show",
                "--network-caching=1500",
                // Browser-User-Agent: viele Xtream-Panels blocken Requests ohne
                // Browser-UA — gleicher UA wie VlcPlayer/Box (exteplayer3).
                "--http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
            )
            mp = f.mediaPlayers().newMediaPlayer()

            val started = AtomicBoolean(false)
            val settled = CountDownLatch(1)
            mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    started.set(true)
                    settled.countDown()
                }

                // Fehler/Ende: NUR Flags setzen — KEIN release() aus dem nativen
                // Event-Thread (kann in vlcj deadlocken). Aufräumen macht stop()
                // vom Scheduler-/App-Thread; der Scheduler sieht den Tod über
                // `recording == false` und startet neu.
                override fun error(mediaPlayer: MediaPlayer) {
                    deathReason = "Stream-Fehler (verweigert oder Verbindung)"
                    settled.countDown()
                    recording = false
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    deathReason = "Stream-Ende"
                    settled.countDown()
                    recording = false
                }
            })

            factory = f
            player = mp
            recording = true

            val sout = ":sout=#standard{access=$access,mux=ts,dst=${outputFile.absolutePath}}"
            mp.media().play(url, sout, ":no-sout-all", ":sout-keep")

            val playing = settled.await(15, TimeUnit.SECONDS) && started.get()
            if (!playing) {
                DaemonLog.log("rec", "Stream öffnete nicht binnen 15 s → Abbruch: $url")
                stopInternal()
                return false
            }
            DaemonLog.log(
                "rec",
                "Aufnahme läuft → ${outputFile.name}${if (useAppend) " (append)" else ""}"
            )
            return true
        } catch (t: Throwable) {
            DaemonLog.log("rec", "Start fehlgeschlagen: ${t.message}")
            recording = false
            runCatching { mp?.controls()?.stop() }
            runCatching { mp?.release() }
            runCatching { f?.release() }
            player = null
            factory = null
            clearLock()
            return false
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
        RecordingEngine.clear()
    }

    fun release() {
        stopInternal()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}