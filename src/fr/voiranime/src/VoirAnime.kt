package eu.kanade.tachiyomi.animeextension.fr.voiranime

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class VoirAnime : ParsedAnimeHttpSource() {

    // --- Core Source Info ---
    override val name = "VoirAnime"
    override val baseUrl = "https://voir-anime.to" 
    override val lang = "fr"
    override val supportsLatest = false

    // ============================== Network & Security ==============================
    
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")

    // ============================== Popular (Homepage) ===============================
    
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/?filter=subbed"
        } else {
            "$baseUrl/page/$page/?filter=subbed"
        }
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = ".page-item-detail"

    override fun popularAnimeNextPageSelector(): String = ".nextpostslink"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        
        // --- Title and URL ---
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

        // --- High-Quality Thumbnail ---
        val imgElement = element.select("div.item-thumb img").first()
        if (imgElement != null) {
            val rawUrl = imgElement.absUrl("data-src").ifEmpty {
                imgElement.absUrl("src")
            }
            anime.thumbnail_url = rawUrl.replace(Regex("-\\d+x\\d+"), "")
        }
        
        return anime
    }

    // ============================== Latest (Ignored) ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    // ============================== Search ===============================
    
        // 1. Building the POST request with the hidden Form Data
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (page > 1) throw Exception("No more pages")
        
        val formBody = FormBody.Builder()
            .add("action", "ajaxsearchpro_search")
            .add("aspp", query) 
            .add("asid", "3")
            .add("asp_inst_id", "3_1")
            // Use addEncoded so OkHttp doesn't double-encode the string!
            .addEncoded("options", "aspf%5Bvf_1%5D%3Dvf%26asp_gen%5B%5D%3Dexcerpt%26asp_gen%5B%5D%3Dcontent%26asp_gen%5B%5D%3Dtitle%26filters_initial%3D1%26filters_changed%3D0%26qtranslate_lang%3D0%26current_page_id%3D15")
            .build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val rawResponse = response.body.string()
        
        // DELIBERATE CRASH: Display the first 1500 characters of the server's reply!
        throw Exception("SEARCH RESPONSE:\n\n" + rawResponse.take(1500))
    }

    override fun searchAnimeSelector(): String = ""
    override fun searchAnimeNextPageSelector(): String? = null
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    // =========================== Anime Details (Ignored) ===========================
    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes (Ignored) ==============================
    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links (Ignored) =============================
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
}
