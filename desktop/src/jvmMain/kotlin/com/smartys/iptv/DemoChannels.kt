package com.smartys.iptv

/**
 * Demo-Modus: legale, öffentlich-rechtliche Sender (SD, nicht HD), damit
 * Interessenten den Player ohne eigene Line testen können.
 *
 * NUR frei zugängliche, legale Sender-Streams — keine privaten Lines,
 * keine Xtream-Credentials, keine HD-Premium-Varianten (med statt veryhigh).
 * Quelle: öffentliche Livestreams der Sender (ARD/ZDF/Arte/phoenix/tagesschau24).
 */
object DemoChannels {

    const val CATEGORY_ID = "demo"
    const val CATEGORY_NAME = "Demo (öffentlich-rechtlich)"

    private data class DemoEntry(val name: String, val url: String)

    private val ENTRIES = listOf(
        DemoEntry("Das Erste", "https://daserste-live.ard-mcdn.de/daserste/live/hls/de/master.m3u8"),
        DemoEntry("ZDF", "https://zdf-hls-15.akamaized.net/hls/live/2016498/de/med/master.m3u8"),
        DemoEntry("3sat", "https://zdf-hls-18.akamaized.net/hls/live/2016501/dach/med/master.m3u8"),
        DemoEntry("ARTE", "https://artesimulcast.akamaized.net/hls/live/2030993/artelive_de/master.m3u8"),
        DemoEntry("phoenix", "https://zdf-hls-19.akamaized.net/hls/live/2016502/de/med/master.m3u8"),
        DemoEntry("tagesschau24", "https://tagesschau.akamaized.net/hls/live/2020115/tagesschau/tagesschau_1/master.m3u8"),
        DemoEntry("KiKA", "https://kikageohls.akamaized.net/hls/live/2022693/livetvkika_de/master.m3u8"),
        DemoEntry("one", "https://mcdn-one.ard.de/ardone/hls/master.m3u8"),
        DemoEntry("ZDFneo", "https://zdf-hls-16.akamaized.net/hls/live/2016499/de/med/master.m3u8"),
        DemoEntry("ZDFinfo", "https://zdf-hls-17.akamaized.net/hls/live/2016500/de/med/master.m3u8"),
    )

    fun category(): Category = Category(id = CATEGORY_ID, name = CATEGORY_NAME)

    /** Demo-Kanäle als LiveStream mit direkter Stream-URL (Demo-Modus ohne Xtream). */
    fun build(): List<LiveStream> = ENTRIES.mapIndexed { i, e ->
        LiveStream(
            num = i + 1,
            name = e.name,
            streamType = null,
            streamId = "demo-$i",
            streamIcon = null,
            epgChannelId = null,
            added = null,
            categoryId = CATEGORY_ID
        ).apply {
            categoryName = CATEGORY_NAME
            directUrl = e.url
        }
    }
}