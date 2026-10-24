package com.smartys.iptv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------- Poster-Helper (Coil 3, mit Fehler-/Lade-Fallback) ----------

@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier, cornerRadius: Int = 10, tmdbId: String? = null) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    // TMDb-Poster-Fallback: kein eigenes Cover, aber tmdb-Id vorhanden -> nachladen.
    var fallbackUrl by remember(tmdbId) { mutableStateOf<String?>(null) }
    LaunchedEffect(tmdbId) {
        if (url.isNullOrBlank() && !tmdbId.isNullOrBlank()) {
            fallbackUrl = withContext(Dispatchers.IO) { TmdbMeta.posterUrl(tmdbId) }
        }
    }
    val resolved = if (url.isNullOrBlank()) fallbackUrl else url
    Box(modifier.clip(shape).background(Card)) {
        if (resolved.isNullOrBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(title, color = TextMid, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } else {
            AsyncImage(
                model = resolved,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ---------- Live-TV ----------

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun LiveTvScreen(vm: DesktopViewModel, state: AppState.Ready, onPlay: (LiveStream) -> Unit, onStop: () -> Unit = {}, floatingChannel: LiveStream? = null, onMultiView: () -> Unit = {}) {
    var selectedCat by remember { mutableStateOf<String?>(null) }
    var favMode by remember { mutableStateOf(false) }
    var sortMode by remember(vm.activeProfileId) { mutableStateOf(ChannelPrefs.sortMode()) }
    var channelMenuFor by remember { mutableStateOf<LiveStream?>(null) }
    var pinFor by remember { mutableStateOf<LiveStream?>(null) }

    // Kategorie-Verwaltung (User-Vorgabe: Sortierung + Ausblenden wie VOD):
    // Langdruck auf Kategorie -> Menü; catsVersion triggert Neu-Sortierung.
    var catMenuFor by remember { mutableStateOf<Category?>(null) }
    var catMenuMulti by remember { mutableStateOf(false) }
    var catsVersion by remember { mutableStateOf(0) }
    // Profilwechsel: Auswahl zurücksetzen und profilgebundene Kategorie-
    // Einstellungen (ausgeblendete Gruppen, Reihenfolge) neu lesen.
    LaunchedEffect(vm.activeProfileId) { selectedCat = null; favMode = false; catsVersion++ }
    val shownCats = remember(state.categories, catsVersion, vm.activeProfileId) {
        CategoryPrefs.applyTo("live", state.categories)
    }
    // Ausgeblendete Gruppen: deren Sender dürfen auch in "Alle Kanäle" nicht
    // auftauchen (vorher verschwand nur die Sidebar-Gruppe, die Sender blieben).
    val hiddenCatIds = remember(state.categories, catsVersion, vm.activeProfileId) {
        CategoryPrefs.hiddenIds("live", state.categories)
    }

    val applied = remember(state.channels, selectedCat, favMode, vm.favorites, sortMode, hiddenCatIds) {
        var list = when {
            favMode -> state.channels.filter { it.streamId in vm.favorites }
            selectedCat == null -> state.channels.filter { it.categoryId !in hiddenCatIds }
            else -> state.channels.filter { it.categoryId == selectedCat }
        }
        ChannelPrefs.apply(list)
    }

    Row(Modifier.fillMaxSize().background(Bg)) {
        // Kategorien-Sidebar
        LazyColumn(Modifier.width(250.dp).fillMaxHeight().background(Sidebar)) {
            item {
                Text("Alle Kanäle",
                    color = if (selectedCat == null && !favMode) Accent else TextMid,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { selectedCat = null; favMode = false }.padding(16.dp))
            }
            item {
                Text("Favoriten (${vm.favorites.size})",
                    color = if (favMode) Accent else TextMid,
                    fontWeight = if (favMode) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clickable { favMode = true; selectedCat = null }.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(shownCats) { cat ->
                Text(cat.name,
                    color = if (selectedCat == cat.id && !favMode) Accent else TextMid,
                    fontWeight = if (selectedCat == cat.id && !favMode) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { selectedCat = cat.id; favMode = false },
                        onLongClick = { catMenuMulti = false; catMenuFor = cat }
                    ).padding(horizontal = 16.dp, vertical = 8.dp))
            }
            item {
                Text("🎯 Gruppen markieren",
                    color = Accent, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clickable {
                        catMenuMulti = true
                        catMenuFor = shownCats.firstOrNull()
                    }.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            val hiddenCats = state.categories.size - shownCats.size
            if (hiddenCats > 0) {
                item {
                    Text(
                        "⚙ $hiddenCats ausgeblendet — alle einblenden",
                        color = TextMid, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            CategoryPrefs.unhideAll("live")
                            catsVersion++
                        }.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // Kanal-Liste
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        favMode -> "Favoriten"
                        selectedCat == null -> "Alle Kanäle"
                        else -> state.categories.firstOrNull { it.id == selectedCat }?.name ?: ""
                    },
                    color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${applied.size} Kanäle", color = TextMid, fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
                // Sortier-Button (Zyklus: Nummer → A–Z → Manuell)
                val label = when (sortMode) {
                    "name" -> "Sortieren: A–Z"
                    "manual" -> "Sortieren: Manuell"
                    else -> "Sortieren: Nummer"
                }
                Surface(
                    shape = RoundedCornerShape(8.dp), color = Card,
                    modifier = Modifier.clickable {
                        sortMode = when (sortMode) {
                            "num" -> "name"
                            "name" -> "manual"
                            else -> "num"
                        }
                        ChannelPrefs.setSortMode(sortMode)
                    }
                ) {
                    Text(label, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                Spacer(Modifier.width(8.dp))
                // Multi-View: bis zu 4 Kanäle gleichzeitig (Sport)
                Surface(
                    shape = RoundedCornerShape(8.dp), color = Card,
                    modifier = Modifier.clickable { onMultiView() }
                ) {
                    Text("▦ Multi-View", color = Accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }

            // Laufender mpv-Kanal (separates Fenster) — Stopp-Button
            floatingChannel?.let { fc ->
                Row(
                    Modifier.fillMaxWidth().background(Color(0x22FFC94D)).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("▶ ${fc.name} läuft im Player-Fenster", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(8.dp), color = Card,
                        modifier = Modifier.clickable { onStop() }
                    ) {
                        Text("■ Stopp", color = TextHi, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }

            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                items(applied, key = { it.streamId }) { ch ->
                    ChannelRow(ch, vm, isFav = ch.streamId in vm.favorites,
                        onToggleFav = { vm.toggleFavorite(ch.streamId) },
                        onPlay = {
                            if (ChannelPrefs.isLocked(ch.streamId)) pinFor = ch
                            else onPlay(ch)
                        }, onLongPress = { channelMenuFor = ch })
                }
            }
        }
    }

    // Kategorie-Langdruck-Menü (Sortierung/Ausblenden/Mehrfach-Markierung)
    catMenuFor?.let { cat ->
        CategoryMenuDialog(
            scope = "live",
            cat = cat,
            cats = state.categories,
            startMulti = catMenuMulti,
            onDismiss = { catMenuFor = null; catMenuMulti = false },
            onChanged = {
                catsVersion++
                if (selectedCat != null && CategoryPrefs.isHidden("live", selectedCat!!)) selectedCat = null
            }
        )
    }

    // Kanal-Langdruck-Menü
    channelMenuFor?.let { ch ->
        ChannelMenuDialog(ch,
            onDismiss = { channelMenuFor = null },
            onMoveUp = { ChannelPrefs.move(applied, ch.streamId, -1); channelMenuFor = null },
            onMoveDown = { ChannelPrefs.move(applied, ch.streamId, 1); channelMenuFor = null },
            onHide = { ChannelPrefs.hide(ch.streamId); channelMenuFor = null },
            onLock = { pinFor = ch; channelMenuFor = null })
    }

    // FSK18-PIN-Abfrage
    pinFor?.let { ch ->
        PinDialog(
            title = "FSK18 — ${ch.name}",
            onDismiss = { pinFor = null },
            onUnlock = {
                if (ParentalControl.verify(it)) {
                    ChannelPrefs.setLocked(ch.streamId, false)
                    pinFor = null
                    onPlay(ch)
                }
            },
            onSetPin = {
                ParentalControl.setPin(it)
                ChannelPrefs.setLocked(ch.streamId, true)
                pinFor = null
            })
    }
}

@Composable
private fun ChannelRow(ch: LiveStream, vm: DesktopViewModel, isFav: Boolean, onToggleFav: () -> Unit, onPlay: () -> Unit, onLongPress: () -> Unit) {
    val epg = vm.epgCache[ch.streamId]
    LaunchedEffect(ch.streamId) { vm.epgFor(ch) }
    // EPG-Ready beobachten: damit Now/Next sofort erscheint, sobald XMLTV/Fallback geladen sind.
    vm.xmltvReady.value
    vm.fallbackReady.value

    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onPlay, onLongClick = onLongPress)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Card), contentAlignment = Alignment.Center) {
            Text(ch.num?.toString() ?: "•", color = TextMid, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ChannelPrefs.isLocked(ch.streamId)) {
                    Text("🔒 ", color = Accent, fontSize = 13.sp)
                }
                Text(ch.name, color = TextHi, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Now/Next EPG
            val (now, next) = vm.nowNext(ch)
            now?.let { p ->
                Text("${XmltvEpg.hhmm(p.start)}  ${p.title}", color = Accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            next?.let { p ->
                Text("${XmltvEpg.hhmm(p.start)}  ${p.title}", color = TextMid, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Favoriten-Stern (separater Klick-Target)
        Text(
            if (isFav) "★" else "☆",
            color = if (isFav) Accent else TextMid,
            fontSize = 18.sp,
            modifier = Modifier.clickable(onClick = onToggleFav).padding(horizontal = 10.dp)
        )
    }
}

@Composable
fun CategoryMenuDialog(
    scope: String,
    cat: Category,
    cats: List<Category>,
    startMulti: Boolean = false,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    var mode by remember { mutableStateOf(if (startMulti) 1 else 0) }   // 0 = Einzel, 1 = Mehrfach
    var marked by remember { mutableStateOf(setOf(cat.id)) }
    var version by remember { mutableStateOf(0) }
    val visible = remember(cats, version) { CategoryPrefs.applyTo(scope, cats) }
    val hiddenCount = cats.size - visible.size

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = {
            Text(
                if (mode == 0) cat.name else "Gruppen markieren (${marked.size} gewählt)",
                color = TextHi, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            when (mode) {
                0 -> Column {
                    MenuItem("▲ Nach oben verschieben") {
                        CategoryPrefs.move(scope, cat.id, visible, -1); onChanged(); onDismiss()
                    }
                    MenuItem("▼ Nach unten verschieben") {
                        CategoryPrefs.move(scope, cat.id, visible, 1); onChanged(); onDismiss()
                    }
                    MenuItem("🎯 Mehrere Gruppen markieren") { marked = setOf(cat.id); mode = 1 }
                    MenuItem("🚫 Ausblenden") {
                        CategoryPrefs.hide(scope, cat.id); onChanged(); onDismiss()
                    }
                    if (hiddenCount > 0) MenuItem("👁 Alle ausgeblendeten einblenden") {
                        CategoryPrefs.unhideAll(scope); onChanged(); onDismiss()
                    }
                }
                else -> Column {
                    Text("Anklicken markiert. MARKIERT = BEHALTEN.", color = TextMid, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniBtn("✅ Alle markieren") { marked = visible.map { it.id }.toSet() }
                        MiniBtn("⬜ Aufheben") { marked = emptySet() }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniBtn("▲ Block nach oben") {
                            if (marked.isNotEmpty()) {
                                CategoryPrefs.moveBlock(scope, marked.toList(), cats, true); version++; onChanged()
                            }
                        }
                        MiniBtn("▼ Block nach unten") {
                            if (marked.isNotEmpty()) {
                                CategoryPrefs.moveBlock(scope, marked.toList(), cats, false); version++; onChanged()
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniBtn("🚫 Nur markierte behalten", red = true) {
                            val toHide = visible.filter { it.id !in marked }.map { it.id }
                            if (toHide.isNotEmpty()) { CategoryPrefs.hideMany(scope, toHide); version++; onChanged() }
                        }
                        MiniBtn("🚫 Markierte ausblenden", red = true) {
                            if (marked.isNotEmpty()) {
                                CategoryPrefs.hideMany(scope, marked.toList()); version++; onChanged()
                            }
                        }
                    }
                    if (hiddenCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        MiniBtn("👁 Alle einblenden") {
                            CategoryPrefs.unhideAll(scope); version++; onChanged()
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Markiert: ${marked.size} von ${visible.size}", color = TextMid, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    ) {
                        visible.forEach { c ->
                            val isMarked = c.id in marked
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isMarked) Card else Color.Transparent,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    .clickable { marked = if (isMarked) marked - c.id else marked + c.id }
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(if (isMarked) "☑" else "☐",
                                        color = if (isMarked) Accent else TextMid, fontSize = 16.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(c.name,
                                        color = if (isMarked) TextHi else TextMid,
                                        fontSize = 14.sp,
                                        fontWeight = if (isMarked) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (mode == 1) "✓ Fertig" else "Schließen", color = Accent)
            }
        },
        dismissButton = if (mode == 1) {
            { TextButton(onClick = { marked = setOf(cat.id); mode = 0 }) { Text("← Zurück", color = TextMid) } }
        } else null
    )
}

@Composable
private fun MiniBtn(label: String, red: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (red) Color(0xFF2A1A1A) else Card,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(label, color = if (red) Color(0xFFFF9B9B) else TextHi, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelMenuDialog(
    ch: LiveStream,
    onDismiss: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHide: () -> Unit,
    onLock: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(ch.name, color = TextHi, fontSize = 18.sp) },
        text = {
            Column {
                MenuItem("Nach oben verschieben", onMoveUp)
                MenuItem("Nach unten verschieben", onMoveDown)
                MenuItem("Ausblenden", onHide)
                MenuItem(if (ChannelPrefs.isLocked(ch.streamId)) "FSK18-Sperre aufheben" else "FSK18 sperren (PIN)", onLock)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen", color = Accent) } }
    )
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(label, color = TextHi, fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp))
}

@Composable
fun PinDialog(
    title: String,
    onDismiss: () -> Unit,
    onUnlock: (String) -> Unit,
    onSetPin: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val hasPin = ParentalControl.isEnabled()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(title, color = TextHi, fontSize = 18.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (hasPin) "PIN eingeben:" else "Noch kein PIN gesetzt. Neuen PIN festlegen:",
                    color = TextMid, fontSize = 14.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(4) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 22.sp, textAlign = TextAlign.Center, color = TextHi)
                )
                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text("Falsche PIN", color = Danger, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pin.length != 4) return@TextButton
                if (hasPin) {
                    if (ParentalControl.verify(pin)) onUnlock(pin) else { error = true; pin = "" }
                } else {
                    onSetPin(pin)
                }
            }) { Text("OK", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = TextMid) } }
    )
}

// ---------- Filme (VOD) ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoviesScreen(vm: DesktopViewModel, onOpenSeries: (SeriesStream) -> Unit, onOpenMovie: (VodStream) -> Unit) {
    LaunchedEffect(Unit) { if (vm.vodMovies.isEmpty()) vm.loadVod() }

    var selectedCat by remember { mutableStateOf<String?>(null) }
    var catMenuFor by remember { mutableStateOf<Category?>(null) }
    var catsVersion by remember { mutableStateOf(0) }
    LaunchedEffect(vm.activeProfileId) { selectedCat = null; catsVersion++ }
    val shownCats = remember(vm.vodCategories, catsVersion, vm.activeProfileId) {
        CategoryPrefs.applyTo("movies", vm.vodCategories)
    }
    val hiddenCatIds = remember(vm.vodCategories, catsVersion, vm.activeProfileId) {
        CategoryPrefs.hiddenIds("movies", vm.vodCategories)
    }
    val applied = remember(vm.vodMovies, selectedCat, hiddenCatIds) {
        if (selectedCat == null) vm.vodMovies.filter { it.categoryId !in hiddenCatIds }
        else vm.vodMovies.filter { it.categoryId == selectedCat }
    }

    if (vm.vodLoading && vm.vodMovies.isEmpty()) {
        CenteredBox { CircularProgressIndicator(color = Accent) }
        return
    }
    vm.vodError?.let { err ->
        CenteredBox { Text(err, color = Danger, fontSize = 14.sp) }
        return
    }

    Row(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(Modifier.width(250.dp).fillMaxHeight().background(Sidebar)) {
            item {
                Text("Alle Filme",
                    color = if (selectedCat == null) Accent else TextMid,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { selectedCat = null }.padding(16.dp))
            }
            items(shownCats) { cat ->
                Text(cat.name,
                    color = if (selectedCat == cat.id) Accent else TextMid,
                    fontWeight = if (selectedCat == cat.id) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { selectedCat = cat.id },
                        onLongClick = { catMenuFor = cat }
                    ).padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (vm.vodCategories.size > shownCats.size) {
                item {
                    Text("⚙ ${vm.vodCategories.size - shownCats.size} ausgeblendet — verwalten",
                        color = TextMid, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            catMenuFor = vm.vodCategories.firstOrNull()
                        }.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
        Box(Modifier.weight(1f).padding(16.dp)) {
            if (applied.isEmpty()) {
                CenteredBox { Text("Keine Filme in dieser Kategorie", color = TextMid) }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(applied, key = { it.streamId }) { m ->
                        Column(Modifier.fillMaxWidth().clickable { onOpenMovie(m) }) {
                            Poster(m.poster(), m.name, Modifier.fillMaxWidth().aspectRatio(2f / 3f), tmdbId = m.tmdb)
                            Spacer(Modifier.height(8.dp))
                            Text(m.name, color = TextHi, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            m.rating?.let {
                                Text("★ $it", color = Accent, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    catMenuFor?.let { cat ->
        CategoryMenuDialog(
            scope = "movies", cat = cat, cats = vm.vodCategories,
            onDismiss = { catMenuFor = null },
            onChanged = { catsVersion++; if (selectedCat != null && CategoryPrefs.isHidden("movies", selectedCat!!)) selectedCat = null }
        )
    }
}

// ---------- Film-Detail (Beschreibung + Resume/von-vorne + Trailer) ----------

/** Kleine Plakette für eine Bewertungsquelle (IMDb / Rotten Tomatoes / Metacritic). */
@Composable
private fun RatingChip(quelle: String, wert: String, farbe: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(farbe.copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(quelle, color = farbe, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(wert, color = TextHi, fontSize = 12.sp)
    }
}

@Composable
fun MovieDetailScreen(vm: DesktopViewModel, m: VodStream, onBack: () -> Unit, onPlay: (Long?) -> Unit) {
    var meta by remember { mutableStateOf<TmdbMeta.MovieMeta?>(null) }
    var metaLoading by remember { mutableStateOf(m.tmdb != null) }
    var ratings by remember { mutableStateOf<TmdbMeta.Ratings?>(null) }

    LaunchedEffect(m.tmdb) {
        if (m.tmdb != null) {
            vm.fetchTmdb(m.tmdb) { result ->
                meta = result
                metaLoading = false
            }
        }
    }

    // Zusatznoten brauchen die IMDb-Kennung aus den TMDB-Daten
    LaunchedEffect(meta?.imdbId) {
        ratings = withContext(Dispatchers.IO) { TmdbMeta.fetchRatings(meta?.imdbId) }
    }

    // Resume-Position (für den "Fortsetzen"-Button)
    val resumePos = remember(m.streamId) { ResumeStore.position("vod:movie:${m.streamId}") }

    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text(m.name, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth()) {
            // Poster links
            Poster(m.poster(), m.name, Modifier.width(200.dp).aspectRatio(2f / 3f), tmdbId = m.tmdb)

            Spacer(Modifier.width(24.dp))

            // Infos + Buttons rechts
            Column(Modifier.weight(1f)) {
                // Meta-Zeile
                if (m.rating != null || m.tmdb != null || m.categoryName.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        m.rating?.let { Text("★ $it", color = Accent, fontSize = 14.sp) }
                        m.categoryName.takeIf { it.isNotBlank() }?.let { Text(it, color = TextMid, fontSize = 13.sp) }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Bewertungen: TMDB-Note plus OMDb-Plaketten (IMDb / RT / MC)
                val tmdbNote = meta?.voteAverage
                if (tmdbNote != null) {
                    val stimmen = meta?.voteCount
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("★", color = Accent, fontSize = 15.sp)
                        Text(
                            String.format(java.util.Locale.GERMAN, "%.1f", tmdbNote).replace('.', ','),
                            color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                        Text("/ 10", color = TextMid, fontSize = 12.sp)
                        if (stimmen != null && stimmen > 0) {
                            Text("· ${String.format("%,d", stimmen)} Stimmen", color = TextMid, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                ratings?.takeIf { it.hatWerte }?.let { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.imdb?.let { RatingChip("IMDb", it, Accent) }
                        r.rottenTomatoes?.let { RatingChip("RT", it, Danger) }
                        r.metacritic?.let { RatingChip("MC", it, Color(0xFF4ECDC4)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Beschreibung (TMDB overview oder Fallback)
                if (metaLoading) {
                    Text("Beschreibung lädt…", color = TextMid, fontSize = 13.sp)
                } else {
                    val desc = meta?.overview
                    if (desc != null) {
                        Text(desc, color = TextHi, fontSize = 14.sp, lineHeight = 20.sp)
                    } else {
                        Text("Keine Beschreibung verfügbar.", color = TextMid, fontSize = 13.sp)
                    }
                    meta?.let { mm ->
                        if (mm.genres.isNotEmpty() || mm.runtime != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                mm.genres.joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = TextMid, fontSize = 12.sp)
                                }
                                mm.runtime?.let { Text("${it} min", color = TextMid, fontSize = 12.sp) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Play-Buttons
                val url = vm.creds?.movieUrl(m.streamId, m.containerExtension ?: "mp4")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Fortsetzen (nur wenn Resume-Position > 0)
                    if (resumePos > 0) {
                        Button(
                            onClick = { onPlay(null) },  // null = auto-resume
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentDark)
                        ) {
                            Text("▶ Weiter schauen (${fmtPos(resumePos)})", fontWeight = FontWeight.Bold)
                        }
                    }
                    // Von vorne
                    Button(
                        onClick = { onPlay(0L) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (resumePos > 0) Card else Accent,
                            contentColor = if (resumePos > 0) TextHi else AccentDark
                        )
                    ) {
                        Text("Von vorne abspielen", fontWeight = FontWeight.Bold)
                    }
                    // Herunterladen (User-Vorgabe): kompletter Film als Datei —
                    // läuft mit Fortschritt und taucht im Aufnahmen-Tab auf.
                    val dlId = "vod:movie:${m.streamId}"
                    val dlState = VodDownloader.items.firstOrNull { it.id == dlId }
                    when (dlState?.status) {
                        "running" -> OutlinedButton(onClick = { VodDownloader.pause(dlId) }) {
                            Text("⏳ Herunterladen … ${(dlState.progress * 100).toInt()} % — pausieren", color = TextHi)
                        }
                        "paused" -> OutlinedButton(onClick = { VodDownloader.resume(dlId) }) {
                            Text("▶ Download fortsetzen", color = TextHi)
                        }
                        "complete" -> Text(
                            "✓ Heruntergeladen — im Tab Aufnahmen", color = Color(0xFF6FCF9F), fontSize = 13.sp
                        )
                        "error" -> OutlinedButton(onClick = {
                            if (url != null) VodDownloader.start(dlId, m.name, url, m.containerExtension ?: "mp4")
                        }) {
                            Text("⚠ Download fehlgeschlagen — erneut versuchen", color = TextHi)
                        }
                        else -> OutlinedButton(onClick = {
                            if (url != null) VodDownloader.start(dlId, m.name, url, m.containerExtension ?: "mp4")
                        }) {
                            Text("⬇ Herunterladen", color = TextHi)
                        }
                    }
                    // Trailer (wenn vorhanden)
                    if (m.hasTrailer()) {
                        OutlinedButton(onClick = {
                            // YouTube-Trailer im Browser öffnen
                            val vid = m.trailer
                            if (!vid.isNullOrBlank()) {
                                runCatching {
                                    java.awt.Desktop.getDesktop().browse(
                                        java.net.URI("https://www.youtube.com/watch?v=$vid")
                                    )
                                }
                            }
                        }) {
                            Text("▶ Trailer ansehen", color = TextHi)
                        }
                    }
                }
            }
        }
    }
}

private fun fmtPos(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s % 60)
    else String.format("%d:%02d", m, s % 60)
}

// ---------- Serien ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesScreen(vm: DesktopViewModel, onOpen: (SeriesStream) -> Unit) {
    LaunchedEffect(Unit) { if (vm.seriesShows.isEmpty()) vm.loadSeries() }

    var selectedCat by remember { mutableStateOf<String?>(null) }
    var catMenuFor by remember { mutableStateOf<Category?>(null) }
    var catsVersion by remember { mutableStateOf(0) }
    LaunchedEffect(vm.activeProfileId) { selectedCat = null; catsVersion++ }
    val shownCats = remember(vm.seriesCategories, catsVersion, vm.activeProfileId) {
        CategoryPrefs.applyTo("series", vm.seriesCategories)
    }
    val hiddenCatIds = remember(vm.seriesCategories, catsVersion, vm.activeProfileId) {
        CategoryPrefs.hiddenIds("series", vm.seriesCategories)
    }
    val applied = remember(vm.seriesShows, selectedCat, hiddenCatIds) {
        if (selectedCat == null) vm.seriesShows.filter { it.categoryId !in hiddenCatIds }
        else vm.seriesShows.filter { it.categoryId == selectedCat }
    }

    Row(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(Modifier.width(250.dp).fillMaxHeight().background(Sidebar)) {
            item {
                Text("Alle Serien",
                    color = if (selectedCat == null) Accent else TextMid,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { selectedCat = null }.padding(16.dp))
            }
            items(shownCats) { cat ->
                Text(cat.name,
                    color = if (selectedCat == cat.id) Accent else TextMid,
                    fontWeight = if (selectedCat == cat.id) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { selectedCat = cat.id },
                        onLongClick = { catMenuFor = cat }
                    ).padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (vm.seriesCategories.size > shownCats.size) {
                item {
                    Text("⚙ ${vm.seriesCategories.size - shownCats.size} ausgeblendet — verwalten",
                        color = TextMid, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            catMenuFor = vm.seriesCategories.firstOrNull()
                        }.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
        Box(Modifier.weight(1f).padding(16.dp)) {
            if (applied.isEmpty()) {
                CenteredBox { Text("Keine Serien in dieser Kategorie", color = TextMid) }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(applied, key = { it.seriesId }) { s ->
                        Column(Modifier.fillMaxWidth().clickable { onOpen(s) }) {
                            Poster(s.cover, s.name, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                            Spacer(Modifier.height(8.dp))
                            Text(s.name, color = TextHi, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    catMenuFor?.let { cat ->
        CategoryMenuDialog(
            scope = "series", cat = cat, cats = vm.seriesCategories,
            onDismiss = { catMenuFor = null },
            onChanged = { catsVersion++; if (selectedCat != null && CategoryPrefs.isHidden("series", selectedCat!!)) selectedCat = null }
        )
    }
}

// ---------- Serien-Detail (Staffeln + Episoden + Download) ----------

/** Episoden aus der Xtream-Antwort extrahieren. WICHTIG: Die eigentlichen
 *  Episoden stehen im `episodes`-Map (Staffelnummer -> Episoden). Das
 *  `seasons`-Feld enthält nur Staffel-METADATEN (Cover, episode_count),
 *  KEINE Episoden — deshalb immer das Map nutzen. */
private fun SeriesInfo?.toSeasons(): List<SeriesSeason> {
    val self = this ?: return emptyList()
    return self.episodes.orEmpty().entries
        .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
        .map { (season, list) ->
            SeriesSeason(seasonNumber = season.toIntOrNull(),
                         name = "Staffel $season", episodes = list)
        }
}

@Composable
fun SeriesDetailScreen(
    vm: DesktopViewModel,
    show: SeriesStream,
    onBack: () -> Unit,
    onPlayEpisode: (List<SeriesEpisode>, Int, Long?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SeriesInfo?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(show.seriesId) {
        loading = true
        info = vm.fetchSeriesInfo(show.seriesId)
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(Sidebar).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Accent, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp))
            Text(show.name, color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Row(Modifier.fillMaxSize()) {
            // Cover + Beschreibung + Start-Buttons
            Column(Modifier.width(240.dp).padding(20.dp)) {
                Poster(show.cover, show.name, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                Spacer(Modifier.height(14.dp))
                show.plot?.let { Text(it, color = TextMid, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(16.dp))

                // Start-Buttons: "Weiter schauen" auf die zuletztgesehene
                // Episode (Auto-Resume) oder "Von vorne" ab Staffel 1 Folge 1.
                val last = remember(show.seriesId, loading) { ResumeStore.seriesLast(show.seriesId) }
                val allEps = remember(info) { info.toSeasons().flatMap { it.episodes.orEmpty() } }
                if (loading) {
                    // noch keine Episodendaten — keine Buttons
                } else if (allEps.isEmpty()) {
                    Text("Keine Episoden gefunden", color = TextMid, fontSize = 13.sp)
                } else if (last != null) {
                    val idx = allEps.indexOfFirst { it.id == last.first }
                    if (idx >= 0) {
                        val epTitle = allEps[idx].title ?: "Episode ${allEps[idx].episodeNum}"
                        Button(
                            onClick = { onPlayEpisode(allEps, idx, null) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentDark),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "▶ Weiter schauen\n${epTitle} · ${fmtPos(last.second)}",
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { onPlayEpisode(allEps, 0, 0L) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Von vorne abspielen", color = TextHi, fontSize = 13.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = { onPlayEpisode(allEps, 0, 0L) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("▶ Erste Episode abspielen", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // Staffeln + Episoden
            if (loading) {
                CenteredBox { CircularProgressIndicator(color = Accent) }
            } else {
                val seasons = info.toSeasons()
                if (seasons.isEmpty()) {
                    CenteredBox { Text("Keine Episoden gefunden", color = TextMid) }
                } else {
                    LazyColumn(Modifier.weight(1f).padding(end = 20.dp, top = 20.dp, bottom = 20.dp)) {
                        seasons.forEach { season ->
                            item {
                                Text(season.name ?: "Staffel ${season.seasonNumber ?: ""}",
                                    color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 10.dp))
                            }
                            items(season.episodes.orEmpty(), key = { it.id }) { ep ->
                                val all = seasons.flatMap { it.episodes.orEmpty() }
                                val idx = all.indexOfFirst { it.id == ep.id }
                                EpisodeRow(vm, show, ep, onPlay = { onPlayEpisode(all, idx, null) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(vm: DesktopViewModel, show: SeriesStream, ep: SeriesEpisode, onPlay: () -> Unit) {
    val dlId = "series:${ep.id}"
    val dlState = VodDownloader.items.firstOrNull { it.id == dlId }
    val epTitle = ep.title ?: "Episode ${ep.episodeNum}"
    val scope = rememberCoroutineScope()

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(Card)
            .clickable(onClick = onPlay).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("${ep.episodeNum ?: ""}  $epTitle".trimStart(), color = TextHi, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ep.rating?.let { Text("★ $it", color = Accent, fontSize = 11.sp) }
        }
        // Download-Button
        val c = vm.creds
        val url = c?.seriesUrl(ep.id, ep.containerExtension ?: "mp4")
        if (url != null) {
            when (dlState?.status) {
                "running" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(20.dp))
                    Text("${(dlState.progress * 100).toInt()}%", color = TextMid, fontSize = 11.sp)
                }
                "paused" -> Text("▶", color = Accent, fontSize = 18.sp, modifier = Modifier.clickable { VodDownloader.resume(dlId) }.padding(6.dp))
                "complete" -> Text("✓", color = Color(0xFF6FCF9F), fontSize = 18.sp)
                "error" -> Text("⚠", color = Danger, fontSize = 18.sp,
                    modifier = Modifier.clickable { VodDownloader.start(dlId, "$show.name — $epTitle", url, ep.containerExtension ?: "mp4") }.padding(6.dp))
                else -> Text("⬇", color = TextMid, fontSize = 18.sp,
                    modifier = Modifier.clickable {
                        VodDownloader.start(dlId, "$show.name — $epTitle", url, ep.containerExtension ?: "mp4")
                    }.padding(6.dp))
            }
        }
    }
}

// ---------- Downloads-Tab ----------

/** Größe menschenlesbar (GB/MB). */
private fun fmtGroesse(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.GERMAN, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(java.util.Locale.GERMAN, "%.0f MB", bytes / 1_000_000.0)
    else -> "${bytes / 1000} KB"
}

/**
 * Speicheranzeige über den Downloads: frei, gesamt, davon belegt und der
 * Anteil der Downloads. Parität zur iOS-App (dort mit 3-GB-Warnung, hier
 * mit 5 GB, weil Desktop-Platten mehr Luft haben).
 */
@Composable
private fun SpeicherZeile() {
    var frei by remember { mutableStateOf(0L) }
    var gesamt by remember { mutableStateOf(0L) }
    var downloadsBelegt by remember { mutableStateOf(0L) }

    LaunchedEffect(VodDownloader.items.size) {
        withContext(Dispatchers.IO) {
            val ordner = VodDownloader.downloadsDir()
            frei = ordner.usableSpace
            gesamt = ordner.totalSpace
            downloadsBelegt = VodDownloader.items.sumOf { it.downloadedBytes }
        }
    }

    if (gesamt <= 0) return

    val belegt = gesamt - frei
    val anteil = (belegt.toFloat() / gesamt.toFloat()).coerceIn(0f, 1f)
    val knapp = frei < 5_000_000_000L

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speicher", color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "${fmtGroesse(frei)} frei von ${fmtGroesse(gesamt)}",
                color = if (knapp) Danger else TextMid, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        // Belegungsbalken
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
            Box(
                Modifier.fillMaxWidth(anteil).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (knapp) Danger else Accent)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text("Belegt: ${fmtGroesse(belegt)}", color = TextMid, fontSize = 11.sp)
            Spacer(Modifier.width(16.dp))
            Text("davon Downloads: ${fmtGroesse(downloadsBelegt)}", color = TextMid, fontSize = 11.sp)
        }
        if (knapp) {
            Spacer(Modifier.height(6.dp))
            Text("Wenig Platz — alte Downloads löschen.", color = Danger, fontSize = 11.sp)
        }
    }
}

@Composable
fun DownloadsScreen(vm: DesktopViewModel, onPlay: (java.io.File, String, String) -> Unit) {
    LaunchedEffect(Unit) { VodDownloader.load() }
    var deleteConfirmFor by remember { mutableStateOf<DownloadItem?>(null) }

    val complete = VodDownloader.items.filter { it.status == "complete" }

    Column(Modifier.fillMaxSize().background(Bg).padding(20.dp)) {
        Text("Offline-Downloads", color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        // Speicheranzeige (Parität zur iOS-App): frei, belegt, Download-Anteil
        SpeicherZeile()
        Spacer(Modifier.height(14.dp))

        if (complete.isEmpty()) {
            CenteredBox {
                Text("Noch keine Downloads.\nFilme und Serien-Episoden kannst du in den jeweiligen Bereichen herunterladen.",
                    color = TextMid, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(complete, key = { it.id }) { d ->
                    val sizeMb = d.downloadedBytes / (1024.0 * 1024.0)
                    val file = VodDownloader.localFile(d.id)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, color = TextHi, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(String.format("%.0f MB", sizeMb), color = TextMid, fontSize = 12.sp)
                        }
                        if (file != null) {
                            Text("▶", color = Accent, fontSize = 20.sp,
                                modifier = Modifier.clickable { onPlay(file, d.title, d.id) }.padding(horizontal = 10.dp))
                        }
                        Text("🗑", color = Danger, fontSize = 18.sp,
                            modifier = Modifier.clickable { deleteConfirmFor = d }.padding(horizontal = 10.dp))
                    }
                }
            }
        }
    }

    deleteConfirmFor?.let { d ->
        AlertDialog(
            onDismissRequest = { deleteConfirmFor = null },
            containerColor = Card,
            title = { Text("Download löschen?", color = TextHi) },
            text = { Text(d.title, color = TextMid) },
            confirmButton = {
                TextButton(onClick = { VodDownloader.delete(d.id); deleteConfirmFor = null }) {
                    Text("Löschen", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmFor = null }) { Text("Abbrechen", color = TextMid) }
            }
        )
    }
}
