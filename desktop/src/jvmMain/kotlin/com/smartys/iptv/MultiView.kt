package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Canvas

/**
 * Multi-View: bis zu 4 Live-Kanäle gleichzeitig im 2x2-Grid („perfekt für Sport").
 * Der aktive Kanal hat Ton, die anderen sind stumm. Klick auf eine Zelle macht sie aktiv.
 */
@Composable
fun MultiViewScreen(channels: List<LiveStream>, vm: DesktopViewModel, onBack: () -> Unit) {
    var selected by remember { mutableStateOf<List<LiveStream>>(emptyList()) }
    var started by remember { mutableStateOf(false) }
    var activeIdx by remember { mutableStateOf(0) }

    if (!started) {
        // Schritt 1: bis zu 4 Kanäle auswählen
        Column(Modifier.fillMaxSize().background(Bg)) {
            Row(
                Modifier.fillMaxWidth().background(Card).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", color = Accent, fontSize = 22.sp,
                    modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
                Text("Multi-View — Kanäle wählen (max. 4)", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${selected.size}/4", color = TextMid, fontSize = 14.sp)
            }
            LazyColumn {
                items(channels) { ch ->
                    val checked = selected.contains(ch)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = when {
                                checked -> selected - ch
                                selected.size >= 4 -> selected
                                else -> selected + ch
                            }
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (checked) "✓" else "○", color = if (checked) Accent else TextMid, fontSize = 18.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(ch.name, color = TextHi, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (selected.size >= 2) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "▶ ${selected.size} Kanäle anzeigen",
                        color = AccentDark,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Accent, RoundedCornerShape(8.dp))
                            .clickable { started = true }
                            .padding(horizontal = 28.dp, vertical = 14.dp)
                    )
                }
            }
        }
    } else {
        // Schritt 2: 2x2-Grid
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xAA0B0E14)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", color = Accent, fontSize = 22.sp,
                    modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
                Text("Multi-View (${selected.size})", color = TextHi, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", color = TextHi, fontSize = 18.sp,
                    modifier = Modifier.clickable { started = false }.padding(horizontal = 8.dp))
            }
            Column(Modifier.fillMaxSize().padding(4.dp)) {
                for (row in 0 until 2) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (col in 0 until 2) {
                            val idx = row * 2 + col
                            if (idx < selected.size) {
                                val ch = selected[idx]
                                val url = vm.streamUrlFor(ch) ?: ""
                                MultiViewCell(
                                    ch = ch,
                                    url = url,
                                    isActive = idx == activeIdx,
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp)
                                ) { activeIdx = idx }
                            } else {
                                Spacer(Modifier.weight(1f).fillMaxHeight().padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiViewCell(
    ch: LiveStream,
    url: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val player = remember(ch.streamId) { MpvPlayer() }
    val canvas = remember(ch.streamId) { Canvas().apply { background = java.awt.Color.BLACK } }
    var inited by remember(ch.streamId) { mutableStateOf(false) }

    // Audio-Routing: nur die aktive Zelle hat Ton.
    LaunchedEffect(isActive) {
        if (inited) player.setMuted(!isActive)
    }

    DisposableEffect(ch.streamId) {
        onDispose { player.release() }
    }

    Box(
        modifier
            .clickable(onClick = onClick)
            .background(Color.Black)
            .border(
                width = if (isActive) 3.dp else 1.dp,
                color = if (isActive) Accent else Color(0xFF1A2130)
            )
    ) {
        SwingPanel(
            factory = {
                if (!inited) {
                    player.videoCanvas = canvas
                    val start = {
                        if (player.init()) {
                            player.play(url, isVod = false)
                            player.setMuted(!isActive)
                        }
                    }
                    if (canvas.isDisplayable) {
                        inited = true
                        start()
                    } else {
                        canvas.addHierarchyListener { _ ->
                            if (!inited && canvas.isDisplayable) {
                                inited = true
                                start()
                            }
                        }
                    }
                }
                canvas
            },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color(0xAA0B0E14)).padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                ch.name,
                color = if (isActive) Accent else TextHi,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
