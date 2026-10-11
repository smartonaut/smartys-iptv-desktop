package com.smartys.iptv

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Kleines Datei-Log für Aufnahme-Daemon/-Scheduler: ~/.smartys-iptv/daemon.log
 *
 * Die Aufnahme-Kette (Timer → Daemon → libVLC) lief bislang komplett stumm —
 * eine still gescheiterte Aufnahme war von außen nicht diagnosabel. Dieses Log
 * schreibt die Zustandsübergänge (Start, Tod, Neustart, Ende) mit Zeitstempel.
 * Rotation ab 1 MB nach daemon.log.old. Nie werfend: Loggen darf den
 * Aufnahme-Betrieb nie stören.
 */
object DaemonLog {
    private val file = File(System.getProperty("user.home"), ".smartys-iptv/daemon.log")
    private val old = File(System.getProperty("user.home"), ".smartys-iptv/daemon.log.old")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Synchronized
    fun log(tag: String, msg: String) {
        runCatching {
            if (file.exists() && file.length() > 1_000_000) {
                old.delete()
                file.renameTo(old)
            }
            file.appendText("${LocalDateTime.now().format(fmt)} [$tag] $msg\n")
        }
    }
}