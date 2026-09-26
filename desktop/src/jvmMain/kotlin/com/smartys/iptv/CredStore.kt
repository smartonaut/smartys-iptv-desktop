package com.smartys.iptv

/**
 * Desktop-Variante von CredStore: nur die Creds-Datenklasse (kein Android-Keystore).
 * Persistenz (verschlüsselt) kommt als eigener Schritt; für den ersten Meilenstein
 * reicht die In-Memory-Struktur.
 */
object CredStore {

    data class Creds(val url: String, val user: String, val pass: String) {
        fun streamUrl(streamId: String, ext: String = "m3u8"): String =
            "$url/live/$user/$pass/$streamId.$ext"

        fun movieUrl(streamId: String, ext: String = "mp4"): String =
            "$url/movie/$user/$pass/$streamId.$ext"

        fun seriesUrl(episodeId: String, ext: String = "mp4"): String =
            "$url/series/$user/$pass/$episodeId.$ext"
    }
}
