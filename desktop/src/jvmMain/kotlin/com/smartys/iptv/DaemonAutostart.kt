package com.smartys.iptv

import java.io.File

/**
 * Verwaltet den Autostart des Aufnahme-Daemons. Optional — wird nur angelegt,
 * wenn der User es in den Einstellungen aktiviert.
 *
 * Plattform-Trennung (gleiches Muster: eine Datei im Autostart-Ordner):
 * - Linux: XDG-.desktop-Datei unter ~/.config/autostart/ (startet beim Login)
 * - Windows: .cmd-Datei im Startup-Ordner (%APPDATA%\...\Startup) — der Pfad
 *   inkl. Anführungszeichen steht in der .cmd, kein Registry-Quoting nötig.
 */
object DaemonAutostart {

    private val isWindows =
        System.getProperty("os.name").lowercase().contains("win")

    /** Aktiviert/deaktiviert den Autostart. */
    fun setEnabled(enabled: Boolean) {
        if (enabled) install() else remove()
    }

    fun isInstalled(): Boolean =
        if (isWindows) startupCmd().exists() else desktopFile().exists()

    /** Liefert den Pfad zum laufenden Executable (für den Autostart-Exec). */
    private fun currentExecutable(): String? =
        ProcessHandle.current().info().command().orElse(null)

    private fun install() {
        if (isWindows) winInstall() else linuxInstall()
    }

    private fun remove() {
        if (isWindows) startupCmd().delete() else desktopFile().delete()
    }

    // ---------------- Windows: Startup-Ordner ----------------

    /** %APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup */
    private fun winStartupDir(): File? {
        val appData = System.getenv("APPDATA") ?: return null
        return File(appData, "Microsoft/Windows/Start Menu/Programs/Startup")
    }

    private fun startupCmd(): File = File(
        winStartupDir()
            // Fallback, falls APPDATA fehlt: Standardpfad von Hand bauen
            ?: File(System.getProperty("user.home"), "AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Startup"),
        "smartys-recording-daemon.cmd"
    )

    private fun winInstall() {
        val exe = currentExecutable() ?: return
        val d = winStartupDir() ?: return
        if (!d.exists()) d.mkdirs()
        // Anführungszeichen um den Pfad — sicher bei Leerzeichen
        // (C:\Program Files\...). cmd.exe wertet die Zeile beim Login aus.
        startupCmd().writeText("\"$exe\" --recording-daemon\r\n")
    }

    // ---------------- Linux: XDG .desktop ----------------

    private fun autostartDir(): File = File(System.getProperty("user.home"), ".config/autostart")
    private fun desktopFile(): File = File(autostartDir(), "smartys-recording-daemon.desktop")

    private fun linuxInstall() {
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
}