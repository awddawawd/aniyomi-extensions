package eu.kanade.tachiyomi.animeextension.fr.voiranime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class VoirAnime : ParsedAnimeHttpSource() {

    override val name = "VoirAnime"
    override val baseUrl = "https://voir-anime.to"
    override val lang = "fr"
    override val supportsLatest = false

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")

    // ==================== GENRE FILTER ====================

    private val genres by lazy {
        listOf(
            "Comedy" to 2940, "Action" to 2746, "Fantasy" to 2001, "Drama" to 1878,
            "Adventure" to 1642, "Romance" to 1624, "Sci-Fi" to 1277, "Slice of Life" to 1219,
            "Ecchi" to 656, "Mystery" to 624, "Mecha" to 427, "Sports" to 384,
            "Music" to 274, "Horror" to 249, "Thriller" to 180, "Mahou Shoujo" to 178,
            "Supernatural" to 131, "Chinese" to 48, "Cartoon" to 10
        ).sortedByDescending { it.second }.map { it.first }
    }

    private class GenreFilter(genres: List<String>) : AnimeFilter.Select<String>(
        "Genre", genres.toTypedArray()
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Choisissez un genre (du plus populaire au moins populaire)"),
        GenreFilter(genres)
    )

    // ============================== POPULAR ==============================

    override fun popularAnimeRequest(page: Int): Request = popularAnimeRequest(page, getFilterList())

    private fun popularAnimeRequest(page: Int, filterList: AnimeFilterList): Request {
        val genreFilter = filterList.find { it is GenreFilter } as? GenreFilter
        val selectedGenre = genreFilter?.state?.let { idx ->
            if (idx in genres.indices) genres[idx] else null
        }

        val url = if (selectedGenre != null) {
            val slug = selectedGenre.lowercase().replace(" ", "-")
            if (page == 1) "$baseUrl/anime-genre/$slug/"
            else "$baseUrl/anime-genre/$slug/page/$page/"
        } else {
            if (page == 1) "$baseUrl/?filter=subbed"
            else "$baseUrl/page/$page/?filter=subbed"
        }
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = ".page-item-detail"
    override fun popularAnimeNextPageSelector(): String = ".nextpostslink"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val titleElement = element.select("div.post-title h3 a, h3.h5 a").first()
        if (titleElement != null) {
            anime.title = titleElement.text()
            anime.setUrlWithoutDomain(titleElement.attr("href"))
        } else {
            val fallbackLink = element.select("a").first()
            if (fallbackLink != null) {
                anime.title = fallbackLink.attr("title").ifEmpty { fallbackLink.text() }
                anime.setUrlWithoutDomain(fallbackLink.attr("href"))
            }
        }

        val imgElement = element.select("div.item-thumb img").first()
        if (imgElement != null) {
            var thumbUrl = imgElement.absUrl("data-src").ifEmpty { imgElement.absUrl("src") }
            val srcset = imgElement.attr("srcset")
            if (srcset.isNotEmpty()) {
                val bestQuality = srcset.split(",")
                    .map { it.trim() }
                    .maxByOrNull {
                        it.substringAfterLast(" ").removeSuffix("w").toIntOrNull() ?: 0
                    }?.substringBefore(" ")
                if (!bestQuality.isNullOrEmpty()) {
                    thumbUrl = if (bestQuality.startsWith("http")) bestQuality else "$baseUrl$bestQuality"
                }
            }
            anime.thumbnail_url = thumbUrl
        }
        return anime
    }

    // ============================== LATEST ==============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    // ============================== SEARCH ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (page > 1) throw Exception("No more pages")
        val formBody = FormBody.Builder()
            .add("action", "ajaxsearchpro_search")
            .add("aspp", query)
            .add("asid", "3")
            .add("asp_inst_id", "3_1")
            .addEncoded("options", "aspf%5Bvf_1%5D%3Dvf%26asp_gen%5B%5D%3Dexcerpt%26asp_gen%5B%5D%3Dcontent%26asp_gen%5B%5D%3Dtitle%26filters_initial%3D1%26filters_changed%3D0%26qtranslate_lang%3D0%26current_page_id%3D15")
            .build()
        return POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val rawResponse = response.body.string()
        val cleanHtml = rawResponse.substringAfter("ASPSTART_HTML").substringBefore("_ASPEND_HTML")
        val document = Jsoup.parse(cleanHtml)
        val animes = document.select(searchAnimeSelector())
            .map { searchAnimeFromElement(it) }
            .filter { !it.title.contains("(VF)") }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeSelector(): String = "div.item.asp_r_pagepost"
    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val titleElement = element.select("h3 a.asp_res_url").first()
        if (titleElement != null) {
            anime.title = titleElement.text()
            anime.setUrlWithoutDomain(titleElement.attr("href"))
        }
        val imgElement = element.select("div.asp_image img").first()
        if (imgElement != null) {
            anime.thumbnail_url = imgElement.absUrl("src")
        } else {
            val divImg = element.select("div.asp_image").first()
            if (divImg != null) {
                anime.thumbnail_url = divImg.absUrl("data-src")
            }
        }
        return anime
    }

    // =========================== ANIME DETAILS ===========================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        description = document.select(".description-summary .summary__content p").text()

        genre = document.select(".post-content_item .genres-content a")
            .joinToString(", ") { it.text() }

        status = parseStatus(
            document.select(".post-content_item:has(.summary-heading h5:contains(Status)) .summary-content")
                .text()
        )

        val studioItem = document.select(".post-content_item").firstOrNull { item ->
            item.selectFirst(".summary-heading h5")?.text()?.trim()?.contains("Studio", ignoreCase = true) == true
        }
        author = studioItem?.selectFirst(".summary-content")?.text()?.trim().orEmpty()

        val img = document.select("div.summary_image img").first()
        if (img != null) {
            thumbnail_url = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
        }
    }

    private fun parseStatus(statusStr: String): Int {
        return when {
            statusStr.contains("EN COURS", ignoreCase = true) -> SAnime.ONGOING
            statusStr.contains("TERMINÉ", ignoreCase = true) || statusStr.contains("COMPLETED", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== EPISODES ==============================

    override fun episodeListSelector(): String = ".listing-chapters_wrap ul.main.version-chap li.wp-manga-chapter"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val urlElement = element.select("a").first()
        if (urlElement != null) {
            setUrlWithoutDomain(urlElement.attr("href"))
            name = urlElement.text()
        }
        val dateElement = element.select("span.chapter-release-date i").first()
        date_upload = parseEpisodeDate(dateElement?.text())
    }

    private fun parseEpisodeDate(date: String?): Long {
        if (date.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH).parse(date)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // ============================ VIDEO LINKS (self‑contained) ============================

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val videos = mutableListOf<Video>()

        val iframes = document.select("iframe")
        for (iframe in iframes) {
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isBlank()) continue

            val name = when {
                src.contains("voe.sx") -> "VOE"
                src.contains("filemoon.sx") -> "MOON"
                src.contains("streamtape.com") -> "Stape"
                // TODO: add myTV host detection here
                else -> continue
            }
            videos.add(Video(src, "$name - VoirAnime", src))
        }
        return videos
    }

    override fun videoUrlParse(document: Document): String {
        val url = document.location()
        return try {
            when {
                url.contains("voe.sx") -> extractVoe(url)
                url.contains("filemoon.sx") -> extractFilemoon(url)
                url.contains("streamtape.com") -> extractStreamtape(url)
                else -> ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // ---------- Inline extractors ----------

    private fun extractVoe(url: String): String {
        val request = GET(url, headers)
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ""
        // VOE often has a script containing "prompt":"https://...mp4"
        val regex = """"prompt"\s*:\s*"(https:[^"]+\.mp4)"""".toRegex()
        val match = regex.find(body) ?: return ""
        return match.groupValues[1]
    }

    private fun extractFilemoon(url: String): String {
        // Filemoon sometimes embeds a second iframe; we fetch it
        val request = GET(url, headers)
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ""
        val doc = Jsoup.parse(body)
        val iframe = doc.selectFirst("iframe") ?: return ""
        val subUrl = iframe.attr("src").ifBlank { return "" }

        val subRequest = GET(subUrl, headers)
        val subResponse = client.newCall(subRequest).execute()
        val subBody = subResponse.body?.string() ?: return ""
        // Extract mp4 from script: file:"https://....mp4"
        val regex = """file\s*:\s*"(https:[^"]+\.mp4)"""".toRegex()
        val match = regex.find(subBody) ?: return ""
        return match.groupValues[1]
    }

    private fun extractStreamtape(url: String): String {
        // Streamtape needs a token from the page
        val getRequest = GET(url, headersBuilder().add("Referer", url).build())
        val getResponse = client.newCall(getRequest).execute()
        val doc = Jsoup.parse(getResponse.body?.string() ?: "", getRequest.url.toString())
        val token = doc.select("div#robotlink").text()
        if (token.isBlank()) return ""

        val postUrl = "https://streamtape.com/get_video"
        val formBody = FormBody.Builder()
            .add("token", token)
            .build()
        val postRequest = Request.Builder()
            .url(postUrl)
            .headers(headersBuilder().add("Referer", url).build())
            .post(formBody)
            .build()
        val postResponse = client.newCall(postRequest).execute()
        val json = postResponse.body?.string() ?: return ""
        val videoRegex = """"videolink"\s*:\s*"(https:[^"]+)"""".toRegex()
        val videoMatch = videoRegex.find(json) ?: return ""
        return videoMatch.groupValues[1]
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException("Not used")
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not used")
}
