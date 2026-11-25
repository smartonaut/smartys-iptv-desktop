package com.smartys.iptv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import javax.imageio.ImageIO

/**
 * Tab "Kameras": eigene Kameras vom LAN-Server plus oeffentliche Webcams.
 * Bilder (jpg) werden im Hintergrund geladen und im Takt von `refreshSek`
 * erneuert; HLS laeuft ueber den normalen Player.
 */
@Composable
fun CamerasScreen(onOpen: (Kameras.Kamera) -> Unit, onWand: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var lokal by remember { mutableStateOf<List<Kameras.Kamera>>(emptyList()) }
    var firmen by remember { mutableStateOf<List<Kameras.Kamera>>(emptyList()) }
    var suche by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf<String?>(null) }
    // Drei Schalter aus den Einstellungen — jede Quelle einzeln an/aus.
    val freiAn = KameraPrefs.freie()
    val firmenAn = KameraPrefs.firmen()

    LaunchedEffect(Unit) {
        if (firmenAn) {
            firmen = withContext(Dispatchers.IO) { Kameras.firmenKameras() }
            if (firmen.isEmpty()) meldung = "Firmen-Kameras: ${KameraPrefs.firmenServer()} antwortet nicht"
        }
        val bekannt = Kameras.server()
        if (bekannt != null) {
            lokal = withContext(Dispatchers.IO) { Kameras.vomServer(bekannt) }
            if (lokal.isEmpty() && firmen.isEmpty()) meldung = "Server $bekannt antwortet nicht mehr"
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kameras", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp))
            Button(onClick = {
                suche = true; meldung = null
                scope.launch {
                    val gefunden = withContext(Dispatchers.IO) { Kameras.imLanSuchen() }
                    lokal = gefunden
                    suche = false
                    if (gefunden.isEmpty()) meldung = "Kein Kamera-Server im Netz gefunden"
                }
            }) { Text(if (suche) "suche …" else "Server im LAN suchen") }
            Spacer(Modifier.width(12.dp))
            if (firmenAn) {
                Button(onClick = onWand) { Text("Wand wie am Pi") }
                Spacer(Modifier.width(12.dp))
            }
            meldung?.let { Text(it, color = TextMid, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(16.dp))

        if (firmenAn && firmen.isNotEmpty()) {
            Text("Firmen-Kameras (VLAN)", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Kaestchen(firmen, onOpen)
            Spacer(Modifier.height(20.dp))
        }

        if (lokal.isNotEmpty()) {
            Text("Eigene Kameras", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Kaestchen(lokal, onOpen)
            Spacer(Modifier.height(20.dp))
        }

        if (freiAn) {
            Text("Öffentliche Webcams", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Kaestchen(Kameras.OEFFENTLICH, onOpen)
        }

        if (!freiAn && !firmenAn && lokal.isEmpty()) {
            Text(
                "Alle Kamera-Quellen sind aus — in den Einstellungen unter " +
                    "\"Kameras & freie Streams\" einschalten.",
                color = TextMid, fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun Kaestchen(liste: List<Kameras.Kamera>, onOpen: (Kameras.Kamera) -> Unit) {
    Column {
        liste.chunked(4).forEach { reihe ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                reihe.forEach { k ->
                    Kachel(k, Modifier.weight(1f).padding(end = 10.dp)) { onOpen(k) }
                }
                repeat(4 - reihe.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Kachel(k: Kameras.Kamera, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Card)
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Text(k.name, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Text(
            if (k.lokal) "LAN · ${k.typ.uppercase()}" else "${k.land} · ${k.typ.uppercase()}",
            color = TextMid, fontSize = 11.sp
        )
    }
}

/** Einzelbild-Kamera: laedt das JPEG und erneuert es im hinterlegten Takt. */
@Composable
fun CamPlayerScreen(k: Kameras.Kamera, vm: DesktopViewModel, onBack: () -> Unit) {
    if (k.typ == "hls") {
        // HLS laeuft im normalen Player — Kameras brauchen keine Resume-Position.
        VodPlayerScreen(
            VodPlaying(k.name, k.url, "cam:${k.name}", k.name, null, false, null, null, live = true),
            vm,
            onBack
        )
        return
    }

    var bild by remember(k.url) { mutableStateOf<ImageBitmap?>(null) }
    var fehler by remember(k.url) { mutableStateOf<String?>(null) }

    LaunchedEffect(k.url) {
        while (true) {
            val geladen = withContext(Dispatchers.IO) {
                runCatching { ImageIO.read(URL(k.url))?.toComposeImageBitmap() }.getOrNull()
            }
            if (geladen != null) { bild = geladen; fehler = null }
            else fehler = "Bild nicht erreichbar"
            delay((k.refreshSek.coerceAtLeast(5)) * 1000L)
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Zurück", color = Accent) }
            Spacer(Modifier.width(12.dp))
            Text(k.name, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${k.land} · Aktualisierung alle ${k.refreshSek}s", color = TextMid, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val b = bild
            if (b != null) {
                Image(bitmap = b, contentDescription = k.name, modifier = Modifier.fillMaxSize())
            } else {
                Text(fehler ?: "lade Bild …", color = TextMid, fontSize = 16.sp)
            }
        }
    }
}
