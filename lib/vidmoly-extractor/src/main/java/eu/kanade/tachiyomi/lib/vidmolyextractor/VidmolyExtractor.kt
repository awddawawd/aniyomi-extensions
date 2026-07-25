package eu.kanade.tachiyomi.lib.vidmolyextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VidMolyExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(
        url: String,
        quality: String = "HLS",
        subtitleList: List<Track> = emptyList()
    ): Video? {
        // Ensure we have the embed URL format
        val embedUrl = if (url.contains("/embed-")) url else {
            // If given a page like https://vidmoly.biz/xxx, we might need to construct embed
            // But we'll assume the input is the embed URL already.
            url
        }

        // Fetch the page
        val document = client.newCall(GET(embedUrl)).execute().asJsoup()

        // Find the script containing JWPlayer setup
        // Look for: sources: [{ file: '...' }]
        val scriptWithSources = document.selectFirst("script:containsData(sources:)")
            ?.data()
            ?: return null

        // Extract the file URL using regex
        val pattern = Regex("""sources:\s*\[\s*\{\s*file:\s*['"]([^'"]+)['"]\s*\}""")
        val match = pattern.find(scriptWithSources)
        val videoUrl = match?.groupValues?.get(1) ?: return null

        // The URL may be relative? It is absolute in our case.
        return Video(videoUrl, quality, videoUrl, subtitleTracks = subtitleList)
    }

    fun videosFromUrl(
        url: String,
        quality: String = "HLS",
        subtitleList: List<Track> = emptyList()
    ): List<Video> {
        return videoFromUrl(url, quality, subtitleList)?.let(::listOf).orEmpty()
    }
}