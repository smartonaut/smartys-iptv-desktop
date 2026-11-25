package com.smartys.iptv

import java.io.File
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Stabile Player-Identitaet nach PLAYER-IDENTITY.md (v1).
 *
 * Hintergrund: Die Panel-Line wird als AES-256-GCM-Ciphertext gespeichert und
 * die AAD bindet an die Player-ID ("smartys-iptv-sec:<ID>", siehe SecCrypto).
 * Verliert ein Geraet seine ID, ist die zugewiesene Line unwiederbringlich
 * weg. Deshalb werden NEUE IDs deterministisch aus geraetestabilen Merkmalen
 * abgeleitet, damit dieselbe Hardware nach Neuinstallation wieder dieselbe ID
 * ergibt. Eine bereits gespeicherte ID wird NIE geaendert — das regelt CredStore
 * (Aufl�sungsreihenfolge: config.json -> Identitaets-Spiegel -> Seed -> Zufall).
 */

/** Vertrags-Namespace aus PLAYER-IDENTITY.md — NIE aendern, sonst verschieben sich ALLE neu vergebenen IDs. */
private const val PID_NAMESPACE = "smartys-iptv-player-id-v1"

/**
 * Reine Funktion ohne Seiteneffekte: leitet aus einem Seed die Player-ID ab.
 *
 *   digest = SHA-256( "smartys-iptv-player-id-v1|" + seed )
 *   b      = digest[0..5]                          // 6 Bytes
 *   b[0]   = (b[0] & 0xFC) | 0x02                  // lokal administriert + unicast
 *   id     = b als "XX:XX:XX:XX:XX:XX"            // GROSSschrift, Doppelpunkte
 *
 * Der Namespace-String ist Teil des Vertrags. Testvektor aus der Spezifikation
 * (muss auf JEDER Plattform exakt herauskommen, siehe PlayerIdentityTest):
 *
 *   seed "boxtype=sf8008|sn=272400008A1E|chipset=3798mv200|board_revision=1.0|nic=d0:27:24:00:8a:1e"
 *     -> "C2:75:9A:B3:8D:17"
 */
fun derivePlayerId(seed: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest((PID_NAMESPACE + "|" + seed).toByteArray(Charsets.UTF_8))
    val b = digest.copyOfRange(0, 6)
    b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte()   // lokal administriert + unicast
    return b.joinToString(":") { String.format("%02X", it) }
}

/**
 * Stabile Merkmale dieser Maschine als Seed, feste Reihenfolge:
 * `machine_id=<wert>|os=<wert>|nic:<ifName>=<mac>|...`.
 *
 *  - machine_id: /etc/machine-id (Linux), IOPlatformUUID (macOS,
 *                MachineGuid via Registry (Windows)
 *  - os:         os.name, normalisiert auf linux/mac/windows
 *  - nic:        ALLE physischen Netzwerk-Schnittstellen mit Hardware-Adresse,
 *                je als `nic:<ifName>=<mac>`, sortiert nach ifName. Rechner
 *                haben meist LAN und WLAN gleichzeitig — zaehlte nur eine,
 *                kippte der Seed bei jedem Wechsel der aktiven Verbindung.
 *
 * Fehlt ein Merkmal (nicht lesbar, externer Befehl nicht verfuegbar, keine
 * NIC), faellt der Teil aus dem Seed WEG — nicht als leerer String anhaengen,
 * sonst waere die Ableitung nicht reproduzierbar. Ist der komplette Seed leer,
 * darf keine Ableitung erfolgen (CredStore faellt dann auf Zufall zurueck).
 *
 * Externe Prozesse laufen mit max. 3 s Timeout; jede Ausnahme fuehrt zum
 * Weglassen des Merkmals, nie zum Absturz der App.
 */
fun machineSeed(): String {
    val parts = ArrayList<String>(4)
    machineId()?.let { parts.add("machine_id=$it") }
    osName()?.let { parts.add("os=$it") }
    parts.addAll(nicParts())
    return parts.joinToString("|")
}

