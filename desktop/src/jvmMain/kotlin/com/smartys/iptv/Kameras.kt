package com.smartys.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Kameras — Port des webOS-Cam-Viewers.
 *
 * Zwei Quellen: oeffentliche Webcams (jpg mit Refresh / hls) und die eigenen
 * Kameras ueber einen Server im LAN. Drei Server-Formate werden erkannt:
 *   - Smartys-Bridge:  <base>/cams.json      -> [name,url,type]
 *   - go2rtc:          <base>/api/streams    -> HLS je <base>/api/stream.m3u8?src=<name>
 *   - CamViewer:       <base>/api/cameras    -> HLS je <base>/hls/<id>/index.m3u8
 * RTSP spielt kein Player — eigene Kameras laufen ausschliesslich ueber den Server.
 */
object Kameras {

    data class Kamera(
        val name: String,
        val land: String,
        val typ: String,          // "jpg" | "hls"
        val url: String,
        val refreshSek: Int = 10,
        val lokal: Boolean = false,
        // Server-Kennung — fuer das Wand-Layout pro Kamera (Breite, Reihenfolge).
        val id: String = ""
    )

    /** Nur frei zugaengliche Webcams, jede in der App per HTTP geprueft. */
    val OEFFENTLICH: List<Kamera> = listOf(
        Kamera("Zugspitze Gipfelpanorama", "DE", "jpg", "https://www.foto-webcam.eu/webcam/zugspitze/current/1200.jpg", 60),
        Kamera("Eibsee mit Zugspitze", "DE", "jpg", "https://www.foto-webcam.eu/webcam/eibsee-nord/current/1200.jpg", 60),
        Kamera("Zell am See Seeufer", "AT", "jpg", "https://www.foto-webcam.eu/webcam/zellamsee/current/1200.jpg", 60),
        Kamera("Furkajoch Passstrasse", "AT", "jpg", "https://www.foto-webcam.eu/webcam/furkajoch/current/1200.jpg", 60),
        Kamera("Dornbirn Stadtpanorama", "AT", "jpg", "https://www.foto-webcam.eu/webcam/dornbirn/current/1200.jpg", 60),
        Kamera("Bludenz Zentrum", "AT", "jpg", "https://www.foto-webcam.eu/webcam/bludenz-zentrum/current/1200.jpg", 60),
        Kamera("Flensburger Hafen", "DE", "jpg", "https://www.foto-webcam.eu/webcam/flensburg/current/1200.jpg", 60),
        Kamera("Dachau Altstadt", "DE", "jpg", "https://www.foto-webcam.eu/webcam/dachau/current/1200.jpg", 60),
        Kamera("Opel-Zoo Kronberg", "DE", "jpg", "https://cam.opel-zoo.de/current.jpg", 30),
        Kamera("Brunnen Vierwaldstättersee", "CH", "jpg", "https://www.foto-webcam.eu/webcam/brunnen/current/1200.jpg", 60),
        Kamera("San Diego Zoo — Koala", "US", "hls", "https://zssd-koala.hls.camzonecdn.com/CamzoneStreams/zssd-koala/Playlist.m3u8", 0),
        Kamera("San Diego Zoo — Löwe", "US", "hls", "https://zssd-lion.hls.camzonecdn.com/CamzoneStreams/zssd-lion/Playlist.m3u8", 0),
        Kamera("San Diego Zoo — Schnabeltier", "US", "hls", "https://zssd-platypus.hls.camzonecdn.com/CamzoneStreams/zssd-platypus/Playlist.m3u8", 0),
        Kamera("San Francisco — I-80 Powell St", "US", "hls", "https://wzmedia.dot.ca.gov/D4/W80_at_Powell_St.stream/playlist.m3u8", 0),
        Kamera("Minneapolis — I-35 NB", "US", "hls", "https://video.dot.state.mn.us/public/D6-C132.stream/playlist.m3u8", 0),
        Kamera("Seattle — 12th Ave NE", "US", "hls", "https://61e0c5d388c2e.streamlock.net/live/12_NE_50.stream/playlist.m3u8", 0),
        Kamera("E39 Fidjane (Statens vegvesen)", "NO", "hls", "https://kamera.vegvesen.no/public/1029002_1/manifest.m3u8", 0),
        Kamera("Ribblehead Viaduct", "GB", "hls", "https://paxman.railcam.co.uk/Ribblehead_Viaduct/tracks-v1a1/mono.m3u8", 0),
        Kamera("Cianjur — Tugu Gentur", "ID", "hls", "https://atcs.cianjurkab.go.id:5443/LieApp/streams/BundaranTuguGentur.m3u8", 0),
        Kamera("Richmond — 14th & Dock", "US", "hls", "https://media-sfs6.vdotcameras.com:443/rtplive/richmondstream014/playlist.m3u8", 0),
    )

