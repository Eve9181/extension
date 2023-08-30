package eu.kanade.tachiyomi.animeextension.it.toonitalia

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.it.toonitalia.extractors.MaxStreamExtractor
import eu.kanade.tachiyomi.animeextension.it.toonitalia.extractors.StreamZExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Toonitalia : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Toonitalia"

    override val baseUrl = "https://toonitalia.green"

    override val lang = "it"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/page/$page", headers = headers)
    }

    override fun popularAnimeSelector(): String = "div#primary > main#main > article"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("h2 > a").text()
        anime.thumbnail_url = element.selectFirst("img")!!.attr("src")
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").substringAfter(baseUrl))
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.nav-links > span.current ~ a"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = if (response.request.url.toString().substringAfter(baseUrl).startsWith("/?s=")) {
            document.select(searchAnimeSelector()).map { element ->
                searchAnimeFromElement(element)
            }
        } else {
            document.select(searchIndexAnimeSelector()).map { element ->
                searchIndexAnimeFromElement(element)
            }
        }

        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/?s=$query", headers = headers)
        } else {
            val url = "$baseUrl".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is IndexFilter -> url.addPathSegment(filter.toUriPart())
                    else -> {}
                }
            }
            var newUrl = url.toString()
            if (page > 1) {
                newUrl += "/?lcp_page0=$page#lcp_instance_0"
            }
            GET(newUrl, headers = headers)
        }
    }

    override fun searchAnimeSelector(): String = "section#primary > main#main > article"

    private fun searchIndexAnimeSelector(): String = "div.entry-content > ul.lcp_catlist > li"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.selectFirst("h2")!!.text()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").substringAfter(baseUrl))
        return anime
    }

    private fun searchIndexAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("a").text()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").substringAfter(baseUrl))
        return anime
    }

    override fun searchAnimeNextPageSelector() =
        "nav.navigation div.nav-previous, " + // Normal search
            "ul.lcp_paginator > li > a.lcp_nextlink" // Index search

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.entry-title")!!.text()
        thumbnail_url = document.selectFirst("header.entry-header img")!!.attr("abs:src")

        // Cursed sources should have cursed code!
        description = document.selectFirst("article > div.entry-content")!!
            .also { it.select("center").remove() } // Remove unnecessary data
            .wholeText()
            .replace(",", ", ").replace("  ", " ") // Fix text
            .lines()
            .map(String::trim)
            .filterNot { it.startsWith("Titolo:") }
            .also { lines ->
                genre = lines.firstOrNull { it.startsWith("Genere:") }
                    ?.substringAfter("Genere: ")
            }
            .joinToString("\n")
            .substringAfter("Trama: ")
    }

    // ============================== Episodes ==============================
    private val episodeNumRegex by lazy { Regex("\\s(\\d+x\\d+)\\s?") }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.use { it.asJsoup() }
        val url = doc.location()

        if ("/film-anime/" in url) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain("$url#0")
                    episode_number = 1F
                    name = doc.selectFirst("h1.entry-title")!!.text()
                },
            )
        }

        val epNames = doc.select(episodeListSelector() + ">td:not(:has(a))").eachText()
        return epNames.mapIndexed { index, item ->
            SEpisode.create().apply {
                setUrlWithoutDomain("$url#$index")
                val (season, episode) = episodeNumRegex.find(item)
                    ?.groupValues
                    ?.last()
                    ?.split("x")
                    ?: listOf("01", "01")
                name = "Stagione $season - Episodi $episode"
                episode_number = "$season.${episode.padStart(3, '0')}".toFloatOrNull() ?: 1F
            }
        }.reversed()
    }

    override fun episodeFromElement(element: Element) = throw Exception("Not used")

    override fun episodeListSelector() = "article > div.entry-content table tr:has(a)"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }
        val episodeNumber = response.request.url.fragment!!.toInt()

        val episode = document.select(episodeListSelector())
            .getOrNull(episodeNumber)
            ?: return emptyList()

        return episode.select("a").flatMap {
            runCatching {
                val url = it.attr("href")
                val hosterUrl = when {
                    url.contains("uprot.net") -> bypassUprot(url)
                    else -> url
                }
                hosterUrl?.let(::extractVideos)
            }.getOrNull() ?: emptyList()
        }
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not used")

    override fun videoListSelector(): String = throw Exception("Not used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not used")

    // ============================= Utilities ==============================
    private fun bypassUprot(url: String): String? =
        client.newCall(GET(url, headers)).execute()
            .use { it.asJsoup() }
            .selectFirst("a:has(button.button.is-info)")
            ?.attr("href")

    private val voeExtractor by lazy { VoeExtractor(client) }
    private val streamZExtractor by lazy { StreamZExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val maxStreamExtractor by lazy { MaxStreamExtractor(client, headers) }

    private fun extractVideos(url: String): List<Video> =
        when {
            "https://voe.sx" in url -> voeExtractor.videoFromUrl(url)?.let(::listOf)
            "https://streamtape" in url -> streamTapeExtractor.videoFromUrl(url)?.let(::listOf)
            "https://maxstream" in url -> maxStreamExtractor.videosFromUrl(url)
            "https://streamz" in url || "streamz.cc" in url -> {
                streamZExtractor.videoFromUrl(url, "StreamZ")?.let(::listOf)
            }
            else -> null
        } ?: emptyList()

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTA: ignorato se si utilizza la ricerca di testo!"),
        AnimeFilter.Separator(),
        IndexFilter(getIndexList()),
    )

    private class IndexFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Indice", vals)

    private fun getIndexList() = arrayOf(
        Pair("<selezionare>", ""),
        Pair("Anime", "anime"),
        Pair("Anime Sub-ita", "anime-sub-ita"),
        Pair("Serie Tv", "serie-tv"),
        Pair("Film Animazione", "film-animazione"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "VOE")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(server) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "80")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val serverPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("StreamZ", "VOE", "StreamZ Sub-Ita", "VOE Sub-Ita")
            entryValues = arrayOf("StreamZ", "VOE", "StreamZ Sub-Ita", "VOE Sub-Ita")
            setDefaultValue("StreamZ")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(serverPref)
    }
}