/** Maschinen-ID des OS (Linux/macOS/Windows); null, wenn nicht ermittelbar. */
private fun machineId(): String? {
    val os = System.getProperty("os.name")?.lowercase() ?: return null
    return when {
        "linux" in os -> runCatching {
            File("/etc/machine-id").takeIf { it.exists() }?.readText()?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        "mac" in os || "darwin" in os ->
            commandOutput(listOf("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"))
                ?.let { Regex("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                ?.takeIf { it.isNotEmpty() }

        "windows" in os ->
            commandOutput(listOf("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid"))
                ?.let { Regex("MachineGuid\\s+REG_SZ\\s+(\\S+)").find(it)?.groupValues?.get(1) }
                ?.takeIf { it.isNotEmpty() }

        else -> null
    }
}

/** os.name, normalisiert auf linux/mac/windows (unbekannte Namen: lowercase Original). */
private fun osName(): String? {
    val raw = System.getProperty("os.name")?.trim() ?: return null
    return when {
        raw.contains("Linux", ignoreCase = true) -> "linux"
        raw.contains("Mac", ignoreCase = true) || raw.contains("Darwin", ignoreCase = true) -> "mac"
        raw.contains("Windows", ignoreCase = true) -> "windows"
        raw.isNotEmpty() -> raw.lowercase()
        else -> null
    }
}

/**
 * Alle Netzwerk-Hardware-Adressen als Seed-Teile, feste Reihenfolge:
 * `nic:<ifName>=<mac>` je Schnittstelle, sortiert nach Name.
 *
 * Bewusst ALLE statt nur der ersten: Rechner haben meist LAN und WLAN
 * gleichzeitig. Wuerde nur eine davon zaehlen, kippt der Seed, sobald das
 * OS die andere nach vorne sortiert oder eine ausfaellt — obwohl die
 * Maschine dieselbe ist. Die Menge beider ist stabil.
 *
 * Ausgeschlossen: Loopback, Null-/Broadcast-Adressen, virtuelle Netze
 * (docker/veth/br-/tun/tap/virbr/vmnet/vboxnet) und — wo erkennbar — NICs
 * am USB-Bus (Dock, Stick). Ein abgezogener USB-Adapter ist Zubehoer, kein
 * Teil der Maschine. Gibt es keine eingebaute NIC, zaehlen ersatzweise alle.
 */
private fun nicParts(): List<String> = runCatching {
    val all = NetworkInterface.getNetworkInterfaces()?.asSequence()
        ?.filterNotNull()
        ?.filterNot { runCatching { it.isLoopback }.getOrDefault(true) }
        ?.mapNotNull { ni ->
            val name = ni.name ?: return@mapNotNull null
            if (VIRTUAL_NIC_PREFIXES.any { name.startsWith(it, ignoreCase = true) }) return@mapNotNull null
            val mac = runCatching { ni.hardwareAddress }.getOrNull() ?: return@mapNotNull null
            if (mac.isEmpty() || mac.all { it == 0.toByte() }) return@mapNotNull null
            name to mac.joinToString(":") { String.format("%02x", it) }
        }
        ?.sortedWith(compareBy({ it.first.lowercase() }, { it.second }))
        ?.toList()
        ?: emptyList()
    val builtin = all.filter { isBuiltinNic(it.first) }
    (builtin.ifEmpty { all }).map { "nic:${it.first}=${it.second}" }
}.getOrElse { emptyList() }

private val VIRTUAL_NIC_PREFIXES = listOf(
    "docker", "veth", "br-", "virbr", "tun", "tap", "vmnet", "vboxnet",
    "utun", "awdl", "llw", "gif", "stf", "bridge",
)

/**
 * Liegt die NIC auf einem festen Bus (PCIe/SoC) statt an USB? Auf Systemen
 * ohne sysfs (Windows, macOS) ist das nicht pruefbar — dann gilt sie als
 * eingebaut, damit die Ableitung dort nicht ohne Netz-Teil dasteht.
 */
private fun isBuiltinNic(name: String): Boolean = runCatching {
    val dev = File("/sys/class/net/$name/device").canonicalPath
    if (!dev.startsWith("/sys/")) return@runCatching true      // kein sysfs -> nicht beurteilbar
    !dev.startsWith("/sys/devices/virtual") && !dev.contains("/usb")
}.getOrDefault(true)

/** Externen Befehl ausfuehren, Ausgabe mit 3-s-Timeout lesen; null bei jedem Fehler. */
private fun commandOutput(command: List<String>): String? = runCatching {
    val proc = ProcessBuilder(command).start()
    try {
        // Lesen mit Timeout — ein haengender Prozess blockiert uns nicht:
        // schlaegt das Future fehl, killt destroyForcibly() den Prozess und
        // gibt den Lesethread frei.
        val reader = CompletableFuture.supplyAsync { proc.inputStream.readBytes().toString(Charsets.UTF_8) }
        val output = reader.get(3, TimeUnit.SECONDS)
        if (proc.waitFor(3, TimeUnit.SECONDS)) output else null
    } finally {
        proc.destroyForcibly()
    }
}.getOrNull()