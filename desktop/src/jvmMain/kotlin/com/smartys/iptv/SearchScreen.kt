package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MIN_QUERY = 2
private const val MAX_RESULTS = 50

/**
 * Universelle Suche über Kanäle, Filme und Serien.
 *
 * Alles liegt bereits im ViewModel-Speicher (Kanäle via AppState.Ready,
 * Filme/Serien werden beim ersten Öffnen nachgeladen) — die Suche ist reines
 * lokales Filtern, keine API-Calls pro Tastendruck. Treffer-Kappen: pro
 * Sektion MAX_RESULTS Zeilen, der Header zeigt die volle Trefferzahl.
 */
@Composable
fun SearchScreen(
    vm: DesktopViewModel,
    s: AppState.Ready,
    onPlayChannel: (LiveStream) -> Unit,
    onOpenMovie: (VodStream) -> Unit,
    onOpenSeries: (SeriesStream) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Filme/Serien nachladen, falls noch nicht geschehen — die Suche braucht
    // den Vollbestand. Läuft im Hintergrund, Kanaltreffer kommen sofort.
    LaunchedEffect(Unit) {
        if (vm.vodMovies.isEmpty()) vm.loadVod()
        if (vm.seriesShows.isEmpty()) vm.loadSeries()
        focusRequester.requestFocus()
    }

    val q = query.trim()
    val (channels, movies, shows) = remember(q, s.channels, vm.vodMovies, vm.seriesShows) {
        if (q.length < MIN_QUERY) Triple(emptyList(), emptyList(), emptyList())
        else Triple(
            s.channels.filter { it.name.contains(q, ignoreCase = true) },
            vm.vodMovies.filter { it.name.contains(q, ignoreCase = true) },
            vm.seriesShows.filter { it.name.contains(q, ignoreCase = true) }
        )
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Sender, Filme oder Serien suchen …", color = TextMid) },
            leadingIcon = { Text("🔍", color = TextMid, fontSize = 15.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .focusRequester(focusRequester),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextHi,
                unfocusedTextColor = TextHi,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Sidebar,
                cursorColor = Accent,
                focusedContainerColor = Sidebar,
                unfocusedContainerColor = Sidebar
            )
        )

        if ((vm.vodLoading && vm.vodMovies.isEmpty()) || vm.seriesShows.isEmpty()) {
            Text(
                "Filmliste wird noch geladen — Kanaltreffer erscheinen sofort.",
                color = TextMid, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        when {
            q.length < MIN_QUERY -> CenteredBox {
                Text(
                    "Mindestens $MIN_QUERY Zeichen tippen — sucht in " +
                        "${s.channels.size} Kanälen, ${vm.vodMovies.size} Filmen und ${vm.seriesShows.size} Serien.",
                    color = TextMid, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
            }

            channels.isEmpty() && movies.isEmpty() && shows.isEmpty() -> CenteredBox {
                Text("Keine Treffer für \"$q\"", color = TextMid, fontSize = 15.sp)
            }

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (channels.isNotEmpty()) {
                    item { SectionHeader("📺 Sender", channels.size) }
                    items(channels.take(MAX_RESULTS)) { ch ->
                        SearchResultRow(
                            badge = ch.num?.toString() ?: "•",
                            title = ch.name,
                            subtitle = ch.categoryName.ifBlank { "Live-TV" },
                            onClick = { onPlayChannel(ch) }
                        )
                    }
                    moreRow(channels.size)
                }
                if (movies.isNotEmpty()) {
                    item { SectionHeader("🎬 Filme", movies.size) }
                    items(movies.take(MAX_RESULTS)) { m ->
                        SearchResultRow(
                            poster = m.poster(),
                            tmdbId = m.tmdb,
                            title = m.name,
                            subtitle = m.categoryName.ifBlank { "Film" },
                            onClick = { onOpenMovie(m) }
                        )
                    }
                    moreRow(movies.size)
                }
                if (shows.isNotEmpty()) {
                    item { SectionHeader("📺 Serien", shows.size) }
                    items(shows.take(MAX_RESULTS)) { sh ->
                        SearchResultRow(
                            poster = sh.cover,
                            title = sh.name,
                            subtitle = sh.genre ?: sh.categoryName.ifBlank { "Serie" },
                            onClick = { onOpenSeries(sh) }
                        )
                    }
                    moreRow(shows.size)
                }
            }
        }
    }
}

/** "… weitere N" — Zeile, wenn eine Sektion mehr Treffer als MAX_RESULTS hat. */
private fun LazyListScope.moreRow(total: Int) {
    if (total > MAX_RESULTS) {
        item {
            Text(
                "… weitere ${total - MAX_RESULTS} Treffer — Suche weiter eingrenzen",
                color = TextMid, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Text(
        "$label  ·  $count Treffer",
        color = Accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp)
    )
}

/**
 * Ergebnis-Zeile: optional kleines Cover (Film/Serie) oder Nummern-Badge
 * (Kanal) + Titel + Untertitel. Ganze Zeile klickbar.
 */
@Composable
private fun SearchResultRow(
    badge: String? = null,
    poster: String? = null,
    tmdbId: String? = null,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(Sidebar)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (poster != null) {
            Poster(poster, title, Modifier.width(34.dp).height(50.dp), cornerRadius = 6, tmdbId = tmdbId)
            Spacer(Modifier.width(12.dp))
        } else if (badge != null) {
            Box(
                Modifier.width(34.dp).height(34.dp).clip(RoundedCornerShape(8.dp)).background(Bg),
                contentAlignment = Alignment.Center
            ) {
                Text(badge, color = Accent, fontSize = if (badge.length > 2) 11.sp else 14.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextMid, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}