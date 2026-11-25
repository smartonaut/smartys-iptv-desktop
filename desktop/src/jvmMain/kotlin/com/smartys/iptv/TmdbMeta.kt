package com.smartys.iptv

/**
 * TMDB-Metadaten (Film-Beschreibungen, Genres, Laufzeit) für die VOD-Detailseiten.
 * Identisch zur Android-App: KEIN API-Key im Client, Anfrage läuft durchs Backend.
 */
object TmdbMeta {

    private const val BASE = "https://panel.smartys.tech/api/v1/tmdb/movie/"

    data class MovieMeta(
        val overview: String?,
        val runtime: Int?,
        val genres: List<String>,
        val tagline: String?,
        val backdrop: String?,
        val poster: String?,    // https://image.tmdb.org/t/p/w342/... (Cover-Fallback)
        val voteAverage: Double? = null,
        val voteCount: Int? = null,
        val imdbId: String? = null
    )

    /**
     * Zusatzbewertungen von OMDb (IMDb, Rotten Tomatoes, Metacritic).
     * Läuft wie TMDB über das eigene Backend — der OMDb-Key bleibt auf dem Server.
     */
    data class Ratings(
        val imdb: String?,
        val imdbStimmen: String?,
        val rottenTomatoes: String?,
        val metacritic: String?
    ) {
        val hatWerte: Boolean
            get() = imdb != null || rottenTomatoes != null || metacritic != null
    }

    private const val RATINGS_BASE = "https://panel.smartys.tech/api/v1/ratings/"

    /** In-Memory-Cache: Noten ändern sich langsam, pro Sitzung reicht einer. */
    private val ratingsCache = java.util.concurrent.ConcurrentHashMap<String, Ratings?>()

    /** Holt die OMDb-Noten zu einer IMDb-Kennung (`tt…`). null = keine Daten. */
    fun fetchRatings(imdbId: String?): Ratings? {
        val id = imdbId?.trim() ?: return null
        if (!id.startsWith("tt") || id.length < 3) return null
        if (ratingsCache.containsKey(id)) return ratingsCache[id]
        val ergebnis = runCatching {
            val conn = java.net.URL(RATINGS_BASE + id).openConnection() as javax.net.ssl.HttpsURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode != 200) return@runCatching null
            val root = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
            fun wert(name: String): String? =
                root.optString(name).takeIf { it.isNotBlank() && it != "null" && it != "N/A" }
            Ratings(
                imdb = wert("imdb"),
                imdbStimmen = wert("imdb_stimmen"),
                rottenTomatoes = wert("rotten_tomatoes"),
                metacritic = wert("metacritic") ?: wert("metascore")?.let { "$it/100" }
            )
        }.getOrNull()
        ratingsCache[id] = ergebnis
        return ergebnis
    }

    /** In-Memory-Cache für Poster-URLs (verhindert N-fache Backend-Calls pro Liste). */
    private val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun fetchMovie(tmdbId: String): MovieMeta? = runCatching {
        val conn = java.net.URL(BASE + tmdbId.trim()).openConnection() as javax.net.ssl.HttpsURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        if (conn.responseCode != 200) return@runCatching null
        val root = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        val genres = mutableListOf<String>()
        val ga = root.optJSONArray("genres") ?: org.json.JSONArray()
        for (i in 0 until ga.length()) genres.add(ga.getJSONObject(i).optString("name"))
        MovieMeta(
            overview = root.optString("overview").takeIf { it.isNotBlank() },
            runtime = if (root.has("runtime") && !root.isNull("runtime")) root.getInt("runtime") else null,
            genres = genres,
            tagline = root.optString("tagline").takeIf { it.isNotBlank() },
            backdrop = root.optString("backdrop_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w780$it" },
            poster = root.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w342$it" },
            voteAverage = if (root.has("vote_average") && !root.isNull("vote_average"))
                root.optDouble("vote_average").takeIf { !it.isNaN() } else null,
            voteCount = if (root.has("vote_count") && !root.isNull("vote_count"))
                root.optInt("vote_count") else null,
            imdbId = root.optString("imdb_id").takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    /** Nur die Poster-URL für eine TMDB-ID (leichtgewichtiger Fallback, gecacht). */
    fun posterUrl(tmdbId: String): String? {
        val id = tmdbId.trim()
        if (id.isBlank()) return null
        posterCache[id]?.let { return it }
        return fetchMovie(id)?.poster?.also { posterCache[id] = it }
    }
}
