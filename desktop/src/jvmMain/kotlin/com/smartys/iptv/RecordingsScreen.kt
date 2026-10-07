package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aufnahmen-Tab: geplante Aufnahmen anlegen (EPG-Auswahl oder manueller Timer)
 * und verwalten (anzeigen, löschen).
 */
@Composable
fun RecordingsScreen(vm: DesktopViewModel, state: AppState.Ready) {
    var recordings by remember { mutableStateOf(RecordingStore.load()) }
    var mode by remember { mutableStateOf<RecordMode?>(null) }

    fun refresh() { recordings = RecordingStore.load() }

    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Aufnahmen", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (vm.isRecording()) {
                Text("● Aufnahme läuft", color = Danger, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))

        // Buttons: EPG-Auswahl + manueller Timer
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { mode = RecordMode.Epg }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentDark)) {
                Text("Aus EPG aufnehmen", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { mode = RecordMode.Manual }) {
                Text("Manueller Timer", color = TextHi)
            }
        }
        Spacer(Modifier.height(20.dp))

        if (recordings.isEmpty()) {
            Text("Keine geplanten Aufnahmen.", color = TextMid, fontSize = 14.sp)
        } else {
            Text("Geplante Aufnahmen", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recordings, key = { it.id }) { r ->
                    RecordingRow(r, onDelete = {
                        RecordingStore.remove(r.id)
                        refresh()
                    })
                }
            }
        }
    }

    // EPG-Auswahl-Dialog
    if (mode == RecordMode.Epg) {
        EpgRecordDialog(vm, state, onDismiss = { mode = null }, onSaved = { mode = null; refresh() })
    }
    // Manueller Timer-Dialog
    if (mode == RecordMode.Manual) {
        ManualRecordDialog(vm, state, onDismiss = { mode = null }, onSaved = { mode = null; refresh() })
    }
}

private enum class RecordMode { Epg, Manual }

@Composable
private fun RecordingRow(r: RecordingStore.Recording, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.channelName, color = TextMid, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${fmtDateTime(r.startEpoch)} – ${fmtTime(r.endEpoch)}  ·  ${if (r.source == "epg") "EPG" else "manuell"}",
                color = TextMid, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text("🗑", color = Danger, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDelete).padding(8.dp))
    }
}

