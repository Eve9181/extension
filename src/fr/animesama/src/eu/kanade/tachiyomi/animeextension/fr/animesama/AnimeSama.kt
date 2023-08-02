package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.mytvextractor.MytvExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.Normalizer

class AnimeSama : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Anime-Sama"

    override val baseUrl = "https://www.anime-sama.fr"

    override val lang = "fr"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val animes = response.asJsoup()
        val seasons = animes.select("h2:contains(les classiques) + .scrollBarStyled > div").flatMap {
            val animeUrl = it.getElementsByTag("a").attr("href")
            fetchAnimeSeasons(animeUrl)
        }
        return AnimesPage(seasons, false)
    }

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = response.asJsoup()
        val seasons = animes.select("h2:contains(derniers ajouts) + .scrollBarStyled > div").flatMap {
            val animeUrl = it.getElementsByTag("a").attr("href")
            fetchAnimeSeasons(animeUrl)
        }
        return AnimesPage(seasons, false)
    }
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.request.method == "GET") {
            AnimesPage(fetchAnimeSeasons(response), false)
        } else {
            val page = response.request.url.fragment?.toInt() ?: 1
            val elements = response.asJsoup().select(".cardListAnime").chunked(5)
            val animes = elements[page - 1].flatMap {
                fetchAnimeSeasons(it.getElementsByTag("a").attr("href"))
            }
            AnimesPage(animes, page < elements.size)
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.startsWith(PREFIX_SEARCH)) { // Activity Intent Handler
            GET("$baseUrl/catalogue/${query.removePrefix(PREFIX_SEARCH)}/")
        } else {
            POST("$baseUrl/catalogue/searchbar.php#$page", headers, FormBody.Builder().add("query", query).build())
        }

    // =========================== Anime Details ============================
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> = Observable.just(anime)

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("not used")

    // ============================== Episodes ==============================
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val animeUrl = "$baseUrl${anime.url.substringBeforeLast("/")}"
        val movie = anime.url.split("#").getOrElse(1) { "" }.toIntOrNull()
        val players = VOICES_VALUES.map { fetchPlayers("$animeUrl/$it") }
        val episodes = playersToEpisodes(players)
        return Observable.just(if (movie == null) episodes.reversed() else listOf(episodes[movie]))
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("not used")

    // ============================ Video Links =============================
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val playerUrls = json.decodeFromString<List<List<String>>>(episode.url)
        val videos = playerUrls.flatMapIndexed { i, it ->
            val prefix = "(${VOICES_VALUES[i].uppercase()}) "
            it.flatMap { playerUrl ->
                with(playerUrl) {
                    when {
                        contains("anime-sama.fr") -> listOf(Video(playerUrl, "${prefix}AS Player", playerUrl))
                        contains("sibnet.ru") -> SibnetExtractor(client).videosFromUrl(playerUrl, prefix)
                        contains("myvi.") -> MytvExtractor(client).videosFromUrl(playerUrl, prefix)
                        contains("vk.") -> VkExtractor(client, headers).videosFromUrl(playerUrl, prefix)
                        contains("sendvid.com") -> SendvidExtractor(client, headers).videosFromUrl(playerUrl, prefix)
                        else -> emptyList()
                    }
                }
            }
        }.sort()
        return Observable.just(videos)
    }

    // ============================ Utils =============================
    private fun removeDiacritics(string: String) = Normalizer.normalize(string, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    private fun sanitizeEpisodesJs(doc: String) = doc
        .replace(Regex("[\"\t]"), "") // Fix trash format
        .replace("'", "\"") // Fix quotes
        .replace(Regex("/\\*.*?\\*/", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)), "") // Remove block comments
        .replace(Regex("(^|,|\\[)\\s*//.*?$", RegexOption.MULTILINE), "$1") // Remove line comments
        .replace(Regex(",\\s*]"), "]") // Remove trailing comma

    override fun List<Video>.sort(): List<Video> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(voices) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun fetchAnimeSeasons(animeUrl: String): List<SAnime> {
        val res = client.newCall(GET(animeUrl)).execute()
        return fetchAnimeSeasons(res)
    }

    private fun fetchAnimeSeasons(response: Response): List<SAnime> {
        val animeDoc = response.asJsoup()
        val animeUrl = response.request.url
        val animeName = animeDoc.getElementById("titreOeuvre")?.text() ?: ""

        val seasonRegex = Regex("^\\s*panneauAnime\\(\"(.*)\", \"(.*)\"\\)", RegexOption.MULTILINE)
        val animes = seasonRegex.findAll(animeDoc.toString()).flatMapIndexed { animeIndex, seasonMatch ->
            val (seasonName, seasonStem) = seasonMatch.destructured
            if (seasonStem.contains("film", true)) {
                val moviesUrl = "$animeUrl/$seasonStem"
                val movies = fetchPlayers(moviesUrl).ifEmpty { return@flatMapIndexed emptyList() }
                val movieNameRegex = Regex("^\\s*newSPF\\(\"(.*)\"\\);", RegexOption.MULTILINE)
                val moviesDoc = client.newCall(GET(moviesUrl)).execute().use { it.body.string() }
                val matches = movieNameRegex.findAll(moviesDoc).toList()
                List(movies.size) { i ->
                    val title = when {
                        animeIndex == 0 && movies.size == 1 -> animeName
                        matches.size > i -> "$animeName ${matches[i].destructured.component1()}"
                        movies.size == 1 -> "$animeName Film"
                        else -> "$animeName Film ${i + 1}"
                    }
                    Triple(title, "$moviesUrl#$i", SAnime.COMPLETED)
                }
            } else {
                listOf(Triple("$animeName $seasonName", "$animeUrl/$seasonStem", SAnime.UNKNOWN))
            }
        }

        return animes.map {
            SAnime.create().apply {
                title = it.first
                thumbnail_url = animeDoc.getElementById("coverOeuvre")?.attr("src")
                description = animeDoc.select("h2:contains(synopsis) + p").text()
                genre = animeDoc.select("h2:contains(genres) + a").text()
                setUrlWithoutDomain(it.second)
                status = it.third
                initialized = true
            }
        }.toList()
    }

    private fun playersToEpisodes(list: List<List<List<String>>>): List<SEpisode> =
        List(list.fold(0) { acc, it -> maxOf(acc, it.size) }) { episodeNumber ->
            val players = list.map { it.getOrElse(episodeNumber) { emptyList() } }
            SEpisode.create().apply {
                name = "Episode ${episodeNumber + 1}"
                url = json.encodeToString(players)
                episode_number = (episodeNumber + 1).toFloat()
                scanlator = players.mapIndexedNotNull { i, it -> if (it.isNotEmpty()) VOICES_VALUES[i] else null }.joinToString().uppercase()
            }
        }

    private fun fetchPlayers(url: String): List<List<String>> {
        val docUrl = "$url/episodes.js"
        val players = mutableListOf<List<String>>()
        val doc = client.newCall(GET(docUrl)).execute().use {
            if (it.code != 200) return listOf()
            it.body.string()
        }
        val sanitizedDoc = sanitizeEpisodesJs(doc)
        for (i in 1..8) {
            val numPlayers = getPlayers("eps$i", sanitizedDoc)
            if (numPlayers != null) players.add(numPlayers)
        }
        val asPlayers = getPlayers("epsAS", sanitizedDoc)
        if (asPlayers != null) players.add(asPlayers)
        return List(players[0].size) { i -> players.mapNotNull { it.getOrNull(i) }.distinct() }
    }

    private fun getPlayers(playerName: String, doc: String): List<String>? {
        val playerRegex = Regex("$playerName\\s*=\\s*(\\[.*?])", RegexOption.DOT_MATCHES_ALL)
        val string = playerRegex.find(doc)?.groupValues?.get(1)
        return if (string != null) json.decodeFromString<List<String>>(string) else null
    }

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
            key = PREF_VOICES_KEY
            title = "Préférence des voix"
            entries = VOICES
            entryValues = VOICES_VALUES
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val VOICES = arrayOf(
            "Préférer VOSTFR",
            "Préférer VF",
        )

        private val VOICES_VALUES = arrayOf(
            "vostfr",
            "vf",
        )

        private const val PREF_VOICES_KEY = "voices_preference"
        private const val PREF_VOICES_DEFAULT = "vostfr"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}
