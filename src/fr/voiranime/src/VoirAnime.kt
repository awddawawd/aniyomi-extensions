package eu.kanade.tachiyomi.animeextension.fr.voiranime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
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

    // ============================== Popular (Homepage) ===============================
    
    override fun popularAnimeRequest(page: Int): Request {
        // Requesting the base URL so we can see what the homepage HTML looks like
        return GET(baseUrl, headers)
    }

    // Intercept the response BEFORE the app tries to look for CSS selectors
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        
        // Grab the first 1500 characters of the HTML to fit on your phone screen.
        // Throwing it as an Exception forces the app to display it as an error message!
        throw Exception("HTML RECEIVED:\n\n" + document.html().take(1500))
    }

    // These are required by the compiler, but will never be reached because we throw an Exception above.
    override fun popularAnimeSelector(): String = ""
    override fun popularAnimeNextPageSelector(): String? = null
    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

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
