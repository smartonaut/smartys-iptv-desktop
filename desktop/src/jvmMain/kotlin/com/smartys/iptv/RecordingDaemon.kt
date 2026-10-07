package com.smartys.iptv

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

/**
 * Headless Hintergrund-Daemon für Aufnahmen.
 *
 * Läuft als eigener Prozess (ohne GUI) und führt den RecordingScheduler aus,
 * sodass geplante Aufnahmen auch starten, wenn die App geschlossen ist.
 *
 * Start über die App oder manuell:
 *   /opt/smartysiptv/bin/SmartysIPTV --recording-daemon
 */
object RecordingDaemon {
    fun mainLoop() {
        val scheduler = RecordingScheduler()
        runBlocking {
            while (true) {
                scheduler.tick()
                delay(5_000)
            }
        }
    }
}

/** Einstiegspunkt, wenn die App mit --recording-daemon gestartet wird. */
fun runRecordingDaemon() {
    // libVLC-Init passiert lazy beim ersten Start einer Aufnahme (RecordingEngine).
    RecordingDaemon.mainLoop()
}
