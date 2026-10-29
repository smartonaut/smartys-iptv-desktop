package com.smartys.iptv

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before

/**
 * Integrationstest der Aufl�sungsreihenfolge aus PLAYER-IDENTITY.md.
 *
 * Laeuft gegen ein Sandbox-Home (user.home wird umgebogen), damit die echten
 * Dateien des Nutzers (~/.smartys-iptv/config.json, ~/.config/smartys-iptv/)
 * unberuehrt bleiben. XDG_CONFIG_HOME ist im Testprozess nicht gesetzt, der
 * Spiegel landet also unter <sandbox>/.config/smartys-iptv/identity.json.
 */
class CredStoreIdentityTest {

    private val realHome: String? = System.getProperty("user.home")
    private lateinit var home: File

    @Before
    fun sandboxHome() {
        home = Files.createTempDirectory("smartys-credstore-test").toFile()
        System.setProperty("user.home", home.absolutePath)
    }

    @After
    fun restoreHome() {
        System.setProperty("user.home", realHome ?: "")
        home.deleteRecursively()
    }

    private fun configFile(): File = File(File(home, ".smartys-iptv"), "config.json")
    private fun mirrorFile(): File = File(File(home, ".config/smartys-iptv"), "identity.json")

    @Test
    fun `Stufe 1 - gespeicherte ID wird nie geaendert, Spiegel unberuehrt`() {
        configFile().parentFile.mkdirs()
        configFile().writeText("""{"player_id":"AA:BB:CC:DD:EE:02","pairing_pw":"AltesPw123"}""")
        mirrorFile().parentFile.mkdirs()
        mirrorFile().writeText("""{"player_id":"11:22:33:44:55:66"}""")

        assertEquals("AA:BB:CC:DD:EE:02", CredStore.playerId())
        assertEquals("AA:BB:CC:DD:EE:02", CredStore.playerId()) // stabil

        // config.json unveraendert (kein Neu-Schreiben in Stufe 1):
        val cfg = org.json.JSONObject(configFile().readText())
        assertEquals("AltesPw123", cfg.getString("pairing_pw"))
        assertFalse(cfg.has("player_id_source"))
        // Spiegel durch Stufe 1 nicht angefasst:
        val m = org.json.JSONObject(mirrorFile().readText())
        assertEquals("11:22:33:44:55:66", m.getString("player_id"))
        assertFalse(m.has("pairing_pw"))
    }

    @Test
    fun `Stufe 2 - ohne config-ID wird die Spiegel-Identitaet uebernommen`() {
        mirrorFile().parentFile.mkdirs()
        mirrorFile().writeText(
            """{"player_id":"AA:BB:CC:DD:EE:02","pairing_pw":"SpiegelPw456","player_id_source":"hw-v1"}""")

        assertEquals("AA:BB:CC:DD:EE:02", CredStore.playerId())

        val cfg = org.json.JSONObject(configFile().readText())
        assertEquals("AA:BB:CC:DD:EE:02", cfg.getString("player_id"))
        assertEquals("hw-v1", cfg.getString("player_id_source"))
        // Mit verloren gegangenem Profil auch die Pairing-Daten aus dem Spiegel retten:
        assertEquals("SpiegelPw456", cfg.getString("pairing_pw"))
        assertEquals("SpiegelPw456", CredStore.pairingPw()) // pairingPw() wuerfelt NICHT neu
    }

    @Test
    fun `Stufe 3 - bei jungfraeulichem Zustand wird neu ermittelt und gespiegelt`() {
        val id = CredStore.playerId()
        assertTrue(id.isNotEmpty())

        val cfg = org.json.JSONObject(configFile().readText())
        assertEquals(id, cfg.getString("player_id"))
        val source = cfg.getString("player_id_source")
        assertTrue(source == "hw-v1" || source == "random", "source=$source")

        // Spiegel erhaelt dieselbe Identitaet ...
        val m = org.json.JSONObject(mirrorFile().readText())
        assertEquals(id, m.getString("player_id"))
        assertEquals(source, m.getString("player_id_source"))
        // ... und ein erneuter Aufruf bleibt stabil (jetzt Stufe 1):
        assertEquals(id, CredStore.playerId())
    }

    @Test
    fun `eine fremde Spiegel-ID wird niemals ueberschrieben`() {
        // config.json mit einer ID OHNE pairing_pw, Spiegel mit einer ANDEREN ID:
        configFile().parentFile.mkdirs()
        configFile().writeText("""{"player_id":"AA:BB:CC:DD:EE:02","player_id_source":"hw-v1"}""")
        mirrorFile().parentFile.mkdirs()
        mirrorFile().writeText("""{"player_id":"11:22:33:44:55:66"}""")

        val pw = CredStore.pairingPw() // wuerfelt neu -> updateMirror(AA:BB:CC:DD:EE:02, pw, hw-v1)

        // Der Spiegel ist ein Gedaechtnis: die ANDERE player_id bleibt stehen ...
        val m = org.json.JSONObject(mirrorFile().readText())
        assertEquals("11:22:33:44:55:66", m.getString("player_id"))
        // ... aber pairing_pw und Quelle werden ergaenzt:
        assertEquals(pw, m.getString("pairing_pw"))
        assertEquals("hw-v1", m.getString("player_id_source"))
        assertEquals(pw, CredStore.pairingPw()) // und bleibt stabil
    }
}