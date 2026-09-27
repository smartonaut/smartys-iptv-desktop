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
        val backdrop: String?
    )

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
                ?.let { "https://image.tmdb.org/t/p/w780$it" }
        )
    }.getOrNull()
}
