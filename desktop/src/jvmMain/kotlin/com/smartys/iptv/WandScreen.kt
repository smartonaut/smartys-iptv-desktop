package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Canvas

/**
 * Kamera-Wand — Port der Kiosk-Ansicht des Raspberry Pi.
 *
 * Reihen werden aus den Feldbreiten gebildet (Dual-Lens belegt zwei Felder) und
 * fuellen die Flaeche automatisch, weil jede Kachel ein `weight` in Feldern
 * bekommt. Das Layout liegt PRO PROFIL in [KameraPrefs], jeder stellt sich
 * seinen eigenen Stil ein (Spalten, Kachelbreite, Reihenfolge).
 *
 * Kein Balken ueber dem Bild: der Name steht als Text mit Schatten direkt auf
 * der Kachel.
 */
@Composable
fun WandScreen(onBack: () -> Unit, onOpen: (Kameras.Kamera) -> Unit) {
    val profil = ProfileStore.activeId() ?: "default"
    var kameras by remember { mutableStateOf<List<Kameras.Kamera>>(emptyList()) }
    var meldung by remember { mutableStateOf<String?>(null) }
    var spalten by remember { mutableStateOf(KameraPrefs.wandSpalten(profil)) }
    var breiten by remember { mutableStateOf(KameraPrefs.wandBreiten(profil)) }
    var reihe by remember { mutableStateOf(KameraPrefs.wandReihe(profil)) }
    // Bedienleiste erscheint erst auf Wunsch (Mausrad/Rechtsklick waere auf dem
    // Pi-Kiosk auch nicht da) — hier per Knopf unten rechts ein-/ausblendbar.
    var leiste by remember { mutableStateOf(false) }

    // Esc schliesst die Wand - auf dem PC der natuerliche Rueckweg.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // nichts, Platzhalter fuer Tastenbehandlung unten
    }
    LaunchedEffect(Unit) {
        val geladen = withContext(Dispatchers.IO) { Kameras.firmenKameras() }
        kameras = geladen
        if (geladen.isEmpty()) meldung = "Firmen-Kameras: ${KameraPrefs.firmenServer()} antwortet nicht"
    }

    // Reihenfolge: gespeicherte IDs zuerst, neue Kameras hinten anhaengen.
    val geordnet = remember(kameras, reihe) {
        val bekannt = reihe.mapNotNull { id -> kameras.firstOrNull { it.id == id || it.name == id } }
        bekannt + kameras.filterNot { k -> bekannt.any { it.id == k.id && it.name == k.name } }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(10.dp)
    ) {
        // Rueckweg IMMER sichtbar: auch wenn kein Server antwortet oder die
        // Bedienleiste ausgeblendet ist, darf man nicht im schwarzen Bild haengen.
        Row(verticalAlignment = Alignment.CenterVertically) {
            KKnopf("← Zurück") { onBack() }
            Spacer(Modifier.width(12.dp))
            Text(
                if (meldung != null) meldung!! else "${geordnet.size} Kameras",
                color = if (meldung != null) Color(0xFFFFB4A9) else TextMid,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        if (leiste) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kamera-Wand", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(20.dp))
                Text("Spalten:", color = TextMid, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                listOf(1, 2, 3, 4).forEach { n ->
                    KKnopf("$n", aktiv = spalten == n) {
                        spalten = n
                        KameraPrefs.setWandSpalten(profil, n)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.width(16.dp))
                Text("${geordnet.size} Kameras", color = TextMid, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
        }


        // Reihen aus den Feldbreiten bilden — jede Kachel bekommt ihr Feld als
        // Gewicht, dadurch fuellt die Wand die Flaeche ohne Rest.
        val zeilen = remember(geordnet, breiten, spalten) {
            val out = mutableListOf<MutableList<Kameras.Kamera>>()
            var akt = mutableListOf<Kameras.Kamera>()
            var belegt = 0
            geordnet.forEach { k ->
                val b = (breiten[k.id] ?: breiten[k.name] ?: 1).coerceIn(1, spalten)
                if (belegt + b > spalten && akt.isNotEmpty()) {
                    out.add(akt); akt = mutableListOf(); belegt = 0
                }
                akt.add(k); belegt += b
            }
            if (akt.isNotEmpty()) out.add(akt)
            out
        }

        Column(Modifier.fillMaxSize()) {
            zeilen.forEach { zeile ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    zeile.forEach { k ->
                        val b = (breiten[k.id] ?: breiten[k.name] ?: 1).coerceIn(1, spalten)
                        WandKachelDesktop(
                            k = k,
                            breite = b,
                            maxBreite = spalten,
                            versatzMs = geordnet.indexOf(k) * 1500L,
                            modifier = Modifier.weight(b.toFloat()).fillMaxHeight().padding(3.dp),
                            onOpen = { onOpen(k) },
                            onBreite = { neu ->
                                val map = breiten.toMutableMap()
                                val b2 = neu.coerceIn(1, spalten)
                                map[k.id.ifBlank { k.name }] = b2
                                breiten = map
                                KameraPrefs.setWandBreiten(profil, map)
                            },
                            onHoch = {
                                val liste = geordnet.map { it.id.ifBlank { it.name } }.toMutableList()
                                val idx = liste.indexOf(k.id.ifBlank { k.name })
                                if (idx > 0) {
                                    liste[idx] = liste[idx - 1].also { liste[idx - 1] = liste[idx] }
                                    reihe = liste
                                    KameraPrefs.setWandReihe(profil, liste)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Umschalter fuer die Bedienleiste, klein in der Ecke.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            Text(
                if (leiste) "Leiste ausblenden" else "Leiste einblenden",
                color = TextMid, fontSize = 10.sp,
                modifier = Modifier.clickable { leiste = !leiste }.padding(4.dp)
            )
        }
    }
}

/** Knopf mit sichtbarem Zustand (Desktop wird mit der Maus bedient). */
@Composable
private fun KKnopf(text: String, aktiv: Boolean = false, onKlick: () -> Unit) {
    Text(
        text,
        color = if (aktiv) AccentDark else TextHi,
        fontSize = 12.sp,
        fontWeight = if (aktiv) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (aktiv) Accent else Card)
            .clickable(onClick = onKlick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun WandKachelDesktop(
    k: Kameras.Kamera,
    breite: Int,
    maxBreite: Int,
    versatzMs: Long = 0L,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onBreite: (Int) -> Unit,
    onHoch: () -> Unit
) {
    // Kamera-Wand nutzt bewusst mpv: mehrere libvlc-Videoausgaben in einem
    // Prozess liefern nur 1-2 Bilder (Rest gruen), auch mit eigener Instanz und
    // eigenem Fenster je Kachel. libmpv rendert je Kachel in ihr eigenes
    // natives Fenster und hat dieses Problem nicht.
    val player: VodPlayerApi = remember(k.url) {
        if (KameraPrefs.wandEngine() == "vlc") VlcPlayer() else MpvPlayer()
    }
    val canvas = remember(k.url) { Canvas().apply { background = java.awt.Color.BLACK } }
    val inited = remember(k.url) { java.util.concurrent.atomic.AtomicBoolean(false) }
    // Rueckfall-Player, falls die gewaehlte Engine nicht initialisiert (mpv).
    val ersatzPlayer = remember(k.url) { java.util.concurrent.atomic.AtomicReference<VodPlayerApi?>(null) }
    var fokus by remember { mutableStateOf(false) }
    // Eigene X11-Fenster pro Kachel: die Compose-Zeichenflaeche kann mehrere
    // eingebettete Videolayer nicht zuverlaessig darstellen (nur 1-2 sichtbar,
    // Rest gruen). Ein JWindow pro Kachel umgeht das komplett.
    var ziel by remember { mutableStateOf<java.awt.Rectangle?>(null) }
    val fenster = remember(k.url) { java.util.concurrent.atomic.AtomicReference<javax.swing.JWindow?>(null) }

    DisposableEffect(k.url) {
        onDispose {
            val w = fenster.get()
            if (w != null) javax.swing.SwingUtilities.invokeLater { runCatching { w.dispose() } }
            ersatzPlayer.get()?.release()
            player.release()
        }
    }

    LaunchedEffect(k.url, ziel) {
        val r = ziel ?: return@LaunchedEffect
        if (r.width < 40 || r.height < 40) return@LaunchedEffect
        kotlinx.coroutines.delay(versatzMs)
        // ALLES auf dem EDT: libVLC und libmpv verlangen, dass Fenster und
        // native Videoflaeche auf dem Swing-Thread entstehen - sonst schlaegt
        // init() fehl (Log: "init=false") und die Kachel bleibt schwarz.
        javax.swing.SwingUtilities.invokeLater {
            var w = fenster.get()
            if (w == null) {
                w = javax.swing.JWindow()
                w.background = java.awt.Color.BLACK
                w.contentPane = javax.swing.JPanel(java.awt.BorderLayout()).apply {
                    background = java.awt.Color.BLACK
                    add(canvas, java.awt.BorderLayout.CENTER)
                }
                w.isAlwaysOnTop = true
                w.isVisible = true
                canvas.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        if (e.clickCount == 1) runCatching { onOpen() }
                    }
                })
                fenster.set(w)
            }
            w!!.location = java.awt.Point(r.x, r.y)
            w.setSize(r.width, r.height)
            PlayerLog.mark("Wand-Kachel ${k.name}: Fenster ${r.width}x${r.height} @ ${r.x},${r.y}")

            val start = {
                if (!inited.get()) {
                    inited.set(true)
                    player.videoCanvas = canvas
                    val ok = player.init()
                    PlayerLog.log(
                        "Wand-Kachel ${k.name}: Engine=${player.javaClass.simpleName} " +
                            "init=$ok displayable=${canvas.isDisplayable}"
                    )
                    if (ok) {
                        player.play(k.url, isVod = false, timeshift = false, timeshiftDir = null, startPosMs = 0)
                        player.setMuted(true)
                    } else if (player !is VlcPlayer) {
                        // mpv nicht verfuegbar (z.B. mpv_create NULL): Kachel
                        // laeuft trotzdem, dann eben ueber VLC.
                        PlayerLog.log("Wand-Kachel ${k.name}: mpv fehlgeschlagen -> Rueckfall auf VLC")
                        val ersatz = VlcPlayer()
                        ersatz.videoCanvas = canvas
                        if (ersatz.init()) {
                            ersatzPlayer.set(ersatz)
                            ersatz.play(k.url, isVod = false, timeshift = false, timeshiftDir = null, startPosMs = 0)
                            ersatz.setMuted(true)
                        }
                    }
                }
            }
            if (canvas.isDisplayable) start()
            else canvas.addHierarchyListener { _ -> if (canvas.isDisplayable) start() }
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { fokus = true }
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { fokus = false }
            .onGloballyPositioned { c ->
                val s = c.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
                ziel = java.awt.Rectangle(
                    s.x.toInt(), s.y.toInt(),
                    c.size.width.coerceAtLeast(1),
                    (c.size.height - 22).coerceAtLeast(1)
                )
            }
            .border(
                width = if (fokus) 2.dp else 0.dp,
                color = if (fokus) Accent else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                (if (fokus) "● " else "") + k.name,
                color = if (fokus) Accent else Color.White,
                fontSize = 11.sp,
                fontWeight = if (fokus) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    shadow = Shadow(Color.Black, Offset(1f, 1f), 4f)
                )
            )
            if (maxBreite > 1) {
                Text(
                    if (breite > 1) "${breite}F" else "1F",
                    color = Accent, fontSize = 10.sp,
                    modifier = Modifier.clickable { onBreite(if (breite > 1) 1 else 2) }.padding(3.dp)
                )
            }
            Text(
                "▲", color = TextMid, fontSize = 10.sp,
                modifier = Modifier.clickable(onClick = onHoch).padding(3.dp)
            )
        }
    }
}
