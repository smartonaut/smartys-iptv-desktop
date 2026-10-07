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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: DesktopViewModel) {
    val profiles = remember { mutableStateOf(ProfileStore.list()) }
    val active = remember { mutableStateOf(ProfileStore.active()) }
    var addDialog by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Text("Einstellungen", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        // ---- Profile ----
        Text("Profile", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                Text(p.name, color = TextHi, fontSize = 15.sp)
                if (p.id == active.value?.id) {
                    Spacer(Modifier.weight(1f))
                    Text("aktiv", color = Accent, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row {
            TextButton(onClick = { addDialog = true }) { Text("+ Profil hinzufügen", color = Accent) }
        }

        Spacer(Modifier.height(24.dp))

        // ---- Kindersicherung ----
        Text("Kindersicherung", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FSK18-PIN:", color = TextMid, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            if (ParentalControl.isEnabled()) {
                Text("gesetzt", color = Color(0xFF6FCF9F), fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { ParentalControl.disable() }) { Text("Deaktivieren", color = Danger) }
            } else {
                Text("nicht gesetzt", color = TextMid, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { pinDialog = true }) { Text("PIN festlegen", color = Accent) }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ---- Live-TV-Timeshift (Pause am Live-Kanal) ----
        Text("Live-TV-Pause (Timeshift)", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
        Text("Aufnahmepfad:", color = TextMid, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = timeshiftDir,
            onValueChange = { timeshiftDir = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Verzeichnis für den Timeshift-Puffer") }
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = {
            AppSettings.setTimeshiftDir(timeshiftDir.trim())
            timeshiftDir = AppSettings.timeshiftDir()
        }) { Text("Pfad speichern", color = Accent) }

        Spacer(Modifier.height(16.dp))

        // ---- Aufnahme-Verzeichnis (dauerhafte Aufnahmen) ----
        Text("Aufnahme-Verzeichnis", color = TextMid, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        var recDir by remember { mutableStateOf(AppSettings.recordingsDir()) }
        OutlinedTextField(
            value = recDir,
            onValueChange = { recDir = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Zielordner für geplante Aufnahmen") }
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = {
            AppSettings.setRecordingsDir(recDir.trim())
            recDir = AppSettings.recordingsDir()
        }) { Text("Pfad speichern", color = Accent) }

        Spacer(Modifier.height(16.dp))

        // ---- Aufnahme-Daemon Autostart (optional) ----
        Text("Aufnahme-Daemon", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
        Text("Verwaltung", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SettingButton("Ausgeblendete Kanäle wieder einblenden") {
            ChannelPrefs.unhideAll()
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
        Text("Player", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Player-ID: ${CredStore.playerId()}", color = TextMid, fontSize = 14.sp)
        Text("Passwort: ${CredStore.pairingPw()}", color = TextMid, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Playlist verwalten: panel.smartys.tech",
            color = TextMid, fontSize = 13.sp
        )
    }

    // Profil hinzufügen
    if (addDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            containerColor = Card,
            title = { Text("Neues Profil", color = TextHi) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name") }
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
                }) { Text("Anlegen", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Abbrechen", color = TextMid) } }
        )
    }

    // PIN festlegen
    if (pinDialog) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            containerColor = Card,
            title = { Text("FSK18-PIN festlegen", color = TextHi) },
            text = {
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(4) },
                    singleLine = true, label = { Text("4-stelliger PIN") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pin.length == 4) ParentalControl.setPin(pin)
                    pinDialog = false
                }) { Text("Speichern", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text("Abbrechen", color = TextMid) } }
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
