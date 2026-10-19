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
        // OSD-Overlay (PNG) braucht den "logo"-Video-Filter in der Kette.
        val osdOpt = ":video-filter=logo"
        if (timeshift) {
            val opts = mutableListOf(":input-timeshift-access=file", osdOpt)
            timeshiftDir?.takeIf { it.isNotBlank() }?.let {
                opts.add(":input-timeshift-dir=$it")
            }
            mp.media().play(url, *opts.toTypedArray())
        } else if (startPosMs > 0) {
            // Resume: VLC öffnet den Titel DIREKT ab dieser Position — ein
            // seekTo() direkt nach dem asynchronen play() verpufft, weil die
            // Media zu dem Zeitpunkt noch nicht geladen ist.
            mp.media().play(url, ":start-time=${startPosMs / 1000.0}", osdOpt)
        } else {
            mp.media().play(url, osdOpt)
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
     * OSD-Karte im Android-Stil: dunkle Karte unten links mit Senderlogo,
     * Titel, EPG-Zeile, Fortschrittsbalken und optionaler "Danach"-Zeile.
     *
     * WARUM KEIN VLC-FILTER: libVLC 3 hat das Marquee-Modul von "marquee" auf
     * "marq" umbenannt; die vlcj-/libvlc-Aufrufe (libvlc_video_set_marquee_*)
     * setzen weiterhin "marquee-*"-Variablen und laufen damit still ins Leere
     * (kein Fehler, einfach kein OSD). Das Logo-Filter-API ist genauso
     * unzuverlaessig. Deshalb zeichnet die App die Karte selbst — plattform-
     * unabhaengig, mit Logo, echten Fonts und echtem Balken.
     */
    data class OsdCard(
        val title: String,
        val sub: String = "",
        val extra: String? = null,
        val barPercent: Float? = null,
        val logoPath: String? = null,
    )

    override fun showOsd(text: String, timeoutMs: Int,
                position: MarqueePosition,
                color: java.awt.Color) {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        showCard(
            OsdCard(
                title = lines.getOrNull(0) ?: "",
                sub = lines.getOrNull(1) ?: "",
                extra = lines.drop(2).joinToString("   ").ifBlank { null },
            ),
            timeoutMs
        )
    }

    /** Karte anzeigen (unten links ueber dem Video). timeoutMs = 0: nie ausblenden. */
    fun showCard(card: OsdCard, timeoutMs: Int = 5000) {
        // 1. Wahl: PNG-Overlay über VLCs Bild-Filter — echtes Alpha, wird IN den
        // Frame geblendet (kein Fenster-Trick, skaliert mit Vollbild mit).
        if (showCardOverlay(card, timeoutMs)) return
        // Fallback: AWT-Fenster (opak, falls der Filter nicht greift).
        SwingUtilities.invokeLater {
            runCatching {
                val win = ensureOsdWindow() ?: return@runCatching
                val panel = osdPanel ?: return@runCatching
                panel.logo = loadLogoImage(card.logoPath ?: logoPath)
                panel.card = card
                panel.revalidate()

                // Position erst setzen, wenn der Video-Canvas wirklich laid out
                // ist. Beim Start ist er noch unsichtbar/1px — sonst landet die
                // Karte oben links ueber der Tab-Leiste. Bis dahin: Retry-Timer.
                val placed = positionOsd()
                if (perPixelAlpha) runCatching { win.background = java.awt.Color(0, 0, 0, 0) }
                win.isVisible = placed
                if (!placed) {
                    osdPlaceTimer?.stop()
                    osdPlaceAttempts = 0
                    osdPlaceTimer = Timer(150) {
                        osdPlaceAttempts++
                        if (positionOsd()) {
                            win.isVisible = true
                            osdPlaceTimer?.stop()
                        } else if (osdPlaceAttempts >= 20) {
                            osdPlaceTimer?.stop()   // Canvas kam nie -> aufgeben
                        }
                    }.apply { isRepeats = true; start() }
                }
                if (isWindows) makeClickThrough(win)
                if (timeoutMs > 0) {
                    osdHide?.stop()
                    osdHide = Timer(timeoutMs) { win.isVisible = false }
                        .apply { isRepeats = false; start() }
                }
                PlayerLog.log("OSD card: '${card.title.take(24)}' ${panel.preferredSize.width}x${panel.preferredSize.height} alpha=$perPixelAlpha winAlpha=${runCatching { win.background?.alpha ?: -1 }.getOrDefault(-1)} placed=$placed")
            }.onFailure {
                PlayerLog.log("OSD card FEHLER: ${it.javaClass.simpleName} ${it.message}")
            }
        }
    }

    private var osdPlaceTimer: Timer? = null
    private var osdPlaceAttempts = 0

    // ---------- OSD als PNG über den VLC-Bild-Filter (echtes Alpha) ----------

    private var osdOverlayDir: File? = null
    private var osdOverlaySeq = 0
    private var osdOverlayTimer: Timer? = null

    private fun overlayDir(): File {
        osdOverlayDir?.let { return it }
        val d = File(System.getProperty("user.home"), ".smartys-iptv/osd")
        runCatching { d.mkdirs() }
        runCatching { d.listFiles()?.forEach { f -> f.delete() } }
        osdOverlayDir = d
        return d
    }

    /**
     * Karte als transparentes PNG rendern. Die Ränder (26 px links/unten) sind
     * transparent und sorgen dafür, dass die Karte mit Abstand in der linken
     * unteren Ecke des Bildes sitzt (Logo-Filter ankert unten links).
     */
    private fun renderCardPng(card: OsdCard, logo: java.awt.image.BufferedImage?): File? {
        return runCatching {
            val panel = OsdCardPanel(alpha = true)
            panel.isOpaque = false
            panel.card = card
            panel.logo = logo
            val pref = panel.preferredSize
            val margin = 26
            val img = java.awt.image.BufferedImage(
                pref.width + margin, pref.height + margin, java.awt.image.BufferedImage.TYPE_INT_ARGB
            )
            val g = img.createGraphics()
            try {
                panel.setSize(pref)
                g.translate(margin, 0)
                panel.paint(g)
            } finally {
                g.dispose()
            }
            // Zwei wechselnde Dateinamen: VLC cacht das Bild pro Dateiname —
            // derselbe Name wuerde die alte Karte zeigen.
            val f = File(overlayDir(), "card_${osdOverlaySeq++ % 2}.png")
            javax.imageio.ImageIO.write(img, "png", f)
            f
        }.onFailure { PlayerLog.log("OSD-PNG FEHLER: ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull()
    }

    /** Karte über den "logo"-Video-Filter einblenden. false = Filter/API nicht verfuegbar. */
    private fun showCardOverlay(card: OsdCard, timeoutMs: Int): Boolean {
        val mp = mediaPlayer ?: return false
        val png = renderCardPng(card, loadLogoImage(card.logoPath ?: logoPath)) ?: return false
        val ok = runCatching {
            mp.logo().setFile(png.absolutePath)
            mp.logo().setPosition(uk.co.caprica.vlcj.player.base.LogoPosition.BOTTOM_LEFT)
            mp.logo().setOpacity(1.0f)
            mp.logo().enable(true)
            true
        }.getOrElse {
            PlayerLog.log("OSD-Overlay FEHLER: ${it.javaClass.simpleName} ${it.message}")
            false
        }
        if (!ok) return false
        PlayerLog.log("OSD overlay: ${png.name} ${png.length()}B")
        if (timeoutMs > 0) {
            osdOverlayTimer?.stop()
            osdOverlayTimer = Timer(timeoutMs) {
                runCatching { mp.logo().enable(false) }
            }.apply { isRepeats = false; start() }
        }
        return true
    }

    /** Karte unten links positionieren. false = Canvas noch nicht bereit. */
    private fun positionOsd(): Boolean {
        val canvas = videoCanvas ?: return false
        val win = osdWin ?: return false
        val panel = osdPanel ?: return false
        if (!canvas.isShowing || canvas.width < 120 || canvas.height < 90) return false
        val pref = panel.preferredSize
        val loc = runCatching { canvas.locationOnScreen }.getOrNull() ?: return false

        // Sichtbaren Bildbereich bestimmen (Letterbox): libVLC kennt die
        // Videogroesse. Sitzt die Karte im schwarzen Balken, sieht man weder
        // Transparenz noch Eckenrundung — deshalb INS Bild legen.
        val cw = canvas.width
        val ch = canvas.height
        val dim = runCatching { mediaPlayer?.video()?.videoDimension() }.getOrNull()
        val dimValid = dim != null && dim.width > 0 && dim.height > 0
        var picW = cw
        var picH = ch
        if (dimValid) {
            val scale = minOf(cw.toDouble() / dim!!.width, ch.toDouble() / dim.height)
            picW = (dim.width * scale).toInt()
            picH = (dim.height * scale).toInt()
        } else {
            // libVLC liefert (noch) keine Videogroesse: typische Letterbox
            // annehmen (Bild ~80 % der Canvas-Hoehe, oben/unten je 10 %).
            picW = cw
            picH = (ch * 0.80).toInt()
        }
        val picX = loc.x + (cw - picW) / 2
        val picY = loc.y + (ch - picH) / 2
        val x = (picX + 26).coerceAtMost(loc.x + cw - pref.width - 8)
        val y = (picY + picH - pref.height - 26).coerceAtLeast(picY + 8)
        win.setBounds(x, y, pref.width, pref.height)
        // Diagnose: nur bei geaenderter Geometrie loggen (sonst spammt jede
        // Mausbewegung das Log). Zeigt, ob videoDimension() wirklich greift.
        val info = "canvas=${cw}x$ch video=${dim?.width}x${dim?.height} pic=${picW}x$picH y=$y"
        if (info != lastPlaceLog) {
            lastPlaceLog = info
            PlayerLog.log("OSD place: $info")
        }
        return true
    }

    private var lastPlaceLog: String? = null

    /** Bei Fenster-Resize/Move (Vollbild-Wechsel) die sichtbare Karte nachziehen. */
    private var osdCanvasListener: java.awt.event.ComponentListener? = null

    /** Senderlogo fuer die Karte (gecacht, wird pro Kanal neu geladen). */
    private var logoPath: String? = null
    private var logoPathCached: String? = null
    private var logoImgCached: java.awt.image.BufferedImage? = null

    private fun loadLogoImage(path: String?): java.awt.image.BufferedImage? {
        if (path.isNullOrBlank()) return null
        if (path == logoPathCached) return logoImgCached
        val img = runCatching { javax.imageio.ImageIO.read(File(path)) }.getOrNull()
        logoPathCached = path
        logoImgCached = img
        return img
    }

    /** Per-Pixel-Alpha (Wayland/XWayland oft nicht) — entscheidet nur, ob die
     *  Karte transparente Ecken bekommt oder deckend dunkel gezeichnet wird. */
    private val perPixelAlpha: Boolean by lazy {
        runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .isWindowTranslucencySupported(
                    java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        }.getOrDefault(false)
    }

    /**
     * Selbstgezeichnete OSD-Karte. Bewusst ohne JLabel/HTML: gerundete Karte,
     * Logo links, Fettschrift fuer den Titel, duenner Balken in Akzentfarbe.
     */
    private class OsdCardPanel(private val alpha: Boolean) : javax.swing.JComponent() {

        var card: OsdCard? = null
        var logo: java.awt.image.BufferedImage? = null

        private val padX = 20
        private val padY = 15
        private val logoBoxW = 96
        private val logoBoxH = 40
        private val gap = 14
        private val barH = 5

        private val fTitle = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 19)
        private val fSub = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 14)
        private val fExtra = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13)

        private fun w(s: String, f: java.awt.Font): Int = getFontMetrics(f).stringWidth(s)

        /** Breite des Textblocks — Balken laeuft ueber genau diese Breite. */
        private fun contentWidth(c: OsdCard): Int = maxOf(
            w(c.title, fTitle),
            if (c.sub.isNotBlank()) w(c.sub, fSub) else 0,
            if (!c.extra.isNullOrBlank()) w(c.extra, fExtra) else 0,
            240
        )

        override fun getPreferredSize(): java.awt.Dimension {
            val c = card ?: return java.awt.Dimension(20, 20)
            val wText = contentWidth(c)
            var h = padY * 2 + getFontMetrics(fTitle).height
            if (c.sub.isNotBlank()) h += 3 + getFontMetrics(fSub).height
            if (c.barPercent != null) h += 9 + barH
            if (!c.extra.isNullOrBlank()) h += 7 + getFontMetrics(fExtra).height
            h = maxOf(h, padY * 2 + (if (logo != null) logoBoxH else 0))
            val logoW = if (logo != null) logoBoxW + gap else 0
            return java.awt.Dimension(padX * 2 + logoW + wText, h)
        }

        override fun paintComponent(gj: java.awt.Graphics) {
            val c = card ?: return
            val g = gj.create() as java.awt.Graphics2D
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val ww = width.toFloat()
                val hh = height.toFloat()
                // 1px Innenabstand: sonst schneidet der Fensterrand die
                // antialiased Rundung an -> ausgefranste/abgeschnittene Ecken.
                val inset = 1f
                val rw = ww - inset * 2f - 1f
                val rh = hh - inset * 2f - 1f
                val radius = 14f
                g.color = if (alpha) java.awt.Color(0x0B, 0x13, 0x22, 180)
                          else java.awt.Color(0x0B, 0x13, 0x22)
                g.fillRoundRect(inset.toInt(), inset.toInt(), rw.toInt(), rh.toInt(),
                    radius.toInt(), radius.toInt())
                g.color = java.awt.Color(255, 255, 255, if (alpha) 26 else 34)
                g.stroke = java.awt.BasicStroke(1f)
                g.drawRoundRect(inset.toInt(), inset.toInt(), rw.toInt(), rh.toInt(),
                    radius.toInt(), radius.toInt())

                var x = padX
                logo?.let { img ->
                    // Logo vollständig in die Box einpassen (Quell-Icons sind oft
                    // nur 100x13 px — sie sollen lesbar sein, nicht winzig).
                    val scale = minOf(logoBoxW.toFloat() / img.width, logoBoxH.toFloat() / img.height)
                    val lw = (img.width * scale).toInt().coerceAtLeast(1)
                    val lh = (img.height * scale).toInt().coerceAtLeast(1)
                    g.drawImage(img, x + (logoBoxW - lw) / 2, padY + (logoBoxH - lh) / 2, lw, lh, null)
                    x += logoBoxW + gap
                }

                // Text mit 1px-Schatten: lesbar auch über hellen Bildstellen
                // (Karte ist halbtransparent — Kontrast kommt aus dem Schatten).
                var y = padY + getFontMetrics(fTitle).ascent
                g.font = fTitle
                g.color = java.awt.Color(0, 0, 0, 170)
                g.drawString(c.title, x, y + 1)
                g.color = java.awt.Color(0xEE, 0xF3, 0xFF)
                g.drawString(c.title, x, y)
                if (c.sub.isNotBlank()) {
                    y += 3 + getFontMetrics(fSub).height
                    g.font = fSub
                    g.color = java.awt.Color(0, 0, 0, 170)
                    g.drawString(c.sub, x, y + 1)
                    g.color = java.awt.Color(0xDC, 0xE6, 0xF6)
                    g.drawString(c.sub, x, y)
                }
                if (c.barPercent != null) {
                    y += 9
                    val bw = contentWidth(c)
                    g.color = java.awt.Color(255, 255, 255, 70)
                    g.fillRoundRect(x, y, bw, barH, barH, barH)
                    val fw = (bw * c.barPercent.coerceIn(0f, 1f)).toInt()
                    if (fw > 0) {
                        g.color = java.awt.Color(0xFF, 0xC9, 0x4D)
                        g.fillRoundRect(x, y, fw, barH, barH, barH)
                    }
                    y += barH
                }
                if (!c.extra.isNullOrBlank()) {
                    y += 7 + getFontMetrics(fExtra).height
                    g.font = fExtra
                    g.color = java.awt.Color(0, 0, 0, 170)
                    g.drawString(c.extra, x, y + 1)
                    g.color = java.awt.Color(0xC8, 0xD5, 0xE9)
                    g.drawString(c.extra, x, y)
                }
            } finally {
                g.dispose()
            }
        }
    }

    // ---------------- OSD-Fenster (selbstgezeichnete Karte) ----------------

    private var osdWin: JWindow? = null
    private var osdPanel: OsdCardPanel? = null
    private var osdHide: Timer? = null

    /** user32 für WS_EX_TRANSPARENT (OSD + VLC-Video-Kindfenster). */
    private interface User32Lib : Library {
        fun GetWindowLongW(hwnd: Long, index: Int): Int
        fun SetWindowLongW(hwnd: Long, index: Int, style: Int): Int
        fun EnumChildWindows(parent: Long, cb: WndEnumProc, lParam: Long): Boolean
        fun GetClassNameW(hwnd: Long, buf: CharArray, max: Int): Int
        fun EnableWindow(hwnd: Long, enable: Boolean): Boolean
    }

    private interface WndEnumProc : com.sun.jna.Callback {
        fun callback(hwnd: Long, lParam: Long): Boolean
    }

    /**
     * Einmalig das OSD-Fenster erzeugen (Owner = Fenster des Canvas, damit es
     * im Z-Stack ueber dem Video bleibt). Die Karte zeichnet sich selbst; ist
     * Per-Pixel-Alpha verfuegbar (X11), bekommen die Ecken Transparenz, sonst
     * wird sie deckend dunkel gezeichnet (Wayland/XWayland).
     */
    private fun ensureOsdWindow(): JWindow? {
        osdWin?.let { return it }
        var owner: java.awt.Container? = videoCanvas?.parent
        while (owner?.parent != null) owner = owner.parent
        if (owner !is java.awt.Window) return null
        val w = JWindow(owner)
        // WICHTIG: Fenster-Transparenz MUSS vor dem Realisieren gesetzt werden.
        // Wird sie danach gesetzt, bleibt das Fenster opak — dann sind die Ecken
        // eckig (Rundung verschwindet im Fensterhintergrund) und das Video
        // scheint nicht durch, obwohl isWindowTranslucencySupported() true sagt.
        if (perPixelAlpha) runCatching { w.background = java.awt.Color(0, 0, 0, 0) }
        runCatching { w.addNotify() }
        if (perPixelAlpha) runCatching { w.background = java.awt.Color(0, 0, 0, 0) }
        val panel = OsdCardPanel(perPixelAlpha)
        panel.isOpaque = false
        (w.contentPane as? javax.swing.JComponent)?.let {
            it.isOpaque = false
            runCatching { it.background = java.awt.Color(0, 0, 0, 0) }
        }
        w.setLayout(java.awt.BorderLayout())
        w.add(panel, java.awt.BorderLayout.CENTER)
        w.setFocusableWindowState(false)
        // 1.5.18: nie Fokus klauen — sonst bricht AWT-Vollbild sofort ab
        w.setAutoRequestFocus(false)
        w.isVisible = false
        // Vollbild-/Resize-Wechsel: sichtbare Karte mitziehen
        videoCanvas?.let { c ->
            if (osdCanvasListener == null) {
                val l = object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent?) {
                        if (osdWin?.isVisible == true) positionOsd()
                    }
                    override fun componentMoved(e: java.awt.event.ComponentEvent?) {
                        if (osdWin?.isVisible == true) positionOsd()
                    }
                }
                c.addComponentListener(l)
                osdCanvasListener = l
            }
        }
        osdWin = w
        osdPanel = panel
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
                        // 1.5.17: WS_EX_TRANSPARENT ALLEINE hat keine Hit-Test-Wirkung
                        // (1.5.16: Schalter gesetzt, Klicks kamen trotzdem nicht an).
                        // Dokumentiertes Klick-Durchlass-Rezept: LAYERED | TRANSPARENT.
                        // Zusaetzlich EnableWindow(FALSE): deaktivierte Fenster empfangen
                        // ueberhaupt keine Maus-Eingabe (ohne Rendering-Nebenwirkung).
                        val ex = u32.GetWindowLongW(hwnd, -20) // GWL_EXSTYLE
                        u32.SetWindowLongW(hwnd, -20, ex or 0x20 or 0x80000) // +WS_EX_TRANSPARENT +WS_EX_LAYERED
                        u32.EnableWindow(hwnd, false)
                        PlayerLog.log("VLC-Fenster neutralisiert: '$cls' (hwnd=$hwnd ex=0x${u32.GetWindowLongW(hwnd, -20).toString(16)})")
                        applied[0]++
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

    /**
     * Senderlogo fuer die OSD-Karte merken. Der VLC-Logofilter wird NICHT mehr
     * benutzt: unter VLC 3 unzuverlaessig (eigene API-Variablennamen) und er
     * wuerde das Logo doppelt zeichnen, weil es jetzt in der Karte steckt.
     */
    fun setLogo(path: String?) {
        logoPath = path
        // Cache verwerfen, damit das neue Logo wirklich geladen wird.
        logoPathCached = null
        logoImgCached = null
    }

    /** API-Paritaet: Logo steckt jetzt in der OSD-Karte (showCard). */
    @Suppress("UNUSED_PARAMETER")
    fun showLogo(v: Boolean) { /* bewusst ohne Wirkung */ }

    /** Relatives Spulen (±ms) — für Timeshift im Archiv-Stream. */
    fun seekBy(deltaMs: Long) {
        runCatching { mediaPlayer?.controls()?.skipTime(deltaMs) }
    }

    fun hideOsd() {
        osdOverlayTimer?.stop()
        runCatching { mediaPlayer?.logo()?.enable(false) }
        SwingUtilities.invokeLater {
            osdHide?.stop()
            osdWin?.isVisible = false
        }
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
        SwingUtilities.invokeLater {
            osdHide?.stop()
            osdWin?.dispose()
            osdWin = null
            osdPanel = null
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
