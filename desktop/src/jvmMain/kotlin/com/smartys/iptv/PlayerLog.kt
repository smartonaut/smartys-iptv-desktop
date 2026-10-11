package com.smartys.iptv

import java.io.File
import java.time.LocalTime

/**
 * Datei-Log ~/.smartys-iptv/app.log — Diagnose-Log für die GUI-App
 * (der Daemon hat daemon.log).
 *
 * Grund: 1.5.9/1.5.10-Windows-Freezer ließen sich remote nicht
 * eingrenzen — keine Konsole, kein Log. Jetzt protokolliert der Player
 * jeden nativen Aufruf mit →/✓-Paaren; ein Freeze zwischen beiden
 * Zeilen zeigt schwarz auf weiß, welcher Call hängt.
 */
object PlayerLog {

    private val file: File =
        File(System.getProperty("user.home"), ".smartys-iptv/app.log")

    @Volatile
    private var rotateChecked = false

    fun log(msg: String) {
        runCatching {
            if (!rotateChecked) {
                rotateChecked = true
                if (file.exists() && file.length() > 2_000_000) file.delete()
            }
            file.appendText("${LocalTime.now().withNano(0)}  $msg\n")
        }
    }

    /** Aufruf-Paar: `mark("setVolume $v")` vor dem nativen Call, danach
     *  `done("setVolume")` — fehlt die done-Zeile, hängt genau dieser Call. */
    fun mark(msg: String) = log("→ $msg")
    fun done(what: String) = log("✓ $what")
}