package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

fun qrCodeImageBitmap(content: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val info = org.jetbrains.skia.ImageInfo.makeN32Premul(size, size)
    val bmp = org.jetbrains.skia.Bitmap()
    bmp.allocPixels(info)
    val canvas = org.jetbrains.skia.Canvas(bmp)
    canvas.clear(0xFFFFFFFF.toInt())
    val paint = org.jetbrains.skia.Paint().apply { color = 0xFF10141F.toInt() }
    for (y in 0 until size) {
        for (x in 0 until size) {
            if (matrix[x, y]) {
                canvas.drawRect(
                    org.jetbrains.skia.Rect.makeXYWH(x.toFloat(), y.toFloat(), 1f, 1f),
                    paint
                )
            }
        }
    }
    return org.jetbrains.skia.Image.makeFromBitmap(bmp).toComposeImageBitmap()
}

@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val bmp = remember(content) { qrCodeImageBitmap(content) }
    Image(bitmap = bmp, contentDescription = "QR-Code zum Pairing", modifier = modifier)
}

@Composable
fun SettingsScreen(vm: DesktopViewModel) {
    val profiles = remember { mutableStateOf(ProfileStore.list()) }
    val active = remember { mutableStateOf(ProfileStore.active()) }
    var addDialog by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }
    var lineDialogFor by remember { mutableStateOf<Profile?>(null) }
    var reloadingLines by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Text(I18n.s("Einstellungen"), color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        // ---- Sprache (offene Liste: Deutsch, English, Polski, Türkçe, فارسی, اردو) ----
        Text(I18n.s("Sprache"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            I18n.alle.forEach { sp ->
                val aktiv = I18n.sprache.code == sp.code
                Text(
                    sp.label,
                    color = if (aktiv) AccentDark else TextHi,
                    fontSize = 13.sp,
                    fontWeight = if (aktiv) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                        .background(if (aktiv) Accent else Card)
                        .clickable { I18n.setze(sp) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // ---- Profile ----
        Text(I18n.s("Profile"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        profiles.value.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(if (p.id == active.value?.id) Card else Sidebar)
                    .clickable {
                        ProfileStore.setActive(p.id)
                        active.value = ProfileStore.active()
                        vm.reloadProfileState()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color(p.color)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = TextHi, fontSize = 15.sp)
                    // Panel-Line des Profils — Klick öffnet die Line-Auswahl
                    Text(
                        "Line: ${vm.lineLabelFor(p.id) ?: "Automatisch"}  ▾",
                        color = Accent, fontSize = 11.sp,
                        modifier = Modifier.clickable { lineDialogFor = p }
                    )
                }
                if (p.id == active.value?.id) {
                    Text(I18n.s("aktiv"), color = Accent, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { addDialog = true }) { Text(I18n.s("+ Profil hinzufügen"), color = Accent) }
            Spacer(Modifier.width(10.dp))
            // Lines neu vom Panel ziehen (z. B. nachdem im Panel eine Line
            // hinzugekommen ist). Verschlüsselte Lines -> Unlock-Screen.
            TextButton(
                enabled = !reloadingLines,
                onClick = {
                    reloadingLines = true
                    uiScope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            vm.pairAndLoad(CredStore.playerId(), CredStore.pairingPw())
                        }
                        reloadingLines = false
                    }
                }
            ) {
                Text(
                    if (reloadingLines) "lade Lines …" else "🔄 Lines vom Panel neu laden",
                    color = if (reloadingLines) TextMid else Accent
                )
            }
        }
        // Welche Lines kennt die App? (Labels aus dem Panel)
        val knownLines = vm.lines()
        Text(
            if (knownLines.isEmpty()) "Keine Lines geladen."
            else "Lines (${knownLines.size}): " + knownLines.joinToString(" · ") { it.second } +
                 "   — aktiv: ${vm.activeLineLabel.ifBlank { "—" }}",
            color = TextMid, fontSize = 12.sp
        )

        Spacer(Modifier.height(24.dp))

        // ---- Kindersicherung ----
        Text(I18n.s("Kindersicherung"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.s("FSK18-PIN:"), color = TextMid, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            if (ParentalControl.isEnabled()) {
                Text(I18n.s("gesetzt"), color = Color(0xFF6FCF9F), fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { ParentalControl.disable() }) { Text(I18n.s("Deaktivieren"), color = Danger) }
            } else {
                Text(I18n.s("nicht gesetzt"), color = TextMid, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { pinDialog = true }) { Text(I18n.s("PIN festlegen"), color = Accent) }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ---- Live-TV-Timeshift (Pause am Live-Kanal) ----
        Text(I18n.s("Live-TV-Pause (Timeshift)"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        var timeshiftEnabled by remember { mutableStateOf(AppSettings.timeshiftEnabled()) }
        var timeshiftDir by remember { mutableStateOf(AppSettings.timeshiftDir()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = timeshiftEnabled,
                onCheckedChange = { on ->
                    timeshiftEnabled = on
                    AppSettings.setTimeshiftEnabled(on)
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Color(0x55FFC94D))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (timeshiftEnabled) "aktiv — Stream wird auf die Platte gepuffert" else "aus",
                color = TextMid, fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(I18n.s("Aufnahmepfad:"), color = TextMid, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = timeshiftDir,
            onValueChange = { timeshiftDir = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(I18n.s("Verzeichnis für den Timeshift-Puffer")) }
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = {
            AppSettings.setTimeshiftDir(timeshiftDir.trim())
            timeshiftDir = AppSettings.timeshiftDir()
        }) { Text(I18n.s("Pfad speichern"), color = Accent) }

        Spacer(Modifier.height(16.dp))

        // ---- Aufnahme-Verzeichnis (dauerhafte Aufnahmen) ----
        Text(I18n.s("Aufnahme-Verzeichnis"), color = TextMid, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        var recDir by remember { mutableStateOf(AppSettings.recordingsDir()) }
        OutlinedTextField(
            value = recDir,
            onValueChange = { recDir = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(I18n.s("Zielordner für geplante Aufnahmen")) }
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = {
            AppSettings.setRecordingsDir(recDir.trim())
            recDir = AppSettings.recordingsDir()
        }) { Text(I18n.s("Pfad speichern"), color = Accent) }

        Spacer(Modifier.height(16.dp))

        // ---- Video-Decoding (HW/SW) ----
        Text(I18n.s("Video-Decoding"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        var decMode by remember { mutableStateOf(AppSettings.decodingMode()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Auto", "hw" to "Hardware (GPU)", "sw" to "Software (CPU)").forEach { (m, label) ->
                val active = decMode == m
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) Accent else Card,
                    modifier = Modifier.clickable {
                        decMode = m
                        AppSettings.setDecodingMode(m)
                    }
                ) {
                    Text(label, color = if (active) AccentDark else TextMid, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(I18n.s("Auto erkennt das System selbst (z. B. Hybrid-Grafik → Software). Nur bei Problemen manuell wählen."),
            color = TextMid, fontSize = 12.sp)

        Spacer(Modifier.height(24.dp))

        // ---- Stream-Puffer (Anti-Freeze, User-Vorgabe: Sekunden einstellbar) ----
        Text(I18n.s("Stream-Puffer"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        var cacheSecs by remember { mutableStateOf(AppSettings.cacheSeconds()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = cacheSecs.toFloat(),
                onValueChange = { v ->
                    cacheSecs = v.toInt()
                    AppSettings.setCacheSeconds(cacheSecs)
                },
                valueRange = 0f..30f,
                steps = 29,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0x33555555)
                )
            )
            Spacer(Modifier.width(14.dp))
            Text(
                if (cacheSecs <= 0) "aus" else "$cacheSecs s",
                color = if (cacheSecs <= 0) TextMid else Accent,
                fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Wie viele Sekunden des Streams vorgehalten werden — beugt Bild-Freezern bei schwacher Leitung vor. " +
                "Mehr Puffer = stabiler, aber mehr Verzögerung. 0 = sofortiger Start ohne Puffer. " +
                "Gilt für Live-TV und VOD (neustarten zum Übernehmen).",
            color = TextMid, fontSize = 12.sp
        )

        Spacer(Modifier.height(16.dp))

        // ---- Fenster im Vordergrund halten (Always-on-Top) ----
        Text(I18n.s("Fenster"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        var alwaysOnTop by remember { mutableStateOf(AppSettings.alwaysOnTop()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = alwaysOnTop,
                onCheckedChange = { on ->
                    alwaysOnTop = on
                    AppSettings.setAlwaysOnTop(on)
                    runCatching { FullscreenController.window?.isAlwaysOnTop = on }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Color(0x55FFC94D))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Im Vordergrund halten — bleibt über anderen Programmen (Windows/X11; unter Wayland regelt das der Compositor)",
                color = TextMid, fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---- Aufnahme-Daemon Autostart (optional) ----
        Text(I18n.s("Aufnahme-Daemon"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        var daemonAuto by remember { mutableStateOf(AppSettings.daemonAutostartEnabled()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = daemonAuto,
                onCheckedChange = { on ->
                    daemonAuto = on
                    AppSettings.setDaemonAutostartEnabled(on)
                    DaemonAutostart.setEnabled(on)
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Color(0x55FFC94D))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Beim Anmelden automatisch starten — geplante Aufnahmen laufen auch ohne App",
                color = TextMid, fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(24.dp))

        // ---- Verwaltung zurücksetzen ----
        Text(I18n.s("Verwaltung"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SettingButton("Ausgeblendete Kanäle wieder einblenden") {
            ChannelPrefs.unhideAll()
        }
        Spacer(Modifier.height(8.dp))
        SettingButton("Kategorie-Sortierung zurücksetzen (Live-TV)") {
            CategoryPrefs.unhideAll("live")
        }
        Spacer(Modifier.height(8.dp))
        SettingButton("Kategorie-Sortierung zurücksetzen (Filme)") {
            CategoryPrefs.unhideAll("movies")
        }
        Spacer(Modifier.height(8.dp))
        SettingButton("Kategorie-Sortierung zurücksetzen (Serien)") {
            CategoryPrefs.unhideAll("series")
        }

        Spacer(Modifier.height(24.dp))

        // ---- Player-Info ----
        Text(I18n.s("Player"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        // Player-ID + Passwort: Klick kopiert den Wert in die Zwischenablage
        // (für die Weitergabe an das Panel). Kurzes Feedback statt Dialog.
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        var copied by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(copied) {
            if (copied != null) {
                kotlinx.coroutines.delay(1600)
                copied = null
            }
        }
        val panelUrl = "https://panel.smartys.tech/pair?mac=${CredStore.playerId()}&pw=${CredStore.pairingPw()}"

        CopyRow(
            label = "Player-ID",
            value = CredStore.playerId(),
            hint = if (copied == "id") "✓ kopiert" else "klicken = kopieren",
            onCopy = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(CredStore.playerId()))
                copied = "id"
            }
        )
        CopyRow(
            label = "Passwort",
            value = CredStore.pairingPw(),
            hint = if (copied == "pw") "✓ kopiert" else "klicken = kopieren",
            onCopy = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(CredStore.pairingPw()))
                copied = "pw"
            }
        )
        Spacer(Modifier.height(14.dp))
        // QR-Code: scannen statt abtippen
        QrCode(
            panelUrl,
            modifier = Modifier.size(180.dp).clip(RoundedCornerShape(10.dp))
                .background(Color.White).padding(10.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(I18n.s("QR-Code mit dem Handy scannen"), color = TextMid, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        // Panel-Link: Klick öffnet die Panel-Seite im Browser
        Text(
            "Playlist verwalten → panel.smartys.tech",
            color = Accent, fontSize = 13.sp,
            modifier = Modifier.clickable {
                runCatching {
                    java.awt.Desktop.getDesktop().browse(java.net.URI("https://panel.smartys.tech"))
                }
            }
        )
    }

    // Profil hinzufügen
    if (addDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            containerColor = Card,
            title = { Text(I18n.s("Neues Profil"), color = TextHi) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(I18n.s("Name")) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        val p = ProfileStore.add(name)
                        ProfileStore.setActive(p.id)
                        profiles.value = ProfileStore.list()
                        active.value = ProfileStore.active()
                        vm.reloadProfileState()
                    }
                    addDialog = false
                }) { Text(I18n.s("Anlegen"), color = Accent) }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text(I18n.s("Abbrechen"), color = TextMid) } }
        )
    }

    // PIN festlegen
    if (pinDialog) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            containerColor = Card,
            title = { Text(I18n.s("FSK18-PIN festlegen"), color = TextHi) },
            text = {
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(4) },
                    singleLine = true, label = { Text(I18n.s("4-stelliger PIN")) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pin.length == 4) ParentalControl.setPin(pin)
                    pinDialog = false
                }) { Text(I18n.s("Speichern"), color = Accent) }
            },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text(I18n.s("Abbrechen"), color = TextMid) } }
        )
    }

    // Profil -> Panel-Line
    lineDialogFor?.let { prof ->
        AlertDialog(
            onDismissRequest = { lineDialogFor = null },
            containerColor = Card,
            title = { Text("Line für ${prof.name}", color = TextHi, fontSize = 18.sp) },
            text = {
                Column {
                    Text(I18n.s("Welche Line aus dem Panel nutzt dieses Profil?"),
                        color = TextMid, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    val current = ProfileStore.lineKey(prof.id)
                    // "Automatisch" = keine Bindung: alle Lines, erste Line aktiv
                    val autoSelected = current == null
                    Text(
                        (if (autoSelected) "✓ " else "") + "Automatisch (alle Lines)",
                        color = if (autoSelected) Accent else TextHi, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            vm.setProfileLine(prof.id, null)
                            lineDialogFor = null
                        }.padding(vertical = 10.dp)
                    )
                    vm.lines().forEach { (idx, label) ->
                        val isSel = current != null && vm.allProviders.getOrNull(idx)?.key == current
                        Text(
                            (if (isSel) "✓ " else "") + label,
                            color = if (isSel) Accent else TextHi, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.setProfileLine(prof.id, idx)
                                lineDialogFor = null
                            }.padding(vertical = 10.dp)
                        )
                    }
                    if (vm.lines().isEmpty()) {
                        Text(I18n.s("Keine Lines geladen — oben „Lines vom Panel neu laden“."),
                            color = Danger, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { lineDialogFor = null }) { Text(I18n.s("Schließen"), color = Accent) } }
        )
    }
}

@Composable
private fun SettingButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = TextHi, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(Card).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

/**
 * Wert-Zeile mit Klick-zum-Kopieren (Player-ID / Passwort).
 * Der eigentliche Wert steht fett da, rechts der Hinweis — damit man sofort
 * sieht, dass die Zeile klickbar ist und WAS in der Zwischenablage landet.
 */
@Composable
private fun CopyRow(label: String, value: String, hint: String, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onCopy)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label: ", color = TextMid, fontSize = 14.sp)
        Text(value, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            hint,
            color = if (hint.startsWith("✓")) Accent else TextMid,
            fontSize = 11.sp
        )
    }
}
