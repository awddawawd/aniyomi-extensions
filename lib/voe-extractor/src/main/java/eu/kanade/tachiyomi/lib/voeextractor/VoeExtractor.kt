package eu.kanade.tachiyomi.lib.voeextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class VoeExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()
    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://voe.sx/")
            .build()

        // 1. Fetch initial URL
        var response = client.newCall(GET(url, headers)).execute()
        var body = response.body?.string() ?: return emptyList()

        // 2. Handle JS redirect if present (The 757-byte localStorage script)
        if (body.contains("window.location.href") && body.contains("localStorage")) {
            val redirectRegex = """window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""".toRegex()
            val match = redirectRegex.find(body)
            if (match != null) {
                val newUrl = match.groupValues[1]
                response = client.newCall(GET(newUrl, headers)).execute()
                body = response.body?.string() ?: return emptyList()
            }
        }

        // Parse HTML body
        val document = Jsoup.parse(body)

        // 3. Find the encoded application/json configuration block
        val scriptTags = document.select("script[type=application/json]")
        var encodedStr = ""
        for (script in scriptTags) {
            try {
                val data = json.parseToJsonElement(script.data()).jsonArray
                if (data.isNotEmpty()) {
                    val str = data[0].jsonPrimitive.content
                    if (str.length > 100) {
                        encodedStr = str
                        break
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        if (encodedStr.isEmpty()) return emptyList()

        // 4. Decode the configuration payload
        val config = decodeConfig(encodedStr) ?: return emptyList()

        val videos = mutableListOf<Video>()

        // 5. Extract target stream URLs
        val hlsUrl = config["source"]?.jsonPrimitive?.content ?: config["file"]?.jsonPrimitive?.content
        val mp4Url = config["direct_access_url"]?.jsonPrimitive?.content

        // Process HLS (.m3u8) streams via PlaylistUtils to get all qualities
        if (hlsUrl != null && hlsUrl.contains(".m3u8")) {
            videos.addAll(
                playlistUtils.extractFromHls(
                    hlsUrl,
                    videoNameGen = { quality -> "${prefix}Voe: $quality" }
                )
            )
        }

        // Add direct MP4 fallback if available
        if (mp4Url != null) {
            videos.add(Video(mp4Url, "${prefix}Voe: MP4", mp4Url))
        }

        return videos
    }

    private fun decodeConfig(encodedStr: String): JsonObject? {
        return try {
            // Step 1: ROT13 Translation
            var s = encodedStr.map { c ->
                when (c) {
                    in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                    in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                    else -> c
                }
            }.joinToString("")

            // Step 2 & 3: Replace obfuscation layout patterns and remove them
            val patterns = arrayOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
            for (p in patterns) {
                s = s.replace(p, "_")
            }
            s = s.replace("_", "")

            // Step 4: First Base64 decode
            val b64Decoded1 = Base64.decode(s, Base64.DEFAULT).decodeToString()

            // Step 5: Shift character codes globally by -3
            val shifted = b64Decoded1.map { (it.code - 3).toChar() }.joinToString("")

            // Step 6: Reverse string
            val reversed = shifted.reversed()

            // Step 7: Final Base64 decode
            val finalJsonStr = Base64.decode(reversed, Base64.DEFAULT).decodeToString()

            // Step 8: Parse out the JSON object
            json.parseToJsonElement(finalJsonStr) as JsonObject
        } catch (e: Exception) {
            null
        }
    }
}