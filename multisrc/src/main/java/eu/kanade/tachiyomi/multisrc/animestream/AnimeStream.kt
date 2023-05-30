package eu.kanade.tachiyomi.multisrc.animestream

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.GenresFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.OrderFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.SeasonFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.StatusFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.StudioFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.SubFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.TypeFilter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

abstract class AnimeStream(
    override val lang: String,
    override val name: String,
    override val baseUrl: String,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val supportsLatest = true

    override val client = network.cloudflareClient

    protected open val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        const val PREFIX_SEARCH = "path:"
    }

    protected open val prefQualityDefault = "720p"
    protected open val prefQualityKey = "preferred_quality"
    protected open val prefQualityTitle = when (lang) {
        "pt-BR" -> "Qualidade preferida"
        else -> "Preferred quality"
    }
    protected open val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p")
    protected open val prefQualityEntries = prefQualityValues

    protected open val videoSortPrefKey = prefQualityKey
    protected open val videoSortPrefDefault = prefQualityDefault

    protected open val dateFormatter by lazy {
        val locale = when (lang) {
            "pt-BR" -> Locale("pt", "BR")
            else -> Locale.ENGLISH
        }
        SimpleDateFormat("MMMM d, yyyy", locale)
    }

    protected open val animeListUrl = "$baseUrl/anime"

    // ============================== Popular ===============================
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        fetchFilterList()
        return super.fetchPopularAnime(page)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val ahref = element.selectFirst("h4 > a.series")!!
            setUrlWithoutDomain(ahref.attr("href"))
            title = ahref.text()
            thumbnail_url = element.selectFirst("img")!!.getImageUrl()
        }
    }

    override fun popularAnimeNextPageSelector() = null

    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    /* Possible classes: wpop-weekly, wpop-monthly, wpop-alltime */
    override fun popularAnimeSelector() = "div.serieslist.wpop-alltime li"

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select(episodeListSelector()).map(::episodeFromElement)
    }

    protected open val episodePrefix = when (lang) {
        "pt-BR" -> "Episódio"
        else -> "Episode"
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            element.selectFirst("div.epl-num")!!.text().let {
                name = "$episodePrefix $it"
                episode_number = it.substringBefore(" ").toFloatOrNull() ?: 0F
            }
            element.selectFirst("div.epl-sub")?.text()?.let { scanlator = it }
            date_upload = element.selectFirst("div.epl-date")?.text().toDate()
        }
    }

    override fun episodeListSelector() = "div.eplister > ul > li > a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            title = document.selectFirst("h1.entry-title")!!.text()
            thumbnail_url = document.selectFirst("div.thumb > img")!!.getImageUrl()

            val infos = document.selectFirst("div.info-content")!!
            genre = infos.select("div.genxed > a").eachText().joinToString()

            status = parseStatus(infos.getInfo("Status"))
            artist = infos.getInfo("tudio")
            author = infos.getInfo("Fansub")

            description = buildString {
                document.selectFirst("div.entry-content")?.text()?.let {
                    append("$it\n\n")
                }

                infos.select("div.spe > span").eachText().forEach {
                    append("$it\n")
                }
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "select.mirror > option[data-index]"

    override fun videoListParse(response: Response): List<Video> {
        val items = response.asJsoup().select(videoListSelector())
        return items.parallelMap { element ->
            runCatching {
                val name = element.text()
                val url = getHosterUrl(element)
                getVideoList(url, name)
            }.onFailure { it.printStackTrace() }.getOrElse { emptyList() }
        }.flatten()
    }

    protected open fun getHosterUrl(element: Element): String {
        return Base64.decode(element.attr("value"), Base64.DEFAULT)
            .let(::String) // bytearray -> string
            .let(Jsoup::parse) // string -> document
            .selectFirst("iframe[src~=.]")!!
            .attr("src")
            .let { // sometimes the url dont specify its protocol
                when {
                    it.startsWith("http") -> it
                    else -> "https:$it"
                }
            }
    }

    protected open fun getVideoList(url: String, name: String): List<Video> {
        Log.i(name, "getVideoList -> URL => $url || Name => $name")
        return emptyList()
    }

    override fun videoFromElement(element: Element) = throw Exception("Not Used")

    override fun videoUrlParse(document: Document) = throw Exception("Not Used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.selectFirst("div.tt")!!.ownText()
            thumbnail_url = element.selectFirst("img")!!.getImageUrl()
        }
    }

    override fun searchAnimeNextPageSelector() = "div.pagination a.next, div.hpage > a.r"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeStreamFilters.getSearchParameters(filters)
        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val multiString = buildString {
                if (params.genres.isNotEmpty()) append(params.genres + "&")
                if (params.seasons.isNotEmpty()) append(params.seasons + "&")
                if (params.studios.isNotEmpty()) append(params.studios + "&")
            }

            GET("$baseUrl/anime/?page=$page&$multiString&status=${params.status}&type=${params.type}&sub=${params.sub}&order=${params.order}")
        }
    }

    override fun searchAnimeSelector() = "div.listupd article a.tip"

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map(::searchAnimeByPathParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    protected open fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> {
        fetchFilterList()
        return super.fetchLatestUpdates(page)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$animeListUrl/?page=$page&order=update")

    override fun latestUpdatesSelector() = searchAnimeSelector()

    override fun latestUpdatesNextPageSelector() = searchAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = searchAnimeFromElement(element)

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = prefQualityKey
            title = prefQualityTitle
            entries = prefQualityEntries
            entryValues = prefQualityValues
            setDefaultValue(prefQualityDefault)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
    }

    // ============================== Filters ===============================

    /**
     * Disable it if you don't want the filters to be automatically fetched.
     */
    protected open val fetchFilters = true

    private fun fetchFilterList() {
        if (fetchFilters && !AnimeStreamFilters.filterInitialized()) {
            AnimeStreamFilters.filterElements = runBlocking {
                withContext(Dispatchers.IO) {
                    client.newCall(GET(animeListUrl)).execute()
                        .asJsoup()
                        .select("span.sec1 > div.filter > ul")
                }
            }
        }
    }

    protected open val filtersHeader = when (lang) {
        "pt-BR" -> "NOTA: Filtros serão ignorados se usar a pesquisa por nome!"
        else -> "NOTE: Filters are going to be ignored if using search text!"
    }

    protected open val filtersMissingWarning: String = when (lang) {
        "pt-BR" -> "Aperte 'Redefinir' para tentar mostrar os filtros"
        else -> "Press 'Reset' to attempt to show the filters"
    }

    protected open val genresFilterText = when (lang) {
        "pt-BR" -> "Gêneros"
        else -> "Genres"
    }

    protected open val seasonsFilterText = when (lang) {
        "pt-BR" -> "Temporadas"
        else -> "Seasons"
    }

    protected open val studioFilterText = when (lang) {
        "pt-BR" -> "Estúdios"
        else -> "Studios"
    }

    protected open val statusFilterText = "Status"

    protected open val typeFilterText = when (lang) {
        "pt-BR" -> "Tipo"
        else -> "Type"
    }

    protected open val subFilterText = when (lang) {
        "pt-BR" -> "Legenda"
        else -> "Subtitle"
    }

    protected open val orderFilterText = when (lang) {
        "pt-BR" -> "Ordem"
        else -> "Order"
    }

    override fun getFilterList(): AnimeFilterList {
        return if (fetchFilters && AnimeStreamFilters.filterInitialized()) {
            AnimeFilterList(
                GenresFilter(genresFilterText),
                SeasonFilter(seasonsFilterText),
                StudioFilter(studioFilterText),
                AnimeFilter.Separator(),
                StatusFilter(statusFilterText),
                TypeFilter(typeFilterText),
                SubFilter(subFilterText),
                OrderFilter(orderFilterText),
            )
        } else if (fetchFilters) {
            AnimeFilterList(AnimeFilter.Header(filtersMissingWarning))
        } else {
            AnimeFilterList()
        }
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy { it.quality.contains(quality, true) },
        ).reversed()
    }

    protected open fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()?.lowercase()) {
            "completed", "completo" -> SAnime.COMPLETED
            "ongoing", "lançamento" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    protected open fun Element.getInfo(text: String): String? {
        return selectFirst("span:contains($text)")
            ?.run {
                selectFirst("a")?.text() ?: ownText()
            }
    }

    protected open fun String?.toDate(): Long {
        return this?.let {
            runCatching {
                dateFormatter.parse(trim())?.time
            }.getOrNull()
        } ?: 0L
    }

    protected inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    /**
     * Tries to get the image url via various possible attributes.
     * Taken from Tachiyomi's Madara multisrc.
     */
    protected open fun Element.getImageUrl(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }.substringBefore("?resize")
    }
}