    private fun dir() = File(System.getProperty("user.home"), ".smartys-iptv")
    private fun serverDatei() = File(dir(), "camserver.txt")

    fun server(): String? = runCatching { serverDatei().readText().trim().ifBlank { null } }.getOrNull()

    fun serverMerken(base: String?) {
        runCatching {
            dir().mkdirs()
            if (base.isNullOrBlank()) serverDatei().delete() else serverDatei().writeText(base.trimEnd('/'))
        }
    }

    /** HttpURLConnection — java.net.http fehlt in der jpackage-Runtime. */
    private fun hole(url: String): String? = runCatching {
        val c = URI.create(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 900
        c.readTimeout = 6000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun prefixe(): List<String> {
        val p = LinkedHashSet<String>()
        val bekannt = (server() ?: "").removePrefix("http://").removePrefix("https://")
        if (bekannt.split(".").size == 4) p += bekannt.split(".").take(3).joinToString(".") + "."
        listOf("192.168.1.", "192.168.0.", "192.168.4.", "10.0.0.").forEach { p += it }
        // Buero-VLANs nur, wenn die Firmen-Kameras eingeschaltet sind.
        // Keine internen Netze fest eingetragen: das oeffentliche Repo bleibt
        // neutral, die Adresse kommt aus den Einstellungen bzw. firma.properties.   // Bueronetze nur per Suche, ohne feste Server-Adresse
        return p.toList()
    }

    /** Firmen-Kameras (Buero) vom CamViewer-Backend — nur wenn eingeschaltet. */
    fun firmenKameras(): List<Kamera> {
        if (!KameraPrefs.firmen()) return emptyList()
        return vomServer(KameraPrefs.firmenServer()).map { it.copy(lokal = true) }
    }

    /** LAN nach einem Kamera-Server absuchen (alle drei Formate). */
    suspend fun imLanSuchen(): List<Kamera> = withContext(Dispatchers.IO) {
        val hosts = prefixe().flatMap { pr -> (1..254).map { "$pr$it" } }
        for (block in hosts.chunked(48)) {
            var treffer: List<Kamera> = emptyList()
            coroutineScope {
                val versuche = block.map { host -> async { vomServer("http://$host") } }
                versuche.awaitAll().firstOrNull { it.isNotEmpty() }?.let { treffer = it }
            }
            if (treffer.isNotEmpty()) {
                serverMerken("http://" + block.first().substringBeforeLast("."))
                return@withContext treffer
            }
        }
        emptyList()
    }

    /** Kameraliste eines Servers holen — erkennt Bridge, go2rtc und CamViewer. */
    fun vomServer(base: String): List<Kamera> {
        val b = base.trimEnd('/')
        hole("$b/cams.json")?.let { txt ->
            runCatching {
                val arr = com.google.gson.JsonParser.parseString(txt)
                val liste = if (arr.isJsonArray) arr.asJsonArray else arr.asJsonObject.getAsJsonArray("cams")
                liste?.mapNotNull { el ->
                    val o = el.asJsonObject
                    val url = o.get("url")?.asString ?: return@mapNotNull null
                    Kamera(
                        o.get("name")?.asString ?: "Kamera",
                        "PC",
                        o.get("type")?.asString ?: "hls",
                        url,
                        o.get("refresh")?.asInt ?: 10,
                        true
                    )
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        hole("$b/api/streams")?.let { txt ->
            runCatching {
                com.google.gson.JsonParser.parseString(txt).asJsonObject.keySet()
                    .filter { it != "wall" }
                    .map { Kamera(it, "PC", "hls", "$b/api/stream.m3u8?src=$it", 10, true) }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        hole("$b/api/cameras")?.let { txt ->
            runCatching {
                // CamViewer liefert {"cameras":[...]}, aeltere Fassungen ein Array.
                val wurzel = com.google.gson.JsonParser.parseString(txt)
                val reihe = if (wurzel.isJsonArray) wurzel.asJsonArray
                            else wurzel.asJsonObject.getAsJsonArray("cameras")
                reihe.mapNotNull { el ->
                    val o = el.asJsonObject
                    val id = o.get("id")?.asString ?: o.get("name")?.asString ?: return@mapNotNull null
                    Kamera(
                        o.get("name")?.asString ?: id, "PC", "hls",
                        "$b/hls/$id/index.m3u8", 10, true, id
                    )
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }
}
