package com.smartys.iptv

import uk.co.caprica.vlcj.player.base.MarqueePosition
import java.awt.Canvas
import java.awt.image.BufferedImage

/**
 * Gemeinsame Wiedergabe-API für den VOD-Player.
 *
 * Plattform-Trennung (User-Direktive: Windows und Linux sind grundverschiedene
 * Systeme): Windows nutzt MpvPlayer (rendert zuverlässig in den Canvas), Linux
 * nutzt VlcPlayer — gleiche Engine wie Live-TV (schnellster Start, identischer
 * UA-/Decoding-Pfad, kein Weiß-Flash dank videoOutput-Event). Der Screen selbst
 * ist identisch und arbeitet nur gegen dieses Interface.
 */
interface VodPlayerApi {
    /** Video-Ziel (muss vor init()/play() gesetzt sein). */
    var videoCanvas: Canvas?

    /** Wird beim natürlichen Ende eines VOD-Titels aufgerufen. */
    var onVodEnded: (() -> Unit)?

    /** Eine wählbare Tonspur oder Untertitelspur. */
    data class Spur(val id: Int, val name: String)

    /** Bildanpassung, wie in der iOS-App. */
    enum class Bildmodus { ORIGINAL, FUELLEN, RATIO_16_9, RATIO_4_3 }

    fun init(): Boolean
    fun play(url: String, isVod: Boolean = false, timeshift: Boolean = false, timeshiftDir: String? = null, startPosMs: Long = 0)
    fun pause()
    fun resume()
    fun stop()
    fun release()
    fun seekTo(posMs: Long)
    fun currentPositionMs(): Long
    fun durationMs(): Long
    fun setVolume(v: Int)
    fun setMuted(muted: Boolean)
    fun setBrightness(b: Float)
    fun snapshot(): BufferedImage?

    /** Verfügbare Tonspuren (leer, wenn die Datei nur eine hat). */
    fun tonspuren(): List<Spur> = emptyList()

    /** Verfügbare Untertitelspuren (eingebettet). */
    fun untertitelspuren(): List<Spur> = emptyList()

    fun waehleTonspur(id: Int) {}
    fun waehleUntertitel(id: Int) {}

    /** Bildanpassung setzen (Zuschneiden/Strecken). */
    fun setzeBildmodus(modus: Bildmodus) {}

    /** Text-OSD direkt ins Videobild (VLC-Marquee / mpv show-text). */
    fun showOsd(
        text: String,
        timeoutMs: Int = 0,
        position: MarqueePosition = MarqueePosition.BOTTOM,
        color: java.awt.Color = java.awt.Color.WHITE
    )
}