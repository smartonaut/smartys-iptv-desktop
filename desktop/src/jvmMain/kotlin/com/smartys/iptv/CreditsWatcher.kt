package com.smartys.iptv

import kotlinx.coroutines.delay
import java.awt.image.BufferedImage

/**
 * Erkennt den Beginn eines Abspanns (Credits) durch Frame-Analyse am Ende
 * eines VOD-Titels — Desktop-Variante (BufferedImage via vlcj-Snapshot).
 * Gleiche Schwellen wie die Android-App.
 */
class CreditsWatcher(
    private val player: MpvPlayer,
    private val onCreditsDetected: () -> Unit
) {
    private companion object {
        const val SAMPLE_INTERVAL_MS = 2500L
        const val DARK_THRESHOLD = 0.16f
        const val QUIET_THRESHOLD = 0.12f
        const val REQUIRED_STREAK = 2
        const val ACTIVE_WINDOW_MS = 180_000L
    }

    @Volatile private var stopped = false
    private var fired = false
    private var streak = 0
    private var prev: BufferedImage? = null

    suspend fun run() {
        while (!stopped) {
            delay(SAMPLE_INTERVAL_MS)
            if (stopped || fired) return
            val duration = player.durationMs()
            if (duration <= 0L) continue
            val remaining = duration - player.currentPositionMs()
            if (remaining > ACTIVE_WINDOW_MS) continue

            val bmp = player.snapshot() ?: continue
            val (brightness, motion) = analyze(bmp, prev)
            prev = bmp

            if (brightness < DARK_THRESHOLD && motion < QUIET_THRESHOLD) {
                streak++
                if (streak >= REQUIRED_STREAK && !fired) {
                    fired = true
                    onCreditsDetected()
                    return
                }
            } else {
                streak = 0
            }
        }
    }

    fun stop() {
        stopped = true
        prev = null
    }

    private fun analyze(bmp: BufferedImage, prevBmp: BufferedImage?): Pair<Float, Float> {
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        val px = IntArray(n)
        bmp.getRGB(0, 0, w, h, px, 0, w)

        var lumSum = 0.0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lumSum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val brightness = (lumSum / (n * 255.0)).toFloat()

        if (prevBmp == null || prevBmp.width != w || prevBmp.height != h) {
            return brightness to 1f
        }
        val pp = IntArray(n)
        prevBmp.getRGB(0, 0, w, h, pp, 0, w)

        var diffSum = 0.0
        for (i in 0 until n) {
            val a = px[i]; val b = pp[i]
            val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
            val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
            val db = (a and 0xFF) - (b and 0xFF)
            diffSum += kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
        }
        val motion = (diffSum / (n * 3 * 255.0)).toFloat()
        return brightness to motion
    }
}