/** EPG-Auswahl: Kanal wählen → Sendungen anzeigen → Sendung anklicken = aufnehmen. */
@Composable
private fun EpgRecordDialog(vm: DesktopViewModel, state: AppState.Ready, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var selectedChannel by remember { mutableStateOf<LiveStream?>(null) }
    var pendingProgramme by remember { mutableStateOf<XmltvProgramme?>(null) }
    val programmes = remember(selectedChannel) {
        selectedChannel?.let { XmltvEpg.programmesFor(it.epgChannelId).orEmpty().ifEmpty { XmltvEpg.programmesForName(it.name) } }
            ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(if (selectedChannel == null) "Kanal wählen" else "Sendung wählen", color = TextHi) },
        text = {
            Column(Modifier.height(380.dp)) {
                if (selectedChannel == null) {
                    LazyColumn {
                        items(state.channels, key = { it.streamId }) { ch ->
                            Text(ch.name, color = TextHi, fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth().clickable { selectedChannel = ch }.padding(vertical = 8.dp))
                        }
                    }
                } else {
                    Text(selectedChannel!!.name, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { selectedChannel = null }.padding(bottom = 8.dp))
                    if (programmes.isEmpty()) {
                        Text("Keine EPG-Daten für diesen Kanal.", color = TextMid, fontSize = 13.sp)
                    } else {
                        // Nur laufende + zukünftige Sendungen anzeigen (vergangene sind nicht aufnehmbar)
                        val upcoming = programmes.filter { it.stop > System.currentTimeMillis() }
                        if (upcoming.isEmpty()) {
                            Text("Keine laufenden/zukünftigen Sendungen.", color = TextMid, fontSize = 13.sp)
                        } else {
                            LazyColumn {
                                items(upcoming) { p ->
                                    Column(Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0x22FFC94D))
                                        .clickable {
                                            pendingProgramme = p
                                        }
                                        .padding(10.dp)) {
                                        Text("${XmltvEpg.hhmm(p.start)} – ${XmltvEpg.hhmm(p.stop)}", color = Accent, fontSize = 12.sp)
                                        Text(p.title, color = TextHi, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = TextMid) } },
        dismissButton = { if (selectedChannel != null) TextButton(onClick = { selectedChannel = null }) { Text("Zurück", color = Accent) } }
    )

    // Bestätigungsfrage vor dem Aufnehmen
    pendingProgramme?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingProgramme = null },
            containerColor = Card,
            title = { Text("Diese Sendung aufnehmen?", color = TextHi) },
            text = {
                Column {
                    Text(p.title, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("${XmltvEpg.hhmm(p.start)} – ${XmltvEpg.hhmm(p.stop)} · ${selectedChannel?.name ?: ""}",
                        color = TextMid, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedChannel?.let { scheduleEpgRecording(vm, it, p) }
                    pendingProgramme = null
                    onSaved()
                }) { Text("Aufnehmen", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { pendingProgramme = null }) { Text("Abbrechen", color = TextMid) } }
        )
    }
}

/** Legt eine EPG-Aufnahme im Store an (Stream-URL wird sofort aufgelöst + gespeichert). */
private fun scheduleEpgRecording(vm: DesktopViewModel, ch: LiveStream, p: XmltvProgramme) {
    val url = vm.streamUrlFor(ch) ?: ""
    val dir = File(AppSettings.recordingsDir())
    val fname = "${safeName(ch.name)}_${safeName(p.title)}_${p.start}.ts"
    val rec = RecordingStore.Recording(
        id = RecordingStore.newId(),
        channelId = ch.streamId,
        channelName = ch.name,
        title = p.title,
        startEpoch = p.start,
        endEpoch = p.stop,
        source = "epg",
        outputPath = File(dir, fname).absolutePath,
        streamUrl = url
    )
    RecordingStore.add(rec)
}

/** Manueller Timer: Kanal + Startzeit + Dauer. */
@Composable
private fun ManualRecordDialog(vm: DesktopViewModel, state: AppState.Ready, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var channel by remember { mutableStateOf<LiveStream?>(null) }
    var channelPicker by remember { mutableStateOf(false) }
    var startHour by remember { mutableStateOf("20") }
    var startMin by remember { mutableStateOf("00") }
    var durationMin by remember { mutableStateOf("60") }
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Manueller Aufnahme-Timer", color = TextHi) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Kanal", color = TextMid, fontSize = 12.sp)
                Text(channel?.name ?: "Kanal wählen…", color = TextHi, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Card)
                        .clickable { channelPicker = true }.padding(12.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startHour, { startHour = it.filter(Char::isDigit).take(2) }, label = { Text("Stunde") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(startMin, { startMin = it.filter(Char::isDigit).take(2) }, label = { Text("Minute") }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(durationMin, { durationMin = it.filter(Char::isDigit).take(3) }, label = { Text("Dauer (Minuten)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(title, { title = it }, label = { Text("Titel (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ch = channel ?: return@TextButton
                val h = startHour.toIntOrNull() ?: return@TextButton
                val m = startMin.toIntOrNull() ?: return@TextButton
                val dur = durationMin.toIntOrNull() ?: return@TextButton
                if (dur <= 0) return@TextButton
                scheduleManualRecording(vm, ch, h, m, dur, title.ifBlank { ch.name })
                onSaved()
            }) { Text("Anlegen", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = TextMid) } }
    )

    if (channelPicker) {
        AlertDialog(
            onDismissRequest = { channelPicker = false },
            containerColor = Card,
            title = { Text("Kanal wählen", color = TextHi) },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(state.channels, key = { it.streamId }) { ch ->
                        Text(ch.name, color = TextHi, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable { channel = ch; channelPicker = false }.padding(vertical = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { channelPicker = false }) { Text("Schließen", color = TextMid) } }
        )
    }
}

private fun scheduleManualRecording(vm: DesktopViewModel, ch: LiveStream, hour: Int, minute: Int, durationMin: Int, title: String) {
    val now = System.currentTimeMillis()
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    var start = cal.timeInMillis
    if (start < now) start += 24 * 3600_000L // morgen, falls Zeit schon vorbei
    val end = start + durationMin * 60_000L
    val url = vm.streamUrlFor(ch) ?: ""
    val dir = File(AppSettings.recordingsDir())
    val fname = "${safeName(ch.name)}_${safeName(title)}_$start.ts"
    RecordingStore.add(RecordingStore.Recording(
        id = RecordingStore.newId(),
        channelId = ch.streamId,
        channelName = ch.name,
        title = title,
        startEpoch = start,
        endEpoch = end,
        source = "manual",
        outputPath = File(dir, fname).absolutePath,
        streamUrl = url
    ))
}

private fun safeName(s: String): String =
    s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)

private fun fmtDateTime(epoch: Long): String =
    SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault()).format(Date(epoch))

private fun fmtTime(epoch: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch))
