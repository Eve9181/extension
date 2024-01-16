package eu.kanade.tachiyomi.animeextension.pt.animesdigital

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimesDigital : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Animes Digital"

    override val baseUrl = "https://animesdigital.org"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)
    override fun popularAnimeSelector() = latestUpdatesSelector()
    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos/page/$page")

    override fun latestUpdatesSelector() = "div.b_flex:nth-child(2) > div.itemE > a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.let {
            it.attr("data-lazy-src").ifEmpty { it.attr("src") }
        }
        title = element.selectFirst("span.title_anime")!!.text()
    }

    override fun latestUpdatesNextPageSelector() = "ul > li.next"

    // =============================== Search ===============================
    override fun getFilterList() = AnimesDigitalFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/a/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    private val searchToken by lazy {
        client.newCall(GET("$baseUrl/animes-legendado")).execute()
            .use {
                it.asJsoup().selectFirst("div.menu_filter_box")!!.attr("data-secury")
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimesDigitalFilters.getSearchParameters(filters)
        val body = FormBody.Builder().apply {
            add("type", "lista")
            add("limit", "30")
            add("token", searchToken)
            if (query.isNotEmpty()) {
                add("search", query)
            }
            add("pagina", "$page")
            val filterData = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("type_url", params.type)
                addQueryParameter("filter_audio", params.audio)
                addQueryParameter("filter_letter", params.initialLetter)
                addQueryParameter("filter_order", "name")
            }.build().encodedQuery.orEmpty()

            val genres = params.genres.joinToString { "\"$it\"" }
            val delgenres = params.deleted_genres.joinToString { "\"$it\"" }

            add("filters", """{"filter_data": "$filterData", "filter_genre_add": [$genres], "filter_genre_del": [$delgenres]}""")
        }.build()

        return POST("$baseUrl/func/listanime", body = body, headers = headers)
    }

    override fun searchAnimeSelector() = "div.itemA > a"

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeParse(response: Response): AnimesPage {
        return runCatching {
            val data = response.parseAs<SearchResponseDto>()
            val animes = data.results.map(Jsoup::parse)
                .mapNotNull { it.selectFirst(searchAnimeSelector()) }
                .map(::searchAnimeFromElement)
            val hasNext = data.total_page > data.page
            AnimesPage(animes, hasNext)
        }.getOrElse { AnimesPage(emptyList(), false) }
    }

    @Serializable
    data class SearchResponseDto(
        val results: List<String>,
        val page: Int,
        val total_page: Int,
    )

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)
        setUrlWithoutDomain(doc.location())
        thumbnail_url = doc.selectFirst("div.poster > img")?.attr("data-lazy-src")
        status = when (doc.selectFirst("div.clw > div.playon")?.text()) {
            "Em Lançamento" -> SAnime.ONGOING
            "Completo" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        val infos = doc.selectFirst("div.crw > div.dados")!!

        artist = infos.getInfo("Estúdio")
        author = infos.getInfo("Autor") ?: infos.getInfo("Diretor")

        title = infos.selectFirst("h1")!!.text()
        genre = infos.select("div.genre a").eachText().joinToString()

        description = infos.selectFirst("div.sinopse")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealDoc(response.use { it.asJsoup() })
        val pagination = doc.selectFirst("ul.content-pagination")
        return if (pagination != null) {
            val episodes = mutableListOf<SEpisode>()
            episodes += doc.select(episodeListSelector()).map(::episodeFromElement)
            val lastPage = doc.selectFirst("ul.content-pagination > li:nth-last-child(2) > span")!!.text().toInt()
            for (i in 2..lastPage) {
                val request = GET(doc.location() + "/page/$i", headers)
                val res = client.newCall(request).execute()
                val pageDoc = res.use { it.asJsoup() }
                episodes += pageDoc.select(episodeListSelector()).map(::episodeFromElement)
            }
            episodes
        } else {
            doc.select(episodeListSelector()).map(::episodeFromElement)
        }
    }

    override fun episodeListSelector() = "div.item_ep > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val epname = element.selectFirst("div.episode")!!.text()
        episode_number = epname.substringAfterLast(" ").toFloatOrNull() ?: 1F
        name = buildString {
            append(epname)
            element.selectFirst("div.sub_title")?.text()?.let {
                if (!it.contains("Ainda não tem um titulo oficial")) {
                    append(" - $it")
                }
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val player = response.use { it.asJsoup() }.selectFirst("div#player")!!
        return player.select("div.tab-video").flatMap { div ->
            div.select(videoListSelector()).flatMap { element ->
                runCatching {
                    videosFromElement(element)
                }.onFailure { it.printStackTrace() }.getOrElse { emptyList() }
            }
        }
    }

    private fun videosFromElement(element: Element): List<Video> {
        return when (element.tagName()) {
            "iframe" -> {
                val url = element.attr("data-lazy-src").ifEmpty { element.attr("src") }
                    .let {
                        when {
                            it.startsWith("/") -> baseUrl + it
                            else -> it
                        }
                    }
                client.newCall(GET(url, headers)).execute()
                    .use { it.asJsoup() }
                    .select(videoListSelector())
                    .flatMap(::videosFromElement)
            }
            "script" -> {
                val scriptData = element.data().let {
                    when {
                        "eval(function" in it -> Unpacker.unpack(it)
                        else -> it
                    }
                }.ifEmpty { null }?.replace("\\", "")
                scriptData?.let(::videosFromScript).orEmpty()
            }
            else -> emptyList()
        }
    }

    private fun videosFromScript(script: String): List<Video> {
        return script.substringAfter("sources:").substringAfter(".src(")
            .substringBefore(")")
            .substringAfter("[")
            .substringBefore("]")
            .split("{")
            .drop(1)
            .map {
                val quality = it.substringAfter("label", "")
                    .substringAfterKey()
                    .ifEmpty { name }
                val url = it.substringAfter("file").substringAfter("src")
                    .substringAfterKey()
                Video(url, quality, url, headers)
            }
    }

    override fun videoListSelector() = "iframe, script:containsData(eval), script:containsData(player.src), script:containsData(this.src), script:containsData(sources:)"

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
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.let(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    private fun getRealDoc(document: Document): Document {
        return document.selectFirst("div.subitem > a:contains(menu)")?.let { link ->
            client.newCall(GET(link.attr("href")))
                .execute()
                .use { it.asJsoup() }
        } ?: document
    }

    private fun Element.getInfo(key: String): String? {
        return selectFirst("div.info:has(span:containsOwn($key))")?.run {
            ownText()
                .trim()
                .takeUnless { it.isBlank() || it == "?" }
        }
    }

    private fun String.substringAfterKey() = substringAfter(":")
        .substringAfter('"')
        .substringBefore('"')
        .substringAfter("'")
        .substringBefore("'")

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "480p", "720p")
    }
}
