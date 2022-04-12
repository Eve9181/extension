package eu.kanade.tachiyomi.animeextension.ru.animevost

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ru.animevost.dto.AnimeDetailsDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Animevost : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    private enum class SortBy(val by: String) {
        RATING("rating"),
        DATE("date"),
        NEWS_READ("news_read"),
        COMM_NUM("comm_num"),
        TITLE("title"),
    }

    private enum class SortDirection(val direction: String) {
        ASC("asc"),
        DESC("desc"),
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val name = "Animevost"

    override val baseUrl = "https://animevost.org"

    private val baseApiUrl = "https://api.animevost.org"

    override val lang = "ru"

    override val supportsLatest = true

    private val animeSelector = "div.shortstoryContent"

    private val nextPageSelector = "td.block_4 span:not(.nav_ext) + a"

    private fun animeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("table div > a").attr("href"))
        anime.thumbnail_url = baseUrl + element.select("table div > a img").attr("src")
        anime.title = element.select("table div > a img").attr("alt")
        return anime
    }

    private fun animeRequest(page: Int, sortBy: SortBy, sortDirection: SortDirection = SortDirection.DESC): Request {
        val headers: Headers =
            Headers.headersOf("Content-Type", "application/x-www-form-urlencoded", "charset", "UTF-8")
        val body = FormBody.Builder()
            .add("dlenewssortby", sortBy.by)
            .add("dledirection", sortDirection.direction)
            .add("set_new_sort", "dle_sort_main")
            .add("set_direction_sort", "dle_direction_main")
            .build()

        return POST("$baseUrl/page/$page", headers, body)
    }

    private fun parseAnimeIdFromUrl(url: String): String = url.split("/").last().split("-").first()

    // Anime details

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        val animeId = parseAnimeIdFromUrl(anime.url)

        return client.newCall(GET("$baseApiUrl/animevost/api/v0.2/GetInfo/$animeId"))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response).apply { initialized = true }
            }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val animeData = json.decodeFromString(AnimeDetailsDto.serializer(), response.body!!.string()).data?.first()
        val anime = SAnime.create().apply {
            title = animeData?.title!!

            if (animeData.preview != null) {
                thumbnail_url = "$baseUrl/" + animeData.preview
            }

            author = animeData.director
            description = animeData.description

            if (animeData.timer != null) {
                status = if (animeData.timer > 0) SAnime.ONGOING else SAnime.COMPLETED
            }

            genre = animeData.genre
        }

        return anime
    }

    override fun animeDetailsParse(document: Document) = throw Exception("not used")

    // Episode

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = parseAnimeIdFromUrl(anime.url)

        return GET("$baseApiUrl/animevost/api/v0.2/GetInfo/$animeId")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeData = json.decodeFromString(AnimeDetailsDto.serializer(), response.body!!.string()).data?.first()

        val episodeList = mutableListOf<SEpisode>()

        if (animeData?.series != null) {
            val series = Json.parseToJsonElement(animeData.series.replace("'", "\"")).jsonObject.toMap()

            series.entries.forEachIndexed { index, entry ->
                episodeList.add(
                    SEpisode.create().apply {
                        val id = entry.value.toString().replace("\"", "")
                        name = entry.key
                        episode_number = index.toFloat()
                        date_upload = System.currentTimeMillis()
                        url = "/frame5.php?play=$id&old=1"
                    }
                )
            }
        }

        return episodeList
    }

    // Latest

    override fun latestUpdatesFromElement(element: Element) = animeFromElement(element)

    override fun latestUpdatesNextPageSelector() = nextPageSelector

    override fun latestUpdatesRequest(page: Int) = animeRequest(page, SortBy.DATE)

    override fun latestUpdatesSelector() = animeSelector

    // Popular Anime

    override fun popularAnimeFromElement(element: Element) = animeFromElement(element)

    override fun popularAnimeNextPageSelector() = nextPageSelector

    override fun popularAnimeRequest(page: Int) = animeRequest(page, SortBy.RATING)

    override fun popularAnimeSelector() = animeSelector

    // Search

    override fun searchAnimeFromElement(element: Element) = animeFromElement(element)

    override fun searchAnimeNextPageSelector() = nextPageSelector

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchStart = if (page <= 1) 0 else page
        val resultFrom = (page - 1) * 10 + 1
        val headers: Headers =
            Headers.headersOf("Content-Type", "application/x-www-form-urlencoded", "charset", "UTF-8")
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("search_start", searchStart.toString())
            .add("full_search", "0")
            .add("result_from", resultFrom.toString())
            .add("story", query)
            .build()

        return POST("$baseUrl/index.php?do=search", headers, body)
    }

    override fun searchAnimeSelector() = animeSelector

    // Video

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = response.asJsoup()

        val videoData = document.html().substringAfter("file\":\"").substringBefore("\",").split(",")

        videoData.forEach {
            val linkData = it.replace("[", "").split("]")
            val quality = linkData.first()
            val url = linkData.last().split(" or").first()
            videoList.add(Video(url, quality, url, null))
        }

        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("720p", "480p")
            entryValues = arrayOf("720", "480")
            setDefaultValue("480")
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
}
