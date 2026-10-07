package com.smartys.iptv

import java.io.File

/**
 * Verwaltet den Autostart des Aufnahme-Daemons über eine XDG-.desktop-Datei
 * unter ~/.config/autostart/ (startet den Daemon beim Login). Optional —
 * wird nur angelegt, wenn der User es in den Einstellungen aktiviert.
 */
object DaemonAutostart {

    private fun autostartDir(): File = File(System.getProperty("user.home"), ".config/autostart")
    private fun desktopFile(): File = File(autostartDir(), "smartys-recording-daemon.desktop")

    /** Liefert den Pfad zum laufenden Executable (für den Autostart-Exec). */
    private fun currentExecutable(): String? =
        ProcessHandle.current().info().command().orElse(null)

    /** Aktiviert/deaktiviert den Autostart. */
    fun setEnabled(enabled: Boolean) {
        if (enabled) install() else remove()
    }

    private fun install() {
        val exe = currentExecutable() ?: return
        val d = autostartDir()
        if (!d.exists()) d.mkdirs()
        desktopFile().writeText(
            "[Desktop Entry]\n" +
            "Type=Application\n" +
            "Name=Smartys Aufnahme-Daemon\n" +
            "Comment=Startet geplante Aufnahmen auch ohne die Player-App\n" +
            "Exec=$exe --recording-daemon\n" +
            "Terminal=false\n" +
            "X-GNOME-Autostart-enabled=true\n"
        )
    }

    private fun remove() {
        desktopFile().delete()
    }

    fun isInstalled(): Boolean = desktopFile().exists()
}
