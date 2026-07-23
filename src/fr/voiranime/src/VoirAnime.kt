package eu.kanade.tachiyomi.animeextension.fr.voiranime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class VoirAnime : ParsedAnimeHttpSource() {

    // --- Core Source Info ---
    override val name = "VoirAnime"
    // Keep the baseUrl completely clean without parameters or trailing slashes
    override val baseUrl = "https://voir-anime.to" 
    override val lang = "fr"
    override val supportsLatest = false

    // ============================== Popular (Homepage) ===============================
    
    // 1. The URL we want to scrape for the homepage
    override fun popularAnimeRequest(page: Int): Request {
        // We inject the ?filter=subbed here so pagination works perfectly
        val url = if (page == 1) {
            "$baseUrl/?filter=subbed"
        } else {
            "$baseUrl/page/$page/?filter=subbed"
        }
        return GET(url, headers)
    }

    // 2. The CSS selector for the repeating box that holds ONE anime
    override fun popularAnimeSelector(): String = ".page-item-detail"

    // 3. The CSS selector for the "Next Page" button
    override fun popularAnimeNextPageSelector(): String = ".nextpostslink"

    // 4. Extracting the details 
        override fun popularAnimeFromElement(element: Element): SAnime {
        // This will deliberately crash the app and print the HTML of the VERY FIRST anime box
        throw Exception("ANIME BOX HTML:\n\n" + element.html())
    }


    // ============================== Latest (Ignored) ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    // ============================== Search (Ignored) ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()
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
