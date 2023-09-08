package eu.kanade.tachiyomi.animeextension.tr.anizm

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.anizm.AnizmFilters.applyFilterParams
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Anizm : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Anizm"

    override val baseUrl = "https://anizm.net"

    override val lang = "tr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeSelector() = "div.popularAnimeCarousel a.slideAnimeLink"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        title = element.selectFirst(".title")!!.text()
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        element.attr("href")
            .substringBefore("-bolum-izle")
            .substringBeforeLast("-")
            .also { setUrlWithoutDomain(it) }
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime-izle?sayfa=$page", headers)

    override fun latestUpdatesSelector() = "div#episodesMiddle div.posterBlock > a"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "div.nextBeforeButtons > div.ui > a.right:not(.disabled)"

    // =============================== Search ===============================
    private val animeList by lazy {
        client.newCall(GET("$baseUrl/getAnimeListForSearch", headers)).execute()
            .parseAs<List<SearchItemDto>>()
            .asSequence()
    }

    override fun getFilterList(): AnimeFilterList = AnizmFilters.FILTER_LIST

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            val params = AnizmFilters.getSearchParameters(filters).apply {
                animeName = query
            }
            val filtered = animeList.applyFilterParams(params)
            val results = filtered.chunked(30).toList()
            val hasNextPage = results.size > page
            val currentPage = if (results.size == 0) {
                emptyList<SAnime>()
            } else {
                results.get(page - 1).map {
                    SAnime.create().apply {
                        title = it.title
                        url = "/" + it.slug
                        thumbnail_url = baseUrl + "/storage/pcovers/" + it.thumbnail
                    }
                }
            }
            Observable.just(AnimesPage(currentPage, hasNextPage))
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h2.anizm_pageTitle")!!.text()
        thumbnail_url = document.selectFirst("div.infoPosterImg > img")!!.attr("abs:src")
        val infosDiv = document.selectFirst("div.anizm_boxContent")!!
        genre = infosDiv.select("span.dataValue > span.tag > span.label").eachText().joinToString()
        artist = infosDiv.selectFirst("span.dataTitle:contains(Stüdyo) + span")?.text()

        description = buildString {
            infosDiv.selectFirst("div.infoDesc")?.text()?.also(::append)

            infosDiv.select("li.dataRow:not(:has(span.ui.tag)):not(:has(div.star)) > span")
                .forEach {
                    when {
                        it.hasClass("dataTitle") -> append("\n${it.text()}: ")
                        else -> append(it.text())
                    }
                }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.episodeListTabContent div > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        episode_number = element.text().filter(Char::isDigit).toFloatOrNull() ?: 1F
        name = element.text()
    }

    // ============================ Video Links =============================
    @Serializable
    data class ResponseDto(val data: String)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.use { it.asJsoup() }
        val fansubUrls = doc.select("div#fansec > a").map { it.attr("translator") }
        val playerUrls = fansubUrls.flatMap {
            runCatching {
                client.newCall(GET(it, headers)).execute()
                    .parseAs<ResponseDto>()
                    .data
                    .let(Jsoup::parse)
                    .select("a.videoPlayerButtons")
                    .map { it.attr("video").replace("/video/", "/player/") }
            }.getOrElse { emptyList() }
        }
        return playerUrls.parallelMap {
            runCatching {
                getVideosFromUrl(it)
            }.getOrElse { emptyList() }
        }.flatten()
    }

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }

    private fun getVideosFromUrl(firstUrl: String): List<Video> {
        val url = noRedirectClient.newCall(GET(firstUrl, headers)).execute()
            .use { it.headers["location"] }
            ?: return emptyList()

        return when {
            "filemoon.sx" in url -> filemoonExtractor.videosFromUrl(url, headers = headers)
            "sendvid.com" in url -> sendvidExtractor.videosFromUrl(url)
            "video.sibnet" in url -> sibnetExtractor.videosFromUrl(url)
            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, headers)
            "yourupload" in url -> yourUploadExtractor.videoFromUrl(url, headers)
            "dood" in url -> doodExtractor.videoFromUrl(url)?.let(::listOf)
            "drive.google" in url -> {
                val newUrl = "https://gdriveplayer.to/embed2.php?link=$url"
                gdrivePlayerExtractor.videosFromUrl(newUrl, "GdrivePlayer", headers)
            }
            "uqload" in url -> uqloadExtractor.videosFromUrl(url)
            "voe.sx" in url -> voeExtractor.videoFromUrl(url)?.let(::listOf)
            else -> null
        } ?: emptyList()
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private inline fun <reified T> Response.parseAs(): T = use {
        json.decodeFromStream(it.body.byteStream())
    }

    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
