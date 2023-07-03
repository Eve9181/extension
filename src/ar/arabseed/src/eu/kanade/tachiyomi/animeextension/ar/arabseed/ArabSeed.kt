package eu.kanade.tachiyomi.animeextension.ar.arabseed

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class ArabSeed : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "عرب سيد"

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
    }

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "ul.Blocks-UL div.MovieBlock a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/movies/?offset=$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("div.BlockName > h4")!!.text()
        thumbnail_url = element.selectFirst("div.Poster img")!!.attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = "ul.page-numbers li a.next"

    // ============================== Episode ===============================
    override fun episodeListSelector() = "div.ContainerEpisodesList a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector())
        return when {
            episodes.isEmpty() -> {
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    name = "مشاهدة"
                }.let(::listOf)
            }
            else -> episodes.map(::episodeFromElement)
        }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = element.selectFirst("em")?.text()?.toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val watchUrl = doc.selectFirst("a.watchBTn")!!.attr("href")
        val element = client.newCall(GET(watchUrl, headers)).execute().asJsoup()
        return videosFromElement(element)
    }

    override fun videoListSelector() = "div.containerServers ul li"

    private fun videosFromElement(document: Document): List<Video> {
        return document.select(videoListSelector()).mapNotNull { element ->
            val dataQu = element.text()
            val embedUrl = element.attr("data-link")
            when {
                embedUrl.contains("reviewtech") -> {
                    val iframeResponse = client.newCall(GET(embedUrl)).execute().asJsoup()
                    val videoUrl = iframeResponse.selectFirst("source")!!.attr("src")
                    Video(embedUrl, dataQu + "p", videoUrl.replace("https", "http"))
                }
                else -> null
            }
        }
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/find/?find=$query&offset=$page"
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
            val category = typeFilter.toUriPart()
            if (category.isEmpty()) throw Exception("اختر فلتر")

            "$baseUrl/category/$category"
        }
        return GET(url, headers)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.Poster img")!!.let { img ->
            img.attr("abs:data-src")
                .ifEmpty { img.attr("abs:data-lazy-src") }
                .ifEmpty { img.attr("abs:src") }
        }
        title = document.selectFirst("div.BreadCrumbs ol li:last-child a span")!!
            .text()
            .replace(" مترجم", "").replace("فيلم ", "")
        genre = document.select("div.MetaTermsInfo  > li:contains(النوع) > a").eachText().joinToString()
        description = document.selectFirst("div.StoryLine p")!!.text()
        status = when {
            document.location().contains("/selary/") -> SAnime.UNKNOWN
            else -> SAnime.COMPLETED
        }
    }

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        TypeFilter(),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class TypeFilter : UriPartFilter(
        "نوع الفلم",
        arrayOf(
            Pair("أختر", ""),
            Pair("افلام عربي", "arabic-movies-5/"),
            Pair("افلام اجنبى", "foreign-movies3/"),
            Pair("افلام اسيوية", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/"),
            Pair("افلام هندى", "indian-movies/"),
            Pair("افلام تركية", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/"),
            Pair("افلام انيميشن", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%8a%d9%85%d9%8a%d8%b4%d9%86/"),
            Pair("افلام كلاسيكيه", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d9%84%d8%a7%d8%b3%d9%8a%d9%83%d9%8a%d9%87/"),
            Pair("افلام مدبلجة", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/"),
            Pair("افلام Netfilx", "netfilx/افلام-netfilx/"),
            Pair("مسلسلات عربي", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b9%d8%b1%d8%a8%d9%8a/"),
            Pair("مسلسلات اجنبي", "foreign-series/"),
            Pair("مسلسلات تركيه", "turkish-series-1/"),
            Pair("برامج تلفزيونية", "%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-%d8%aa%d9%84%d9%81%d8%b2%d9%8a%d9%88%d9%86%d9%8a%d8%a9/"),
            Pair("مسلسلات كرتون", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%83%d8%b1%d8%aa%d9%88%d9%86/"),
            Pair("مسلسلات رمضان 2019", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2019/"),
            Pair("مسلسلات رمضان 2020", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2020-hd/"),
            Pair("مسلسلات رمضان 2021", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2021/"),
            Pair("مسلسلات Netfilx", "netfilx/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-netfilz/"),
        ),
    )

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")
    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")
    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // =============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val defaultDomainPref = EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            dialogTitle = PREF_DOMAIN_DIALOG_TITLE
            dialogMessage = PREF_DOMAIN_DIALOG_MESSAGE
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = PREF_DOMAIN_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).ifEmpty { PREF_DOMAIN_DEFAULT }
                    Toast.makeText(screen.context, PREF_DOMAIN_TOAST, Toast.LENGTH_LONG).show()
                    preferences.edit().putString(key, value).commit()
                }.getOrDefault(false)
            }
        }

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
        screen.addPreference(defaultDomainPref)
        screen.addPreference(videoQualityPref)
    }

    companion object {
        // From egydead(ar)
        private const val PREF_DOMAIN_KEY = "default_domain"
        private const val PREF_DOMAIN_TITLE = "Override default domain with a custom, different one"
        private const val PREF_DOMAIN_DEFAULT = "https://g20.arabseed.ink"
        private const val PREF_DOMAIN_DIALOG_TITLE = "Enter custom domain"
        private const val PREF_DOMAIN_DIALOG_MESSAGE = "Default/Original domain: https://g20.arabseed.ink"
        private const val PREF_DOMAIN_SUMMARY = "You can change the site domain from here"
        private const val PREF_DOMAIN_TOAST = "Restart Aniyomi to apply changes"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES by lazy {
            PREF_QUALITY_ENTRIES.map { it.substringBefore("p") }.toTypedArray()
        }
    }
}
