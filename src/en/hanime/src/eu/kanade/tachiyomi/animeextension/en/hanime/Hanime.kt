package eu.kanade.tachiyomi.animeextension.en.hanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class Hanime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "hanime.tv"

    override val baseUrl = "https://hanime.tv"

    override val lang = "en"

    override val supportsLatest = true

    private var authCookie: String? = null

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private fun searchRequestBody(query: String, page: Int, filters: AnimeFilterList): RequestBody {
        val (includedTags, blackListedTags, brands, tagsMode, orderBy, ordering) = getSearchParameters(filters)

        return """
            {"search_text": "$query",
            "tags": $includedTags,
            "tags_mode":"$tagsMode",
            "brands": $brands,
            "blacklist": $blackListedTags,
            "order_by": "$orderBy",
            "ordering": "$ordering",
            "page": ${page - 1}}
        """.trimIndent().toRequestBody("application/json".toMediaType())
    }

    private val popularRequestHeaders =
        Headers.headersOf("authority", "search.htv-services.com", "accept", "application/json, text/plain, */*", "content-type", "application/json;charset=UTF-8")

    override fun popularAnimeRequest(page: Int): Request {
        return POST("https://search.htv-services.com/", popularRequestHeaders, searchRequestBody("", page, AnimeFilterList()))
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseSearchJson(responseString)
    }
    private fun parseSearchJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val nbPages = jObject["nbPages"]!!.jsonPrimitive.int
        val page = jObject["page"]!!.jsonPrimitive.int
        val hasNextPage = page < nbPages - 1
        val arrayString = jObject["hits"]!!.jsonPrimitive.content
        val array = json.decodeFromString<JsonArray>(arrayString)
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["name"]!!.jsonPrimitive.content
            anime.thumbnail_url = item.jsonObject["cover_url"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("https://hanime.tv/videos/hentai/" + item.jsonObject["slug"]!!.jsonPrimitive.content)
            anime.author = item.jsonObject["brand"]!!.jsonPrimitive.content
            anime.description = item.jsonObject["description"]!!.jsonPrimitive.content.replace("<p>", "").replace("</p>", "")
            anime.status = SAnime.COMPLETED
            val tags = item.jsonObject["tags"]!!.jsonArray
            anime.genre = tags.joinToString(", ") { it.jsonPrimitive.content }
            anime.initialized = true
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = POST("https://search.htv-services.com/", popularRequestHeaders, searchRequestBody(query, page, filters))

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseSearchJson(responseString)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("h1.tv-title").text()
            thumbnail_url = document.select("img.hvpi-cover").attr("src")
            setUrlWithoutDomain(document.location())
            author = document.select("a.hvpimbc-text").text()
            description = document.select("div.hvpist-description p")
                .joinToString("\n\n") { it.text() }
            status = SAnime.COMPLETED
            genre = document.select("div.hvpis-text div.btn__content").joinToString { it.text() }
            initialized = true
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url)
    }

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        setAuthCookie()

        if (authCookie != null) {
            return fetchVideoListPremium(episode)
        }

        return super.fetchVideoList(episode)
    }

    private fun fetchVideoListPremium(episode: SEpisode): Observable<List<Video>> {
        val videoList = mutableListOf<Video>()
        val id = episode.url.substringAfter("?id=")
        val headers = headers.newBuilder()
            .add("cookie", authCookie!!)
        val document = client.newCall(
            GET("$baseUrl/videos/hentai/$id", headers = headers.build()),
        ).execute().asJsoup()
        val data = document.selectFirst("script:containsData(__NUXT__)")!!.data()
            .substringAfter("__NUXT__=").substringBeforeLast(";")
        val parsed = json.decodeFromString<WindowNuxt>(data)
        parsed.state.data.video.videos_manifest.servers.forEach { server ->
            server.streams.forEach { stream ->
                videoList.add(
                    Video(
                        stream.url,
                        stream.height + "p",
                        stream.url,
                    ),
                )
            }
        }
        return Observable.just(videoList)
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body.string()
        val jObject = json.decodeFromString<JsonObject>(responseString)
        val server = jObject["videos_manifest"]!!.jsonObject["servers"]!!.jsonArray[0].jsonObject
        val streams = server["streams"]!!.jsonArray
        val linkList = mutableListOf<Video>()
        for (stream in streams) {
            val streamObject = stream.jsonObject
            if (streamObject["kind"]!!.jsonPrimitive.content != "premium_alert") {
                linkList.add(
                    Video(
                        url = streamObject["url"]!!.jsonPrimitive.content,
                        quality = streamObject["height"]!!.jsonPrimitive.content + "p",
                        videoUrl = streamObject["url"]!!.jsonPrimitive.content,
                    ),
                )
            }
        }
        return linkList
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

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        return GET("$baseUrl/api/v8/video?id=$slug")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        val jObject = json.decodeFromString<JsonObject>(responseString)
        val episode = SEpisode.create()
        episode.date_upload = jObject.jsonObject["hentai_video"]!!.jsonObject["released_at_unix"]!!.jsonPrimitive.long * 1000
        episode.name = jObject.jsonObject["hentai_video"]!!.jsonObject["name"]!!.jsonPrimitive.content
        episode.url = response.request.url.toString()
        episode.episode_number = 1F
        return listOf(episode)
    }

    private fun setAuthCookie() {
        if (authCookie == null) {
            val cookieList = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            if (cookieList.isNotEmpty()) {
                cookieList.firstOrNull { it.name == "htv3session" }?.let { authCookie = "${it.name}=${it.value}" }
            }
        }
    }

    private fun latestSearchRequestBody(page: Int): RequestBody {
        return """
            {"search_text": "",
            "tags": [],
            "tags_mode":"AND",
            "brands": [],
            "blacklist": [],
            "order_by": "published_at_unix",
            "ordering": "desc",
            "page": ${page - 1}}
        """.trimIndent().toRequestBody("application/json".toMediaType())
    }

    override fun latestUpdatesRequest(page: Int): Request = POST("https://search.htv-services.com/", popularRequestHeaders, latestSearchRequestBody(page))

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseSearchJson(responseString)
    }

    // Filters
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TagList(getTags()),
        BrandList(getBrands()),
        SortFilter(sortableList.map { it.first }.toTypedArray()),
        TagInclusionMode(),

    )
    internal class Tag(val id: String, name: String) : AnimeFilter.TriState(name)
    internal class Brand(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class TagList(tags: List<Tag>) : AnimeFilter.Group<Tag>("Tags", tags)
    private class BrandList(brands: List<Brand>) : AnimeFilter.Group<Brand>("Brands", brands)
    private class TagInclusionMode :
        AnimeFilter.Select<String>("Included tags mode", arrayOf("And", "Or"), 0)

    data class SearchParameters(
        val includedTags: ArrayList<String>,
        val blackListedTags: ArrayList<String>,
        val brands: ArrayList<String>,
        val tagsMode: String,
        val orderBy: String,
        val ordering: String,
    )

    private fun getSearchParameters(filters: AnimeFilterList): SearchParameters {
        val includedTags = ArrayList<String>()
        val blackListedTags = ArrayList<String>()
        val brands = ArrayList<String>()
        var tagsMode = "AND"
        var orderBy = "likes"
        var ordering = "desc"
        filters.forEach { filter ->
            when (filter) {
                is TagList -> {
                    filter.state.forEach { tag ->
                        if (tag.isIncluded()) {
                            includedTags.add(
                                "\"" + tag.id.toLowerCase(
                                    Locale.US,
                                ) + "\"",
                            )
                        } else if (tag.isExcluded()) {
                            blackListedTags.add(
                                "\"" + tag.id.toLowerCase(
                                    Locale.US,
                                ) + "\"",
                            )
                        }
                    }
                }
                is TagInclusionMode -> {
                    tagsMode = filter.values[filter.state].toUpperCase(Locale.US)
                }
                is SortFilter -> {
                    if (filter.state != null) {
                        val query = sortableList[filter.state!!.index].second
                        val value = when (filter.state!!.ascending) {
                            true -> "asc"
                            false -> "desc"
                        }
                        ordering = value
                        orderBy = query
                    }
                }
                is BrandList -> {
                    filter.state.forEach { brand ->
                        if (brand.state) {
                            brands.add(
                                "\"" + brand.id.toLowerCase(
                                    Locale.US,
                                ) + "\"",
                            )
                        }
                    }
                }
                else -> {}
            }
        }
        return SearchParameters(includedTags, blackListedTags, brands, tagsMode, orderBy, ordering)
    }

    private fun getBrands() = listOf(
        Brand("37c-Binetsu", "37c-binetsu"),
        Brand("Adult Source Media", "adult-source-media"),
        Brand("Ajia-Do", "ajia-do"),
        Brand("Almond Collective", "almond-collective"),
        Brand("Alpha Polis", "alpha-polis"),
        Brand("Ameliatie", "ameliatie"),
        Brand("Amour", "amour"),
        Brand("Animac", "animac"),
        Brand("Antechinus", "antechinus"),
        Brand("APPP", "appp"),
        Brand("Arms", "arms"),
        Brand("Bishop", "bishop"),
        Brand("Blue Eyes", "blue-eyes"),
        Brand("BOMB! CUTE! BOMB!", "bomb-cute-bomb"),
        Brand("Bootleg", "bootleg"),
        Brand("BreakBottle", "breakbottle"),
        Brand("BugBug", "bugbug"),
        Brand("Bunnywalker", "bunnywalker"),
        Brand("Celeb", "celeb"),
        Brand("Central Park Media", "central-park-media"),
        Brand("ChiChinoya", "chichinoya"),
        Brand("Chocolat", "chocolat"),
        Brand("ChuChu", "chuchu"),
        Brand("Circle Tribute", "circle-tribute"),
        Brand("CoCoans", "cocoans"),
        Brand("Collaboration Works", "collaboration-works"),
        Brand("Comet", "comet"),
        Brand("Comic Media", "comic-media"),
        Brand("Cosmos", "cosmos"),
        Brand("Cranberry", "cranberry"),
        Brand("Crimson", "crimson"),
        Brand("D3", "d3"),
        Brand("Daiei", "daiei"),
        Brand("demodemon", "demodemon"),
        Brand("Digital Works", "digital-works"),
        Brand("Discovery", "discovery"),
        Brand("Dollhouse", "dollhouse"),
        Brand("EBIMARU-DO", "ebimaru-do"),
        Brand("Echo", "echo"),
        Brand("ECOLONUN", "ecolonun"),
        Brand("Edge", "edge"),
        Brand("Erozuki", "erozuki"),
        Brand("evee", "evee"),
        Brand("FINAL FUCK 7", "final-fuck-7"),
        Brand("Five Ways", "five-ways"),
        Brand("Friends Media Station", "friends-media-station"),
        Brand("Front Line", "front-line"),
        Brand("fruit", "fruit"),
        Brand("Godoy", "godoy"),
        Brand("GOLD BEAR", "gold-bear"),
        Brand("gomasioken", "gomasioken"),
        Brand("Green Bunny", "green-bunny"),
        Brand("Groover", "groover"),
        Brand("Hoods Entertainment", "hoods-entertainment"),
        Brand("Hot Bear", "hot-bear"),
        Brand("Hykobo", "hykobo"),
        Brand("IRONBELL", "ironbell"),
        Brand("Ivory Tower", "ivory-tower"),
        Brand("J.C.", "j-c"),
        Brand("Jellyfish", "jellyfish"),
        Brand("Jewel", "jewel"),
        Brand("Jumondo", "jumondo"),
        Brand("kate_sai", "kate_sai"),
        Brand("KENZsoft", "kenzsoft"),
        Brand("King Bee", "king-bee"),
        Brand("Kitty Media", "kitty-media"),
        Brand("Knack", "knack"),
        Brand("Kuril", "kuril"),
        Brand("L.", "l"),
        Brand("Lemon Heart", "lemon-heart"),
        Brand("Lilix", "lilix"),
        Brand("Lune Pictures", "lune-pictures"),
        Brand("Magic Bus", "magic-bus"),
        Brand("Magin Label", "magin-label"),
        Brand("Majin Petit", "majin-petit"),
        Brand("Marigold", "marigold"),
        Brand("Mary Jane", "mary-jane"),
        Brand("MediaBank", "mediabank"),
        Brand("Media Blasters", "media-blasters"),
        Brand("Metro Notes", "metro-notes"),
        Brand("Milky", "milky"),
        Brand("MiMiA Cute", "mimia-cute"),
        Brand("Moon Rock", "moon-rock"),
        Brand("Moonstone Cherry", "moonstone-cherry"),
        Brand("Mousou Senka", "mousou-senka"),
        Brand("MS Pictures", "ms-pictures"),
        Brand("Muse", "muse"),
        Brand("N43", "n43"),
        Brand("Nihikime no Dozeu", "nihikime-no-dozeu"),
        Brand("Nikkatsu Video", "nikkatsu-video"),
        Brand("nur", "nur"),
        Brand("NuTech Digital", "nutech-digital"),
        Brand("Obtain Future", "obtain-future"),
        Brand("Otodeli", "otodeli"),
        Brand("@ OZ", "oz"),
        Brand("Pashmina", "pashmina"),
        Brand("Passione", "passione"),
        Brand("Peach Pie", "peach-pie"),
        Brand("Pinkbell", "pinkbell"),
        Brand("Pink Pineapple", "pink-pineapple"),
        Brand("Pix", "pix"),
        Brand("Pixy Soft", "pixy-soft"),
        Brand("Pocomo Premium", "pocomo-premium"),
        Brand("PoRO", "poro"),
        Brand("Project No.9", "project-no-9"),
        Brand("Pumpkin Pie", "pumpkin-pie"),
        Brand("Queen Bee", "queen-bee"),
        Brand("Rabbit Gate", "rabbit-gate"),
        Brand("sakamotoJ", "sakamotoj"),
        Brand("Sakura Purin", "sakura-purin"),
        Brand("SANDWICHWORKS", "sandwichworks"),
        Brand("Schoolzone", "schoolzone"),
        Brand("seismic", "seismic"),
        Brand("SELFISH", "selfish"),
        Brand("Seven", "seven"),
        Brand("Shadow Prod. Co.", "shadow-prod-co"),
        Brand("Shelf", "shelf"),
        Brand("Shinyusha", "shinyusha"),
        Brand("ShoSai", "shosai"),
        Brand("Showten", "showten"),
        Brand("SoftCell", "softcell"),
        Brand("Soft on Demand", "soft-on-demand"),
        Brand("SPEED", "speed"),
        Brand("STARGATE3D", "stargate3d"),
        Brand("Studio 9 Maiami", "studio-9-maiami"),
        Brand("Studio Akai Shohosen", "studio-akai-shohosen"),
        Brand("Studio Deen", "studio-deen"),
        Brand("Studio Fantasia", "studio-fantasia"),
        Brand("Studio FOW", "studio-fow"),
        Brand("studio GGB", "studio-ggb"),
        Brand("Studio Houkiboshi", "studio-houkiboshi"),
        Brand("Studio Zealot", "studio-zealot"),
        Brand("Suiseisha", "suiseisha"),
        Brand("Suzuki Mirano", "suzuki-mirano"),
        Brand("SYLD", "syld"),
        Brand("TDK Core", "tdk-core"),
        Brand("t japan", "t-japan"),
        Brand("TNK", "tnk"),
        Brand("TOHO", "toho"),
        Brand("Toranoana", "toranoana"),
        Brand("T-Rex", "t-rex"),
        Brand("Triangle", "triangle"),
        Brand("Trimax", "trimax"),
        Brand("TYS Work", "tys-work"),
        Brand("U-Jin", "u-jin"),
        Brand("Umemaro-3D", "umemaro-3d"),
        Brand("Union Cho", "union-cho"),
        Brand("Valkyria", "valkyria"),
        Brand("Vanilla", "vanilla"),
        Brand("White Bear", "white-bear"),
        Brand("X City", "x-city"),
        Brand("yosino", "yosino"),
        Brand("Y.O.U.C.", "y-o-u-c"),
        Brand("ZIZ", "ziz"),
    )

    private fun getTags() = listOf(
        Tag("3D", "3D"),
        Tag("AHEGAO", "AHEGAO"),
        Tag("ANAL", "ANAL"),
        Tag("BDSM", "BDSM"),
        Tag("BIG BOOBS", "BIG BOOBS"),
        Tag("BLOW JOB", "BLOW JOB"),
        Tag("BONDAGE", "BONDAGE"),
        Tag("BOOB JOB", "BOOB JOB"),
        Tag("CENSORED", "CENSORED"),
        Tag("COMEDY", "COMEDY"),
        Tag("COSPLAY", "COSPLAY"),
        Tag("CREAMPIE", "CREAMPIE"),
        Tag("DARK SKIN", "DARK SKIN"),
        Tag("FACIAL", "FACIAL"),
        Tag("FANTASY", "FANTASY"),
        Tag("FILMED", "FILMED"),
        Tag("FOOT JOB", "FOOT JOB"),
        Tag("FUTANARI", "FUTANARI"),
        Tag("GANGBANG", "GANGBANG"),
        Tag("GLASSES", "GLASSES"),
        Tag("HAND JOB", "HAND JOB"),
        Tag("HAREM", "HAREM"),
        Tag("HD", "HD"),
        Tag("HORROR", "HORROR"),
        Tag("INCEST", "INCEST"),
        Tag("INFLATION", "INFLATION"),
        Tag("LACTATION", "LACTATION"),
        Tag("LOLI", "LOLI"),
        Tag("MAID", "MAID"),
        Tag("MASTURBATION", "MASTURBATION"),
        Tag("MILF", "MILF"),
        Tag("MIND BREAK", "MIND BREAK"),
        Tag("MIND CONTROL", "MIND CONTROL"),
        Tag("MONSTER", "MONSTER"),
        Tag("NEKOMIMI", "NEKOMIMI"),
        Tag("NTR", "NTR"),
        Tag("NURSE", "NURSE"),
        Tag("ORGY", "ORGY"),
        Tag("PLOT", "PLOT"),
        Tag("POV", "POV"),
        Tag("PREGNANT", "PREGNANT"),
        Tag("PUBLIC SEX", "PUBLIC SEX"),
        Tag("RAPE", "RAPE"),
        Tag("REVERSE RAPE", "REVERSE RAPE"),
        Tag("RIMJOB", "RIMJOB"),
        Tag("SCAT", "SCAT"),
        Tag("SCHOOL GIRL", "SCHOOL GIRL"),
        Tag("SHOTA", "SHOTA"),
        Tag("SOFTCORE", "SOFTCORE"),
        Tag("SWIMSUIT", "SWIMSUIT"),
        Tag("TEACHER", "TEACHER"),
        Tag("TENTACLE", "TENTACLE"),
        Tag("THREESOME", "THREESOME"),
        Tag("TOYS", "TOYS"),
        Tag("TRAP", "TRAP"),
        Tag("TSUNDERE", "TSUNDERE"),
        Tag("UGLY BASTARD", "UGLY BASTARD"),
        Tag("UNCENSORED", "UNCENSORED"),
        Tag("VANILLA", "VANILLA"),
        Tag("VIRGIN", "VIRGIN"),
        Tag("WATERSPORTS", "WATERSPORTS"),
        Tag("X-RAY", "X-RAY"),
        Tag("YAOI", "YAOI"),
        Tag("YURI", "YURI"),
    )

    private val sortableList = listOf(
        Pair("Uploads", "created_at_unix"),
        Pair("Views", "views"),
        Pair("Likes", "likes"),
        Pair("Release", "released_at_unix"),
        Pair("Alphabetical", "title_sortable"),
    )

    class SortFilter(sortables: Array<String>) : AnimeFilter.Sort("Sort", sortables, Selection(2, false))

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("720")
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

    @Serializable
    data class WindowNuxt(
        val state: State,
    ) {
        @Serializable
        data class State(
            val data: Data,
        ) {
            @Serializable
            data class Data(
                val video: DataVideo,
            ) {
                @Serializable
                data class DataVideo(
                    val videos_manifest: VideosManifest,
                ) {
                    @Serializable
                    data class VideosManifest(
                        val servers: List<Server>,
                    ) {
                        @Serializable
                        data class Server(
                            val streams: List<Stream>,
                        ) {
                            @Serializable
                            data class Stream(
                                val height: String,
                                val url: String,
                            )
                        }
                    }
                }
            }
        }
    }
}
