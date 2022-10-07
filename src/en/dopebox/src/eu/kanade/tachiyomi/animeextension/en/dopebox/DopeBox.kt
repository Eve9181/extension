package eu.kanade.tachiyomi.animeextension.en.dopebox

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DopeBox : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "DopeBox"

    override val baseUrl by lazy {
        "https://" + preferences.getString("preferred_domain", "dopebox.to")!!
    }

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    override fun popularAnimeSelector(): String = "div.film_list-wrap div.flw-item div.film-poster"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movie?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        anime.thumbnail_url = element.selectFirst("img").attr("data-src")
        anime.title = element.selectFirst("a").attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item a[title=next]"

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val infoElement = document.select("div.detail_page-watch")
        val id = infoElement.attr("data-id")
        val dataType = infoElement.attr("data-type") // Tv = 2 or movie = 1
        if (dataType == "2") {
            val seasonUrl = "$baseUrl/ajax/v2/tv/seasons/$id"
            val seasonsHtml = client.newCall(
                GET(
                    seasonUrl,
                    headers = Headers.headersOf("Referer", document.location())
                )
            ).execute().asJsoup()
            val seasonsElements = seasonsHtml.select("a.dropdown-item.ss-item")
            seasonsElements.forEach {
                val seasonEpList = parseEpisodesFromSeries(it)
                episodeList.addAll(seasonEpList)
            }
        } else {
            val movieUrl = "$baseUrl/ajax/movie/episodes/$id"
            val episode = SEpisode.create()
            episode.name = document.select("h2.heading-name").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(movieUrl)
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.attr("data-id")
        val seasonName = element.text()
        val episodesUrl = "$baseUrl/ajax/v2/season/episodes/$seasonId"
        val episodesHtml = client.newCall(GET(episodesUrl))
            .execute()
            .asJsoup()
        val episodeElements = episodesHtml.select("div.eps-item")
        return episodeElements.map { episodeFromElement(it, seasonName) }
    }

    private fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        val episodeId = element.attr("data-id")
        val epNum = element.selectFirst("div.episode-number").text()
        val epName = element.selectFirst("h3.film-name a").text()
        val episode = SEpisode.create().apply {
            name = "$seasonName $epNum $epName"
            setUrlWithoutDomain("$baseUrl/ajax/v2/episode/servers/$episodeId")
        }
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        // referers
        val referer1 = response.request.url.toString()
        val refererHeaders = Headers.headersOf("Referer", referer1)
        val referer = response.request.url.encodedPath
        val newHeaders = Headers.headersOf("Referer", referer)

        // get embed id
        val getVidID = document.selectFirst("a:contains(Vidcloud)").attr("data-id")
        val getVidApi = client.newCall(GET("$baseUrl/ajax/get_link/" + getVidID)).execute().asJsoup()

        // streamrapid URL
        val getVideoEmbed = getVidApi.text().substringAfter("link\":\"").substringBefore("\"")
        val videoEmbedUrlId = getVideoEmbed.substringAfterLast("/").substringBefore("?")
        val callVideolink = client.newCall(GET(getVideoEmbed, refererHeaders)).execute().asJsoup()
        val uri = Uri.parse(getVideoEmbed)
        val domain = (Base64.encodeToString((uri.scheme + "://" + uri.host + ":443").encodeToByteArray(), Base64.NO_PADDING) + ".").replace("\n", "")
        val soup = Jsoup.connect(getVideoEmbed).referrer("$baseUrl/").get().toString().replace("\n", "")

        val key = soup.substringAfter("var recaptchaSiteKey = '").substringBefore("',")
        val number = soup.substringAfter("recaptchaNumber = '").substringBefore("';")

        val vToken = Jsoup.connect("https://www.google.com/recaptcha/api.js?render=$key").referrer("https://rabbitstream.net/").get().toString().replace("\n", "").substringAfter("/releases/").substringBefore("/recaptcha")
        val recapToken = Jsoup.connect("https://www.google.com/recaptcha/api2/anchor?ar=1&hl=en&size=invisible&cb=kr60249sk&k=$key&co=$domain&v=$vToken").get().selectFirst("#recaptcha-token")?.attr("value")
        val token = Jsoup.connect("https://www.google.com/recaptcha/api2/reload?k=$key").ignoreContentType(true)
            .data("v", vToken).data("k", key).data("c", recapToken).data("co", domain).data("sa", "").data("reason", "q")
            .post().toString().replace("\n", "").substringAfter("rresp\",\"").substringBefore("\"")

        val jsonLink = "https://rabbitstream.net/ajax/embed-4/getSources?id=$videoEmbedUrlId&_token=$token&_number=$number&sId=test"
        /*val reloadHeaderss = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val iframeResponse = client.newCall(GET(jsonLink, reloadHeaderss))
            .execute().asJsoup()
        */
        return videosFromElement(jsonLink)
    }

    private fun videosFromElement(url: String): List<Video> {
        val reloadHeaderss = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val json = Json.decodeFromString<JsonObject>(Jsoup.connect(url).header("X-Requested-With", "XMLHttpRequest").ignoreContentType(true).execute().body())
        val masterUrl = json["sources"]!!.jsonArray[0].jsonObject["file"].toString().trim('"')
        val subsList = mutableListOf<Track>()
        json["tracks"]!!.jsonArray.forEach {
            val subLang = it.jsonObject["label"].toString().substringAfter("\"").substringBefore("\"")
            val subUrl = it.jsonObject["file"].toString().trim('"')
            try {
                subsList.add(Track(subUrl, subLang))
            } catch (e: Error) {}
        }
        val prefSubsList = subLangOrder(subsList)
        if (masterUrl.contains("playlist.m3u8")) {
            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
            val videoList = mutableListOf<Video>()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("\n") + "p"
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                videoList.add(
                    try {
                        Video(videoUrl, quality, videoUrl, subtitleTracks = prefSubsList)
                    } catch (e: Error) {
                        Video(videoUrl, quality, videoUrl)
                    }
                )
            }
            return videoList
        } else if (masterUrl.contains("index.m3u8")) {
            return listOf(
                try {
                    Video(masterUrl, "Default", masterUrl, subtitleTracks = prefSubsList)
                } catch (e: Error) {
                    Video(masterUrl, "Default", masterUrl)
                }
            )
        } else {
            throw Exception("never give up and try again :)")
        }
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

    private fun subLangOrder(tracks: List<Track>): List<Track> {
        val language = preferences.getString("preferred_subLang", null)
        if (language != null) {
            val newList = mutableListOf<Track>()
            var preferred = 0
            for (track in tracks) {
                if (track.lang.contains(language)) {
                    newList.add(preferred, track)
                    preferred++
                } else {
                    newList.add(track)
                }
            }
            return newList
        }
        return tracks
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        anime.thumbnail_url = element.selectFirst("img").attr("data-src")
        anime.title = element.selectFirst("a").attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[title=next]"

    override fun searchAnimeSelector(): String = "div.film_list-wrap div.flw-item div.film-poster"

    // will be refactored AGAIN when i add all filters
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search/$query?page=$page".replace(" ", "-")
            return GET(url, headers)
        }
        val genreList = filters.filterIsInstance<GenreList>().first() as GenreList
        if (genreList.state > 0) {
            val genre_slug = getGenreList()[genreList.state].query
            val genreUrl = "$baseUrl/genre/$genre_slug"
            return GET(genreUrl, headers)
        } else {
            throw Exception("Choose a filter!")
        }
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create().apply {
            thumbnail_url = document.selectFirst("img.film-poster-img").attr("src")
            title = document.selectFirst("img.film-poster-img").attr("title")
            genre = document.select("div.row-line:contains(Genre) a")
                .joinToString(", ") { it.text() }
            description = document.selectFirst("div.detail_page-watch div.description")
                .text().replace("Overview:", "")
            author = document.select("div.row-line:contains(Production) a")
                .joinToString(", ") { it.text() }
            status = parseStatus(document.select("li.status span.value").text())
        }
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_LIST
            entryValues = PREF_DOMAIN_LIST
            setDefaultValue("dopebox.to")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_LIST
            entryValues = PREF_QUALITY_LIST
            setDefaultValue("1080p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subLangPref = ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_LANGUAGES
            entryValues = PREF_SUB_LANGUAGES
            setDefaultValue("English")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
        screen.addPreference(subLangPref)
    }

    // Filter

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Ignored If Using Text Search"),
        GenreList(genresName),
    )

    private class GenreList(genres: Array<String>) : AnimeFilter.Select<String>("Genre", genres)
    private data class Genre(val name: String, val query: String)
    private val genresName = getGenreList().map {
        it.name
    }.toTypedArray()

    private fun getGenreList() = listOf(
        Genre("CHOOSE", ""),
        Genre("Action", "action"),
        Genre("Action & Adventure", "action-adventure"),
        Genre("Adventure", "adventure"),
        Genre("Animation", "animation"),
        Genre("Biography", "biography"),
        Genre("Comedy", "comedy"),
        Genre("Crime", "crime"),
        Genre("Documentary", "documentary"),
        Genre("Drama", "drama"),
        Genre("Family", "family"),
        Genre("Fantasy", "fantasy"),
        Genre("History", "history"),
        Genre("Horror", "horror"),
        Genre("Kids", "kids"),
        Genre("Music", "music"),
        Genre("Mystery", "mystery"),
        Genre("News", "news"),
        Genre("Reality", "reality"),
        Genre("Romance", "romance"),
        Genre("Sci-Fi & Fantasy", "sci-fi-fantasy"),
        Genre("Science Fiction", "science-fiction"),
        Genre("Soap", "soap"),
        Genre("Talk", "talk"),
        Genre("Thriller", "thriller"),
        Genre("TV Movie", "tv-movie"),
        Genre("War", "war"),
        Genre("War & Politics", "war-politics"),
        Genre("Western", "western")
    )

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"
        private val PREF_DOMAIN_LIST = arrayOf("dopebox.to", "dopebox.se")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private val PREF_QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_TITLE = "Preferred sub language"
        private val PREF_SUB_LANGUAGES = arrayOf(
            "Arabic", "English", "French", "German", "Hungarian",
            "Italian", "Japanese", "Portuguese", "Romanian", "Russian",
            "Spanish"
        )
    }
}
