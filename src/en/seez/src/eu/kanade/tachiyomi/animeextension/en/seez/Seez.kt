package eu.kanade.tachiyomi.animeextension.en.seez

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.seez.extractors.VidsrcExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Seez : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Seez"

    override val baseUrl = "https://seez.su"

    private val embedUrl = "https://vidsrc.to"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val vrfHelper by lazy { VrfHelper(client, headers) }

    private val apiKey by lazy {
        val jsUrl = client.newCall(GET(baseUrl, headers)).execute().asJsoup()
            .select("script[defer][src]")[1].attr("abs:src")

        val jsBody = client.newCall(GET(jsUrl, headers)).execute().use { it.body.string() }
        Regex("""f="(\w{20,})"""").find(jsBody)!!.groupValues[1]
    }

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/javascript, */*; q=0.01")
        add("Host", "api.themoviedb.org")
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }.build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = TMDB_URL.newBuilder().apply {
            addPathSegment("movie")
            addPathSegment("popular")
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
        }.buildAPIUrl()

        return GET(url, headers = apiHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<TmdbResponse>()

        val animeList = data.results.map { ani ->
            val name = ani.title ?: ani.name ?: "Title N/A"

            SAnime.create().apply {
                title = name
                url = LinkData(ani.id, "movie").toJsonString()
                thumbnail_url = ani.poster_path?.let { IMG_URL + it } ?: FALLBACK_IMG
            }
        }

        return AnimesPage(animeList, data.page < data.total_pages)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Not used")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val collectionFilter = filterList.find { it is CollectionFilter } as CollectionFilter
        val orderFilter = filterList.find { it is OrderFilter } as OrderFilter

        val url = if (query.isNotBlank()) {
            TMDB_URL.newBuilder().apply {
                addPathSegment("search")
                addPathSegment("multi")
                addQueryParameter("query", query)
                addQueryParameter("page", page.toString())
            }.buildAPIUrl()
        } else {
            TMDB_URL.newBuilder().apply {
                addPathSegment(typeFilter.toUriPart())
                addPathSegment(orderFilter.toUriPart())
                if (collectionFilter.state != 0) {
                    addQueryParameter("with_networks", collectionFilter.toUriPart())
                }
                addQueryParameter("language", "en-US")
                addQueryParameter("page", page.toString())
            }.buildAPIUrl()
        }

        return GET(url, headers = apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<TmdbResponse>()

        val animeList = data.results.map { ani ->
            val name = ani.title ?: ani.name ?: "Title N/A"

            SAnime.create().apply {
                title = name
                url = LinkData(ani.id, ani.media_type).toJsonString()
                thumbnail_url = ani.poster_path?.let { IMG_URL + it } ?: FALLBACK_IMG
            }
        }

        return AnimesPage(animeList, data.page < data.total_pages)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("NOTE: Filters are going to be ignored if using search text"),
        TypeFilter(),
        CollectionFilter(),
        OrderFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("Movies", "movie"),
            Pair("TV-shows", "tv"),
        ),
    )

    private class CollectionFilter : UriPartFilter(
        "Collection",
        arrayOf(
            Pair("<select>", ""),
            Pair("Netflix", "213"),
            Pair("HBO Max", "49"),
            Pair("Paramount+", "4330"),
            Pair("Disney+", "2739"),
            Pair("Apple TV+", "2552"),
            Pair("Prime Video", "1024"),
        ),
    )

    private class OrderFilter : UriPartFilter(
        "Order by",
        arrayOf(
            Pair("Popular", "popular"),
            Pair("Top", "top_rated"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val data = json.decodeFromString<LinkData>(anime.url)

        val url = TMDB_URL.newBuilder().apply {
            addPathSegment(data.media_type)
            addPathSegment(data.id.toString())
            addQueryParameter("append_to_response", "videos,credits,recommendations")
        }.buildAPIUrl()

        return GET(url, headers = apiHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = response.parseAs<TmdbDetailsResponse>()

        return SAnime.create().apply {
            genre = data.genres?.joinToString(", ") { it.name }
            description = buildString {
                if (data.overview != null) {
                    append(data.overview)
                    append("\n\n")
                }
                if (data.release_date != null) append("Release date: ${data.release_date}")
                if (data.first_air_date != null) append("\nFirst air date: ${data.first_air_date}")
                if (data.last_air_date != null) append("\nLast air date: ${data.last_air_date}")
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<TmdbDetailsResponse>()
        val episodeList = mutableListOf<SEpisode>()

        if (data.title != null) { // movie
            episodeList.add(
                SEpisode.create().apply {
                    name = "Movie"
                    date_upload = parseDate(data.release_date!!)
                    episode_number = 1F
                    url = "/movie/${data.id}"
                },
            )
        } else {
            data.seasons.filter { t -> t.season_number != 0 }.forEach { season ->
                val seasonUrl = TMDB_URL.newBuilder().apply {
                    addPathSegment("tv")
                    addPathSegment(data.id.toString())
                    addPathSegment("season")
                    addPathSegment(season.season_number.toString())
                }.buildAPIUrl()

                val seasonData = client.newCall(
                    GET(seasonUrl, headers = apiHeaders),
                ).execute().parseAs<TmdbSeasonResponse>()

                seasonData.episodes.forEach { ep ->
                    episodeList.add(
                        SEpisode.create().apply {
                            name = "Season ${season.season_number} Ep. ${ep.episode_number} - ${ep.name}"
                            date_upload = ep.air_date?.let(::parseDate) ?: 0L
                            episode_number = ep.episode_number.toFloat()
                            url = "/tv/${data.id}/${season.season_number}/${ep.episode_number}"
                        },
                    )
                }
            }
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================

    private val vidsrcExtractor by lazy { VidsrcExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }

    override fun videoListRequest(episode: SEpisode): Request {
        val docHeaders = headers.newBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            add("Host", embedUrl.toHttpUrl().host)
            add("Referer", "$baseUrl/")
        }.build()

        return GET("$embedUrl/embed${episode.url}", headers = docHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }

        val sourcesHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Host", embedUrl.toHttpUrl().host)
            add("Referer", response.request.url.toString())
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val dataId = document.selectFirst("ul.episodes li a[data-id]")!!.attr("data-id")
        val sources = client.newCall(
            GET("$embedUrl/ajax/embed/episode/$dataId/sources", headers = sourcesHeaders),
        ).execute().parseAs<EmbedSourceList>().result

        val urlList = sources.map {
            val encrypted = client.newCall(
                GET("$embedUrl/ajax/embed/source/${it.id}", headers = sourcesHeaders),
            ).execute().parseAs<EmbedUrlResponse>().result.url

            Pair(vrfHelper.decrypt(encrypted), it.title)
        }

        return urlList.parallelMap {
            val url = it.first
            val name = it.second

            runCatching {
                when (name) {
                    "Vidplay" -> vidsrcExtractor.videosFromUrl(url, name)
                    "Filemoon" -> filemoonExtractor.videosFromUrl(url)
                    else -> emptyList()
                }
            }.getOrElse { emptyList() }
        }.flatten().ifEmpty { throw Exception("Failed to fetch videos") }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(server) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun HttpUrl.Builder.buildAPIUrl(): String = this.apply {
        addQueryParameter("api_key", apiKey)
    }.build().toString()

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = use { it.body.string() }
        return json.decodeFromString(responseBody)
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        private val TMDB_URL = "https://api.themoviedb.org/3".toHttpUrl()
        private val IMG_URL = "https://image.tmdb.org/t/p/w300/"
        private val FALLBACK_IMG = "https://seez.su/fallback.png"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vidplay"
    }
    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = arrayOf("Vidplay", "Filemoon")
            entryValues = arrayOf("Vidplay", "Filemoon")
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
