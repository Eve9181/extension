package eu.kanade.tachiyomi.animeextension.fr.franime

import android.net.UrlQuerySanitizer
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Anime
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.mytvextractor.MytvExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.net.URI
import kotlin.io.path.Path
import kotlin.io.path.name

class FrAnime : AnimeHttpSource() {

    override val name = "FRAnime"

    private val domain = "franime.fr"

    override val baseUrl = "https://$domain"

    private val baseApiUrl = "https://api.$domain/api"
    private val baseApiAnimeUrl = "$baseApiUrl/anime"

    override val lang = "fr"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val database by lazy {
        client.newCall(GET("$baseApiUrl/animes/")).execute()
            .use { it.body.string() }
            .let { json.decodeFromString<Array<Anime>>(it) }
    }

    // === Anime Details

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> = Observable.just(anime)

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("not used")

    // === Episodes

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val stem = Path(URI(anime.url).path).name // WILL break on API update ! name becomes fileName in new versions !
        val language = UrlQuerySanitizer(anime.url).getValue("lang")
        val season = UrlQuerySanitizer(anime.url).getValue("s").toInt()
        val animeData = database.first { titleToUrl(it.originalTitle) == stem }
        val episodes = mutableListOf<SEpisode>()
        animeData.seasons[season - 1].episodes.forEachIndexed { index, episode ->
            val players = (if (language == "vo") episode.languages.vo else episode.languages.vf).players
            if (players.isNotEmpty()) {
                episodes += SEpisode.create().apply {
                    setUrlWithoutDomain(anime.url + "&ep=${index + 1}")
                    name = episode.title
                    episode_number = index.toFloat()
                }
            }
        }
        return Observable.just(episodes.sortedByDescending { it.episode_number })
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("not used")

    // === Players

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val seasonNumber = UrlQuerySanitizer(episode.url).getValue("s").toInt()
        val episodeNumber = UrlQuerySanitizer(episode.url).getValue("ep").toInt()
        val episodeLang = UrlQuerySanitizer(episode.url).getValue("lang")
        val stem = Path(URI(episode.url).path).name
        val animeData = database.first { titleToUrl(it.originalTitle) == stem }
        val episodeData = animeData.seasons[seasonNumber - 1].episodes[episodeNumber - 1]
        val videoBaseUrl = "$baseApiAnimeUrl/${animeData.id}/${seasonNumber - 1}/${episodeNumber - 1}"

        val videos = mutableListOf<Video>()
        val players = if (episodeLang == "vo") episodeData.languages.vo.players else episodeData.languages.vf.players

        players.forEachIndexed { index, playerName ->
            val apiUrl = "$videoBaseUrl/$episodeLang/$index"
            val playerUrl = client.newCall(GET(apiUrl)).execute().body.string()
            val playerVideos = when (playerName) {
                "franime_myvi" -> listOf(Video(playerUrl, "FRAnime", playerUrl))
                "myvi" -> MytvExtractor(client).videosFromUrl(playerUrl)
                "sendvid" -> SendvidExtractor(client, headers).videosFromUrl(playerUrl)
                "sibnet" -> SibnetExtractor(client).videosFromUrl(playerUrl)
                "sbfull" -> StreamSBExtractor(client).videosFromUrl(playerUrl, headers)
                else -> null
            }
            if (playerVideos != null) videos.addAll(playerVideos)
        }
        return Observable.just(videos)
    }

    // === Latest

    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> {
        val pages = database.reversed().toList().chunked(50)
        val hasNextPage = pages.size > page
        val entries = pageToSAnimes(pages.getOrNull(page - 1))
        return Observable.just(AnimesPage(entries, hasNextPage))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("not used")

    // === Popular

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        val pages = database.sortedByDescending { it.note }.chunked(50)
        val hasNextPage = pages.size > page
        val entries = pageToSAnimes(pages.getOrNull(page - 1))
        return Observable.just(AnimesPage(entries, hasNextPage))
    }

    override fun popularAnimeParse(response: Response) = throw Exception("not used")

    override fun popularAnimeRequest(page: Int) = throw Exception("not used")

    // === Search

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val pages = database.filter {
            it.title.contains(query, true) ||
                it.originalTitle.contains(query, true) ||
                it.titlesAlt.en?.contains(query, true) == true ||
                it.titlesAlt.enJp?.contains(query, true) == true ||
                it.titlesAlt.jaJp?.contains(query, true) == true ||
                titleToUrl(it.originalTitle).contains(query)
        }.chunked(50)
        val hasNextPage = pages.size > page
        val entries = pageToSAnimes(pages.getOrNull(page - 1))
        return Observable.just(AnimesPage(entries, hasNextPage))
    }

    override fun searchAnimeParse(response: Response): AnimesPage = throw Exception("not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    // === Utils

    private fun titleToUrl(title: String): String = Regex("[^A-Za-z0-9 ]").replace(title, "").replace(" ", "-").lowercase()

    private fun pageToSAnimes(page: List<Anime>?): List<SAnime> {
        val entries = mutableListOf<SAnime>()
        page?.forEach {
            for ((index, season) in it.seasons.withIndex()) {
                val seasonTitle = it.title + if (it.seasons.size > 1) " S${index + 1}" else ""
                val hasVostfr = season.episodes.fold(false) { v, e -> v or e.languages.vo.players.isNotEmpty() }
                val hasVf = season.episodes.fold(false) { v, e -> v or e.languages.vf.players.isNotEmpty() }
                // I want to die for writing this
                if (hasVostfr) {
                    entries += SAnime.create().apply {
                        title = seasonTitle + if (hasVf) " (VOSTFR)" else ""
                        thumbnail_url = it.poster
                        genre = it.genres.joinToString()
                        status = parseStatus(it.status, it.seasons.size, index + 1)
                        description = it.description
                        setUrlWithoutDomain("/anime/${titleToUrl(it.originalTitle)}?lang=vo&s=${index + 1}")
                        initialized = true
                    }
                }
                if (hasVf) {
                    entries += SAnime.create().apply {
                        title = seasonTitle + if (hasVostfr) " (VF)" else ""
                        thumbnail_url = it.poster
                        genre = it.genres.joinToString()
                        status = parseStatus(it.status, it.seasons.size, index + 1)
                        description = it.description
                        setUrlWithoutDomain("/anime/${titleToUrl(it.originalTitle)}?lang=vf&s=${index + 1}")
                        initialized = true
                    }
                }
            }
        }
        return entries
    }

    private fun parseStatus(statusString: String?, seasonCount: Int = 1, season: Int = 1): Int {
        if (season < seasonCount) return SAnime.COMPLETED
        return when (statusString?.trim()) {
            "EN COURS" -> SAnime.ONGOING
            "TERMINÉ" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }
}
