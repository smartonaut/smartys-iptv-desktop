package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Programm-Raster (Parität zur iOS-EpgGrid): Zeit waagerecht, Sender untereinander.
 *
 * Aufbau: Die Sender-Spalte steht fest, der Zeitbereich rollt waagerecht. Damit
 * alle Zeilen und die Stundenleiste gemeinsam scrollen, teilen sie sich EINEN
 * ScrollState — Compose erlaubt das und hält die Position synchron.
 */
private const val ZEITFENSTER_STUNDEN = 6
private const val DP_PRO_MINUTE = 3.2f
private const val ZEILE_HOEHE = 58
private const val SENDER_BREITE = 148

private val zeitFormat = SimpleDateFormat("HH:mm", Locale.GERMAN)

/** Beginn der aktuellen Stunde — „Jetzt“ setzt das Fenster hierher zurück. */
private fun stundenBeginn(jetzt: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = jetzt
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

@Composable
fun EpgGridScreen(
    streams: List<LiveStream>,
    epgBereit: Boolean,
    epgStatus: String?,
    onEpgLaden: () -> Unit,
    onKanalWaehlen: (LiveStream) -> Unit,
    onZurueck: () -> Unit
) {
    var fensterStart by remember { mutableStateOf(stundenBeginn(System.currentTimeMillis())) }
    val fensterEnde = fensterStart + ZEITFENSTER_STUNDEN * 3_600_000L
    val gesamtBreite = (ZEITFENSTER_STUNDEN * 60 * DP_PRO_MINUTE).dp

    val hState = rememberScrollState()
    val vState = rememberScrollState()

    fun xVon(zeit: Long): Float =
        ((zeit - fensterStart) / 60_000f).coerceAtLeast(0f) * DP_PRO_MINUTE

    val stunden = (0..ZEITFENSTER_STUNDEN).map { fensterStart + it * 3_600_000L }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp)) {
        // ---- Kopfzeile ----
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.s("Programm"), color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text(
                I18n.sf("{0} Sender · {1} Stunden", streams.size, ZEITFENSTER_STUNDEN),
                color = TextMid, fontSize = 13.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                I18n.s("Jetzt"),
                color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Card)
                    .clickable { fensterStart = stundenBeginn(System.currentTimeMillis()) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                I18n.s("Zurück"),
                color = TextHi, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Card)
                    .clickable { onZurueck() }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        if (!epgBereit) {
            // Kein EPG geladen: Hinweis + Knopf, statt leerem Raster
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(20.dp)) {
                Column {
                    Text(
                        epgStatus ?: I18n.s("Kein Programm geladen."),
                        color = TextMid, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        I18n.s("Jetzt laden"),
                        color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(Bg)
                            .clickable { onEpgLaden() }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
            return@Column
        }

        // ---- Stundenleiste (scrollt waagerecht mit) ----
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(SENDER_BREITE.dp))
            Row(Modifier.horizontalScroll(hState)) {
                Box(Modifier.width(gesamtBreite).height(24.dp)) {
                    stunden.forEach { zeit ->
                        Text(
                            zeitFormat.format(Date(zeit)),
                            color = TextMid, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = (xVon(zeit) + 4).dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        // ---- Senderzeilen ----
        Column(Modifier.fillMaxSize().verticalScroll(vState)) {
            streams.forEach { sender ->
                val programme = remember(sender.streamId, fensterStart) {
                    val alle = XmltvEpg.programmesFor(sender.epgChannelId)
                        .ifEmpty { XmltvEpg.programmesForName(sender.name) }
                    alle.filter { it.stop > fensterStart && it.start < fensterEnde }
                }
                val jetzt = System.currentTimeMillis()

                Row(
                    Modifier.fillMaxWidth().height(ZEILE_HOEHE.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onKanalWaehlen(sender) }
                ) {
                    // Sender-Spalte
                    Column(
                        Modifier.width(SENDER_BREITE.dp).fillMaxHeight().padding(end = 8.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            sender.name,
                            color = TextHi, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        sender.num?.let {
                            Text(I18n.sf("Nr. {0}", it), color = TextMid, fontSize = 10.sp)
                        }
                    }

                    // Zeitbereich
                    Row(Modifier.horizontalScroll(hState)) {
                        Box(Modifier.width(gesamtBreite).fillMaxHeight()) {
                            // Stundenlinien
                            stunden.forEach { zeit ->
                                Box(
                                    Modifier
                                        .padding(start = xVon(zeit).dp)
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(Color(0x1FFFFFFF))
                                )
                            }
                            if (programme.isEmpty()) {
                                Text(
                                    I18n.s("Keine Programmdaten"),
                                    color = Color(0x40FFFFFF), fontSize = 11.sp,
                                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                                )
                            }
                            programme.forEach { p ->
                                val links = xVon(p.start)
                                val rechts = xVon(p.stop).coerceAtMost(ZEITFENSTER_STUNDEN * 60 * DP_PRO_MINUTE)
                                val breite = (rechts - links).coerceAtLeast(24f)
                                val laeuft = p.start <= jetzt && p.stop > jetzt
                                Column(
                                    Modifier
                                        .offset(x = links.dp)
                                        .width(breite.dp)
                                        .fillMaxHeight()
                                        .padding(vertical = 3.dp, horizontal = 1.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (laeuft) Accent.copy(alpha = 0.25f) else Color(0x14FFFFFF))
                                        .padding(horizontal = 5.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        p.title,
                                        color = if (laeuft) TextHi else TextMid,
                                        fontSize = 11.sp,
                                        fontWeight = if (laeuft) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${zeitFormat.format(Date(p.start))}–${zeitFormat.format(Date(p.stop))}",
                                        color = Color(0x66FFFFFF), fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
