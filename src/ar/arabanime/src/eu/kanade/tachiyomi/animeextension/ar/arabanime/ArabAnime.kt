package eu.kanade.tachiyomi.animeextension.ar.arabanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.arabanime.dto.AnimeItem
import eu.kanade.tachiyomi.animeextension.ar.arabanime.dto.Episode
import eu.kanade.tachiyomi.animeextension.ar.arabanime.dto.PopularAnimeResponse
import eu.kanade.tachiyomi.animeextension.ar.arabanime.dto.ShowItem
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
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ArabAnime: ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "ArabAnime"

    override val baseUrl = "https://www.arabanime.net"

    override val lang = "ar"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<PopularAnimeResponse>(response.body.string())
        val animeList = responseJson.Shows.map {
            val animeJson = json.decodeFromString<AnimeItem>(it.decodeBase64())
            SAnime.create().apply {
                setUrlWithoutDomain(animeJson.info_src)
                title = animeJson.anime_name
                thumbnail_url = animeJson.anime_cover_image_url
            }
        }
        val hasNextPage = responseJson.current_page < responseJson.last_page
        return AnimesPage(animeList, hasNextPage)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api?page=$page")

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val showData = response.asJsoup().select("div#data").text().decodeBase64()
        val episodesJson = json.decodeFromString<ShowItem>(showData)
        return episodesJson.EPS.map {
            SEpisode.create().apply {
                name = it.episode_name
                episode_number = it.episode_number.toFloat()
                setUrlWithoutDomain(it.`info-src`)
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl/${episode.url}")

    override fun videoListParse(response: Response): List<Video> {
        val watchData = response.asJsoup().select("div#datawatch").text().decodeBase64()
        val serversJson = json.decodeFromString<Episode>(watchData)
        val selectServer =serversJson.ep_info[0].stream_servers[0].decodeBase64()
        val watchPage = client.newCall(GET(selectServer)).execute().asJsoup()
        val videoList = mutableListOf<Video>()
        watchPage.select("option").forEach { it ->
            val link = it.attr("data-src").decodeBase64()
            if (link.contains("www.arabanime.net/embed")){
                val sources = client.newCall(GET(link)).execute().asJsoup().select("source")
                sources.forEach { source ->
                    if(!source.attr("src").contains("static")){
                        val quality = source.attr("label").let {q ->
                            if(q.contains("p")) q else q + "p"
                        }
                        videoList.add(
                            Video(source.attr("src"), "${it.text()}: $quality" ,source.attr("src"))
                        )
                    }
                }
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val showData = response.asJsoup().select("div#data").text().decodeBase64()
        val details = json.decodeFromString<ShowItem>(showData).show[0]
        return SAnime.create().apply {
            url = "/show-${details.anime_id}/${details.anime_slug}"
            title = details.anime_name
            status = when (details.anime_status) {
                "Ongoing" -> SAnime.ONGOING
                "Completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            genre = details.anime_genres
            description = details.anime_description
            thumbnail_url = details.anime_cover_image_url
        }
    }

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if(response.body.contentType() == "application/json".toMediaType()){
            popularAnimeParse(response)
        } else {
            val searchResult = response.asJsoup().select("div.show")
            val animeList = searchResult.map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.select("a").attr("href"))
                    title = it.select("h3").text()
                    thumbnail_url = it.select("img").attr("src")
                }
            }
            return AnimesPage(animeList, false)
        }
    }
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotEmpty()) {
            val body = FormBody.Builder().add("searchq", query).build()
            return POST("$baseUrl/searchq", body = body)
        } else {
            var type = ""
            var status = ""
            var order = ""
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is OrderCategoryList -> {
                        order = getOrderFilterList()[filter.state].query
                    }
                    is TypeCategoryList -> {
                        type = getTypeFilterList()[filter.state].query
                    }
                    is StatCategoryList -> {
                        status = getStatFilterList()[filter.state].query
                    }
                    else -> {}
                }
            }
            return GET("$baseUrl/api?order=$order&type=$type&stat=$status&tags=&page=$page")
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val latestEpisodes = response.asJsoup().select("div.as-episode")
        val animeList = latestEpisodes.map {
            SAnime.create().apply {
                val url = it.select("a.as-info").attr("href")
                    .replace("watch","show").substringBeforeLast("/")
                setUrlWithoutDomain(url)
                title = it.select("a.as-info").text()
                thumbnail_url = it.select("img").attr("src")
            }
        }
        return AnimesPage(animeList, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    // ============================== filters ==============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("فلترة الموقع"),
        OrderCategoryList(orderFilterList),
        TypeCategoryList(typeFilterList),
        StatCategoryList(statFilterList),
    )
    private class OrderCategoryList(categories: Array<String>) : AnimeFilter.Select<String>("ترتيب", categories)
    private class TypeCategoryList(categories: Array<String>) : AnimeFilter.Select<String>("النوع", categories)
    private class StatCategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الحالة", categories)

    private data class CatUnit(val name: String, val query: String)

    private val orderFilterList = getOrderFilterList().map { it.name }.toTypedArray()
    private val typeFilterList = getTypeFilterList().map { it.name }.toTypedArray()
    private val statFilterList = getStatFilterList().map { it.name }.toTypedArray()

    private fun getOrderFilterList() = listOf(
        CatUnit("اختر", ""),
        CatUnit("التقييم", "2"),
        CatUnit("اخر الانميات المضافة", "1"),
        CatUnit("الابجدية", "0")
    )

    private fun getTypeFilterList() = listOf(
        CatUnit("اختر", ""),
        CatUnit("الكل", ""),
        CatUnit("فيلم", "0"),
        CatUnit("انمى", "1")
    )
    private fun getStatFilterList() = listOf(
        CatUnit("اختر", ""),
        CatUnit("الكل", ""),
        CatUnit("مستمر", "1"),
        CatUnit("مكتمل", "0")
    )

    // =============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
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

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES by lazy {
            PREF_QUALITY_ENTRIES.map { it.substringBefore("p") }.toTypedArray()
        }
    }
}
