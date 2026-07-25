package eu.kanade.tachiyomi.lib.vidmolyextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class VidmolyExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String = "Vidmoly", subtitleList: List<Track> = emptyList()): Video? {
        val document = try {
            client.newCall(GET(url)).execute().asJsoup()
        } catch (_: Exception) {
            return null
        }

        // Find the script tag containing the player configuration
        val scriptData = document.selectFirst("script:containsData(sources:)")?.data() 
            ?: return null

        // Primary regex matching the sources array directly
        var videoUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*['"]([^'"]+)['"]""").find(scriptData)?.groupValues?.get(1)

        // Fallback regex looking directly for a master.m3u8 or video file URL 
        if (videoUrl == null) {
            videoUrl = Regex("""file:\s*['"](https?://[^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""").find(scriptData)?.groupValues?.get(1)
        }

        if (videoUrl == null) return null

        return Video(videoUrl, quality, videoUrl, subtitleTracks = subtitleList)
    }

    fun videosFromUrl(url: String, quality: String = "Vidmoly", subtitleList: List<Track> = emptyList()): List<Video> {
        return videoFromUrl(url, quality, subtitleList)?.let(::listOf).orEmpty()
    }
}