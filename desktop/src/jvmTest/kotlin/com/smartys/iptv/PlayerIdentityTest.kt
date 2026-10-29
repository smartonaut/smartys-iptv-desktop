package com.smartys.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests der stabilen Player-ID nach PLAYER-IDENTITY.md.
 *
 * Der Testvektor ist Teil des plattformuebergreifenden Vertrags: Alle
 * Plattformen (Enigma2, Android, Desktop, Tizen, webOS) muessen fuer den
 * selben Seed exakt die selbe ID liefern.
 */
class PlayerIdentityTest {

    /** Pflicht-Testvektor aus PLAYER-IDENTITY.md — muss exakt herauskommen. */
    @Test
    fun `Spezifikations-Testvektor ergibt die vorgeschriebene ID`() {
        val seed = "boxtype=sf8008|sn=272400008A1E|chipset=3798mv200|board_revision=1.0|nic=d0:27:24:00:8a:1e"
        assertEquals("C2:75:9A:B3:8D:17", derivePlayerId(seed))
    }

    @Test
    fun `gleicher Seed ergibt gleiche ID im MAC-Format`() {
        val a = derivePlayerId("machine_id=abc123|os=linux|nic=d0:27:24:00:8a:1e")
        val b = derivePlayerId("machine_id=abc123|os=linux|nic=d0:27:24:00:8a:1e")
        assertEquals(a, b)
        assertTrue(Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(a), "id=$a")
    }

    @Test
    fun `Byte 0 ist lokal administriert und unicast`() {
        // Bit 0x02 (lokal administriert) gesetzt, Bit 0x01 (Multicast) nie gesetzt.
        val high = derivePlayerId("beliebiger-seed").take(2).toInt(16)
        assertEquals(0x02, high and 0x03)
    }

    @Test
    fun `machineSeed besteht nur aus key=value-Teilen oder ist komplett leer`() {
        val seed = machineSeed()
        if (seed.isEmpty()) return // keine Merkmale lesbar -> CredStore wuerfelt dann
        // Teile: machine_id=..., os=..., nic:<ifName>=<mac>
        assertTrue(
            Regex("^([a-z0-9_]+(:[A-Za-z0-9_.\\-]+)?=[^|]*)(\\|[a-z0-9_]+(:[A-Za-z0-9_.\\-]+)?=[^|]*)*$").matches(seed),
            "seed=$seed"
        )
        // os.name ist eine Systemproperty und damit praktisch immer verfuegbar.
        assertTrue(seed.contains("os="), "seed=$seed")
    }

    /** Der Seed muss bei wiederholtem Aufruf identisch sein (sonst keine stabile ID). */
    @Test
    fun `machineSeed ist bei jedem Aufruf gleich`() {
        assertEquals(machineSeed(), machineSeed())
    }

    /** Virtuelle Interfaces duerfen den Seed nicht verrauschen (docker, veth, ...). */
    @Test
    fun `machineSeed enthaelt keine virtuellen Interfaces`() {
        val seed = machineSeed()
        for (prefix in listOf("docker", "veth", "br-", "virbr", "tun", "tap", "vmnet", "vboxnet")) {
            assertTrue(
                !Regex("nic:${Regex.escape(prefix)}[^|]*=").containsMatchIn(seed),
                "virtuelles Interface im Seed: $seed"
            )
        }
    }
}