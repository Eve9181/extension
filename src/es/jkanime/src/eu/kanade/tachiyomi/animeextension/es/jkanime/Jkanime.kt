package eu.kanade.tachiyomi.animeextension.es.jkanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.jkanime.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.es.jkanime.extractors.OkruExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Jkanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Jkanime"

    override val baseUrl = "https://jkanime.net"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.col-lg-12 div.list"

    override fun popularAnimeRequest(page: Int): Request = GET("https://jkanime.net/top/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div#conb a").attr("href")
        )
        anime.title = element.select("div#conb a").attr("title")
        anime.thumbnail_url = element.select("div#conb a img").attr("src")
        anime.description = element.select("div#conb div#animinfo p").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "uwu"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val episodeLink = response.request.url
        val pageBody = response.asJsoup()
        val animeId = pageBody.select("div.anime__details__text div.anime__details__title div#guardar-anime.btn.btn-light.btn-sm.ml-2")
            .attr("data-anime")

        val pageNumber = pageBody.select("div.anime__pagination a")
        val lastPage = pageNumber.last()?.attr("href")
            ?.replace("#pag", "")
        val firstPage = pageNumber.first()?.attr("href")
            ?.replace("#pag", "")
        Log.i("bruh", "ULTIMA: $lastPage")

        if (firstPage != lastPage) {
            var checkLast = 0
            for (i in 1 until lastPage?.toInt()!!) {
                Log.i("bruh", "aaa")
                for (j in 1..12) {
                    // Log.i("bruh", (j + checkLast).toString())
                    val episode = SEpisode.create().apply {
                        episode_number = (j + checkLast).toFloat()
                        name = "Episodio ${j + checkLast}"
                        date_upload = System.currentTimeMillis()
                    }
                    episode.setUrlWithoutDomain("$episodeLink/${j + checkLast}")
                    episodes.add(episode)
                }
                checkLast += 12
            }
            Jsoup.connect("https://jkanime.net/ajax/pagination_episodes/$animeId/$lastPage").get()
                .body().select("body").text().replace("}]", "").split("}").forEach { json ->
                    val number = json.substringAfter("\"number\":\"").substringBefore("\"")
                    Log.i("bruh", number)
                    val episode = SEpisode.create().apply {
                        episode_number = number.toFloat()
                        name = "Episodio $number"
                        date_upload = System.currentTimeMillis()
                    }
                    episode.setUrlWithoutDomain("$episodeLink/$number")
                    episodes.add(episode)
                }
        }

        if (firstPage == lastPage) {
            Jsoup.connect("https://jkanime.net/ajax/pagination_episodes/$animeId/$lastPage").get()
                .body().select("body").text().replace("}]", "").split("}").forEach { json ->
                    val number = json.substringAfter("\"number\":\"").substringBefore("\"")
                    Log.i("bruh", number)
                    val episode = SEpisode.create().apply {
                        episode_number = number.toFloat()
                        name = "Episodio $number"
                        date_upload = System.currentTimeMillis()
                    }
                    episode.setUrlWithoutDomain("$episodeLink/$number")
                    episodes.add(episode)
                }
        }
        return episodes.reversed()
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val episodeLink = response.request.url.toString()
        val videos = mutableListOf<Video>()
        document.select("div.col-lg-12.rounded.bg-servers.text-white.p-3.mt-2 a").forEach { it ->
            val server = it.text()
            val serverId = it.attr("data-id")
            document.select("script").forEach { script ->
                if (script.data().contains("var video = [];")) {
                    val url = (
                        baseUrl + script.data().substringAfter("video[$serverId] = '<iframe class=\"player_conte\" src=\"")
                            .substringBefore("\"")
                        )
                        .replace("$baseUrl/jkfembed.php?u=", "https://embedsito.com/v/")
                        .replace("$baseUrl/jkokru.php?u=", "http://ok.ru/videoembed/")
                        .replace("$baseUrl/jkvmixdrop.php?u=", "https://mixdrop.co/e/")
                        .replace("$baseUrl/jk.php?u=", "$baseUrl/")
                    if (url.contains("um2")) {
                        val doc = Jsoup.connect(url).referrer(episodeLink).get()
                        val dataKey = doc.select("form input[value]").attr("value")
                        Jsoup.connect("$baseUrl/gsplay/redirect_post.php").headers(
                            mapOf(
                                "Host" to "jkanime.net",
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                "Accept-Language" to "en-US,en;q=0.5",
                                "Referer" to url,
                                "Content-Type" to "application/x-www-form-urlencoded",
                                "Origin" to "https://jkanime.net",
                                "DNT" to "1",
                                "Connection" to "keep-alive",
                                "Upgrade-Insecure-Requests" to "1",
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "same-origin",
                                "TE" to "trailers",
                                "Pragma" to "no-cache",
                                "Cache-Control" to "no-cache",
                            )
                        ).data(mapOf("data" to dataKey)).method(Connection.Method.POST).followRedirects(false)
                            .execute().headers("location").forEach { loc ->
                                val postkey = loc.replace("/gsplay/player.html#", "")
                                val nozomitext = Jsoup.connect("https://jkanime.net/gsplay/api.php").method(Connection.Method.POST).data("v", postkey).ignoreContentType(true).execute().body()
                                nozomitext.toString().split("}").forEach { file ->
                                    val nozomiUrl = file.substringAfter("\"file\":\"").substringBefore("\"").replace("\\", "")
                                    if (nozomiUrl.isNotBlank() && !nozomiUrl.contains("{")) {
                                        videos.add(Video(nozomiUrl, server, nozomiUrl, null))
                                    }
                                }
                            }
                    }

                    when {
                        "embedsito" in url -> FembedExtractor().videosFromUrl(url).forEach { videos.add(it) }
                        "ok" in url -> OkruExtractor(client).videosFromUrl(url).forEach { videos.add(it) }
                        "stream/jkmedia" in url -> videos.add(Video(url, "Xtreme S", url, null))
                        "um.php" in url -> videos.add(JkanimeExtractor().videoFromUrl(url, server))
                    }
                }
            }
        }
        return videos
    }

    private class JkanimeExtractor() {
        fun videoFromUrl(url: String, server: String): Video {
            var url1 = ""
            Jsoup.connect(url).get().body().select("script").forEach {
                if (it.data().contains("var parts = {")) {
                    url1 = it.data().substringAfter("url: '").substringBefore("'")
                }
            }
            return Video(url1, server, url1, null)
        }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Nozomi")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar/$query/$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/genero/${genreFilter.toUriPart()}/$page")
            else -> GET("$baseUrl/directorio/$page ")
        }
    }
    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.anime__item a").attr("href")
        )
        anime.title = element.select("div.anime__item div#ainfo div.title").text()
        anime.thumbnail_url = element.select("div.anime__item a div").attr("data-setbg")
        anime.description = element.select("div.anime__item#ainfo p").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = "div.row div.col-lg-2.col-md-6.col-sm-6"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.col-lg-3 div.anime__details__pic.set-bg").attr("data-setbg")
        anime.title = document.selectFirst("div.anime__details__text div.anime__details__title h3").text()
        anime.description = document.select("div.col-lg-9 div.anime__details__text p").first().ownText()
        document.select("div.row div.col-lg-6.col-md-6 ul li").forEach { animeData ->
            val data = animeData.select("span").text()
            if (data.contains("Genero")) {
                anime.genre = animeData.select("a").joinToString { it.text() }
            }
            if (data.contains("Estado")) {
                anime.status = parseStatus(animeData.select("span").text())
            }
            if (data.contains("Studios")) {
                anime.author = animeData.select("a").text()
            }
        }

        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Concluido") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "div.container div.navigation a.text.nav-next"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.row.g-0 div.col-md-5.custom_thumb2 a").attr("href")
        )
        anime.title = element.select("div.row.g-0 div.col-md-7 div.card-body h5.card-title a").text()
        anime.thumbnail_url = element.select("div.row.g-0 div.col-md-5.custom_thumb2 a img").attr("src")
        anime.description = element.select("div.row.g-0 div.col-md-7 div.card-body p.card-text.synopsis").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("https://jkanime.net/directorio/$page/?filtro=fecha&tipo=none&estado=none&fecha=none&temporada=none&orden=desc")

    override fun latestUpdatesSelector(): String = "div.card.mb-3.custom_item2"

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Generos",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Español Latino", "latino")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Nozomi", "Desu", "Xtreme S", "Fembed:480p", "Fembed:720p", "Fembed:1080p", "Okru: full", "Okru: sd", "Okru: low", "Okru: lowest", "Okru: mobile")
            entryValues = arrayOf("Nozomi", "Desu", "Xtreme S", "Fembed:480p", "Fembed:720p", "Fembed:1080p", "Okru: full", "Okru: sd", "Okru: low", "Okru: lowest", "Okru: mobile")
            setDefaultValue("Nozomi")
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
