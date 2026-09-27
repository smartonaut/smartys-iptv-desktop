package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Canvas

// ---------- Live-Player ----------

@Composable
fun LivePlayerScreen(ch: LiveStream, url: String, vm: DesktopViewModel, onBack: () -> Unit) {
    val player = remember { VlcPlayer() }
    val canvas = remember { Canvas() }
    var inited by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SwingPanel(
            factory = {
                if (!inited) {
                    inited = true
                    player.videoCanvas = canvas
                    if (player.init()) {
                        player.play(url, isVod = false)
                    }
                }
                canvas
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay-Kopfleiste
        Row(
            Modifier.fillMaxWidth().background(Color(0xAA0B0E14)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Accent, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text(ch.name, color = TextHi, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            // Pause/Resume
            Text(if (paused) "▶" else "❚❚", color = TextHi, fontSize = 18.sp,
                modifier = Modifier.clickable {
                    paused = !paused
                    if (paused) player.pause() else player.resume()
                }.padding(horizontal = 10.dp))
        }
    }
}

// ---------- VOD-Player (Resume + Abspann-Erkennung + Auto-Advance) ----------

@Composable
fun VodPlayerScreen(vp: VodPlaying, vm: DesktopViewModel, onBack: () -> Unit) {
    val player = remember { VlcPlayer() }
    val canvas = remember { Canvas() }
    var inited by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var watcher by remember { mutableStateOf<CreditsWatcher?>(null) }
    val scope = rememberCoroutineScope()

    val startPos = remember(vp.key) { ResumeStore.position(vp.key) }

    DisposableEffect(Unit) {
        onDispose {
            // Fortsetzen-Position sichern
            ResumeStore.save(vp.key, player.currentPositionMs(), player.durationMs())
            watcher?.stop()
            player.release()
        }
    }

    // Positions-Polling + Abspann-Erkennung
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = player.currentPositionMs()
            durationMs = player.durationMs()
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SwingPanel(
            factory = {
                if (!inited) {
                    inited = true
                    player.videoCanvas = canvas
                    if (player.init()) {
                        val advance = {
                            vm.markWatched(vp.streamId)
                            vp.next?.invoke()
                        }
                        // Auto-Advance: Abspann-Erkennung (nur bei Serien mit Folge)
                        // Fallback: natürliches Ende des Streams.
                        player.onVodEnded = { advance() }
                        if (vp.next != null) {
                            val w = CreditsWatcher(player) { advance() }
                            watcher = w
                            scope.launch { w.run() }
                        }
                        player.play(vp.url, isVod = true)
                        if (startPos > 0) player.seekTo(startPos)
                    }
                }
                canvas
            },
            modifier = Modifier.fillMaxSize()
        )

        // Kopfleiste
        Row(
            Modifier.fillMaxWidth().background(Color(0xAA0B0E14)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Accent, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text(vp.title, color = TextHi, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(if (paused) "▶" else "❚❚", color = TextHi, fontSize = 18.sp,
                modifier = Modifier.clickable {
                    paused = !paused
                    if (paused) player.pause() else player.resume()
                }.padding(horizontal = 10.dp))
        }

        // Fortschritts-/Seek-Leiste
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xAA0B0E14)).padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            if (durationMs > 0) {
                Slider(
                    value = (positionMs.coerceAtMost(durationMs)).toFloat(),
                    onValueChange = { player.seekTo(it.toLong()) },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0x33555555)
                    )
                )
            } else {
                LinearProgressIndicator(
                    progress = { 0f },
                    color = Accent,
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text(fmt(positionMs), color = TextMid, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(fmt(durationMs), color = TextMid, fontSize = 11.sp)
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
    else String.format("%d:%02d", m, sec)
}
