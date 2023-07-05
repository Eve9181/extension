package eu.kanade.tachiyomi.animeextension.pt.animesvision

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animesvision.dto.AVResponseDto
import eu.kanade.tachiyomi.animeextension.pt.animesvision.dto.PayloadData
import eu.kanade.tachiyomi.animeextension.pt.animesvision.dto.PayloadItem
import eu.kanade.tachiyomi.animeextension.pt.animesvision.extractors.GlobalVisionExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.lang.Exception

class AnimesVision : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimesVision"

    override val baseUrl = "https://animes.vision"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::loginInterceptor)
        .build()

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Accept-Language", ACCEPT_LANGUAGE)

    // ============================== Popular ===============================
    private fun nextPageSelector() = "ul.pagination li.page-item:contains(›):not(.disabled)"
    override fun popularAnimeSelector() = "div#anime-trending div.item > a.film-poster"
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val img = element.selectFirst("img")!!
        setUrlWithoutDomain(element.attr("href"))
        title = img.attr("title")
        thumbnail_url = img.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.container div.screen-items > div.item"

    private fun getAllEps(response: Response): List<SEpisode> {
        var doc = getRealDoc(response.use { it.asJsoup() })

        return buildList {
            do {
                addAll(doc.select(episodeListSelector()).map(::episodeFromElement))
                if (doc.hasNextPage()) {
                    val nextUrl = doc.selectFirst(nextPageSelector())!!
                        .selectFirst("a")!!
                        .attr("href")
                    doc = client.newCall(GET(nextUrl)).execute().use { it.asJsoup() }
                }
            } while(doc.hasNextPage())
        }
    }
    override fun episodeListParse(response: Response): List<SEpisode> {
        return getAllEps(response).reversed()
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        val epName = element.selectFirst("h3")!!.text().trim()
        name = epName
        episode_number = epName.substringAfterLast(" ").toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val body = response.body.string()
        val internalVideos = GlobalVisionExtractor()
            .videoListFromHtml(body)
            .toMutableList()

        val externalVideos = externalVideosFromEpisode(response.asJsoup(body))
        return internalVideos + externalVideos
    }

    private fun externalVideosFromEpisode(doc: Document): List<Video> {
        val wireDiv = doc.selectFirst("div[wire:id]")!!
        val initialData = wireDiv.attr("wire:initial-data").dropLast(1)
        val wireToken = doc.html()
            .substringAfter("livewire_token")
            .substringAfter("'")
            .substringBefore("'")

        val headers = headersBuilder()
            .add("x-livewire", "true")
            .add("x-csrf-token", wireToken)
            .add("content-type", "application/json")
            .build()

        val players = doc.select("div.server-item > a.btn")

        return players.parallelMap {
            val id = it.attr("wire:click")
                .substringAfter("(")
                .substringBefore(")")
                .toIntOrNull() ?: 1
            val updateItem = PayloadItem(PayloadData(listOf(id)))
            val updateString = json.encodeToString(updateItem)
            val body = "$initialData, \"updates\": [$updateString]}"
            val reqBody = body.toRequestBody()
            val url = "$baseUrl/livewire/message/components.episodio.player-episodio-component"
            val response = client.newCall(POST(url, headers, reqBody)).execute()
            val responseBody = response.body.string()
            val resJson = json.decodeFromString<AVResponseDto>(responseBody)
            (resJson.serverMemo?.data?.framePlay ?: resJson.effects?.html)
                ?.let(::parsePlayerData)
                ?: emptyList<Video>()
        }.flatten()
    }

    private fun parsePlayerData(data: String) = runCatching {
        when {
            "streamtape" in data ->
                StreamTapeExtractor(client).videoFromUrl(data)?.let(::listOf)
            "dood" in data ->
                DoodExtractor(client).videoFromUrl(data)?.let(::listOf)
            "voe.sx" in data ->
                VoeExtractor(client).videoFromUrl(data)?.let(::listOf)
            else -> null
        }
    }.getOrNull() ?: emptyList<Video>()

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        val elementA = element.selectFirst("a")!!
        title = elementA.attr("title")
        setUrlWithoutDomain(elementA.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.attr("data-src")
    }

    override fun searchAnimeNextPageSelector() = nextPageSelector()

    override fun searchAnimeSelector() = "div.film_list-wrap div.film-poster"

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map(::searchAnimeByPathParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AVFilters.getSearchParameters(filters)
        val url = "$baseUrl/search?".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("nome", query)
            .addQueryParameter("tipo", params.type)
            .addQueryParameter("idioma", params.language)
            .addQueryParameter("ordenar", params.sort)
            .addQueryParameter("ano_inicial", params.initial_year)
            .addQueryParameter("ano_final", params.last_year)
            .addQueryParameter("fansub", params.fansub)
            .addQueryParameter("status", params.status)
            .addQueryParameter("temporada", params.season)
            .addQueryParameter("estudios", params.studio)
            .addQueryParameter("produtores", params.producer)
            .addQueryParameter("generos", params.genres)

        return GET(url.build().toString(), headers)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)

        val content = doc.selectFirst("div#ani_detail div.anis-content")!!
        val detail = content.selectFirst("div.anisc-detail")!!
        val infos = content.selectFirst("div.anisc-info")!!

        thumbnail_url = content.selectFirst("img")!!.attr("src")
        title = detail.selectFirst("h2.film-name")!!.text()
        genre = infos.getInfo("Gêneros")
        author = infos.getInfo("Produtores")
        artist = infos.getInfo("Estúdios")
        status = parseStatus(infos.getInfo("Status"))

        description = buildString {
            append(infos.getInfo("Sinopse") + "\n")
            infos.getInfo("Inglês")?.let { append("\nTítulo em inglês: $it") }
            infos.getInfo("Japonês")?.let { append("\nTítulo em japonês: $it") }
            infos.getInfo("Foi")?.let { append("\nFoi ao ar em: $it") }
            infos.getInfo("Temporada")?.let { append("\nTemporada: $it") }
            infos.getInfo("Duração")?.let { append("\nDuração: $it") }
            infos.getInfo("Fansub")?.let { append("\nFansub: $it") }
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = nextPageSelector()
    override fun latestUpdatesSelector() = episodeListSelector()

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("h3")!!.text()
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos?page=$page")

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_VALUES
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

    override fun getFilterList() = AVFilters.FILTER_LIST

    // ============================= Utilities ==============================
    // i'll leave this here just in case the source starts requiring logins again
    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if ("/login" in response.request.url.toString()) {
            response.close()
            throw IOException(ERROR_LOGIN_MISSING)
        }

        return response
    }

    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    private fun getRealDoc(document: Document): Document {
        val originalUrl = document.location()
        if ("/episodio-" in originalUrl || "/filme-" in originalUrl) {
            val url = document.selectFirst("h2.film-name > a")!!.attr("href")
            val req = client.newCall(GET(url)).execute()
            return req.use { it.asJsoup() }
        }
        return document
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Fim da exibição" -> SAnime.COMPLETED
            "Atualmente sendo exibido" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.hasNextPage() = selectFirst(nextPageSelector()) != null

    private fun Element.getInfo(key: String): String? {
        val div = selectFirst("div.item:contains($key)")
            ?: return null

        val elementsA = div.select("a[href]")
        val text = if (elementsA.isEmpty()) {
            val selector = when {
                div.hasClass("w-hide") -> "div.text"
                else -> "span.name"
            }
            div.selectFirst(selector)!!.text().trim()
        } else {
            elementsA.joinToString { it.text().trim() }
        }

        return text.takeIf(String::isNotBlank)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.quality.contains(quality) },
        )
    }

    companion object {
        const val PREFIX_SEARCH = "path:"
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"

        private const val ERROR_LOGIN_MISSING = "Login necessário. " +
            "Abra a WebView, insira os dados de sua conta e realize o login."

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_VALUES = arrayOf("480p", "720p", "1080p", "4K")
    }
}
