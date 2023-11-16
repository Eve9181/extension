package eu.kanade.tachiyomi.animeextension.en.dramacool

import android.app.Application
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class DramaCool : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "DramaCool"

    private val defaultBaseUrl = "https://dramacool.pa"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/most-popular-drama?page=$page") // page/$page

    override fun popularAnimeSelector() = "ul.list-episode-item li a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")?.attr("data-original")?.replace(" ", "%20")
        title = element.selectFirst("h3")?.text() ?: "Serie"
    }

    override fun popularAnimeNextPageSelector() = "li.next a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/recently-added?page=$page")

    override fun latestUpdatesSelector() = "ul.switch-block a"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/search?keyword=$query&page=$page")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        if (anime.url.contains("-episode-") && anime.url.endsWith(".html")) {
            val doc = client.newCall(GET(baseUrl + anime.url)).execute().use { it.asJsoup() }
            anime.setUrlWithoutDomain(doc.selectFirst("div.category a")!!.attr("href"))
        }
        return GET(baseUrl + anime.url)
    }

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.selectFirst("div.img img")!!.run {
            title = attr("alt")
            thumbnail_url = absUrl("src")
        }

        with(document.selectFirst("div.info")!!) {
            description = select("p:contains(Description) ~ p:not(:has(span))").eachText()
                .joinToString("\n")
                .takeUnless(String::isBlank)
            author = selectFirst("p:contains(Original Network:) > a")?.text()
            genre = select("p:contains(Genre:) > a").joinToString { it.text() }.takeUnless(String::isBlank)
            status = parseStatus(selectFirst("p:contains(Status) a")?.text())
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.all-episode li a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val epNum = element.selectFirst("h3")!!.text().substringAfterLast("Episode ")
        val type = element.selectFirst("span.type")?.text() ?: "RAW"
        name = "$type: Episode $epNum".trimEnd()
        episode_number = when {
            epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
            else -> 1F
        }
        date_upload = element.selectFirst("span.time")?.text().orEmpty().toDate()
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "ul.list-server-items li"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }
        val iframeUrl = document.selectFirst("iframe")?.absUrl("src") ?: return emptyList()
        val iframeDoc = client.newCall(GET(iframeUrl)).execute().use { it.asJsoup() }

        return iframeDoc.select(videoListSelector()).flatMap(::videosFromElement)
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }

    // TODO: Create a extractor for the "Standard server" thingie.
    // it'll require Synchrony or something similar, but synchrony is too slow >:(
    private fun videosFromElement(element: Element): List<Video> {
        val url = element.attr("data-video")
        return runCatching {
            when {
                url.contains("dood") -> doodExtractor.videosFromUrl(url)
                url.contains("dwish") -> streamwishExtractor.videosFromUrl(url)
                url.contains("streamtape") -> streamtapeExtractor.videosFromUrl(url)
                else -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF_TITLE
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(BASE_URL_PREF, newValue as String).commit()
                    Toast.makeText(screen.context, RESTART_ANIYOMI, Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    companion object {
        private const val RESTART_ANIYOMI = "Restart Aniyomi to apply new setting."

        private const val BASE_URL_PREF_TITLE = "Override BaseUrl"

        private val BASE_URL_PREF = "overrideBaseUrl_v${BuildConfig.VERSION_CODE}"

        private const val BASE_URL_PREF_SUMMARY = "For temporary uses. Update extension will erase this setting."

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "StreamTape")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
