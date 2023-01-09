package eu.kanade.tachiyomi.animeextension.en.allanime.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

@Serializable
data class VideoLink(
    val links: List<Link>
) {
    @Serializable
    data class Link(
        val link: String,
        val hls: Boolean? = null,
        val mp4: Boolean? = null,
        val resolutionStr: String,
        val subtitles: List<Subtitles>? = null
    ) {
        @Serializable
        data class Subtitles(
            val lang: String,
            val src: String,
        )
    }
}

class AllAnimeExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videoFromUrl(url: String, name: String): List<Video> {
        val videoList = mutableListOf<Video>()

        val resp = client.newCall(
            GET("https://blog.allanime.pro" + url.replace("/clock?", "/clock.json?"))
        ).execute()

        if (resp.code != 200) {
            return emptyList()
        }

        val body = resp.body!!.string()
        val linkJson = json.decodeFromString<VideoLink>(body)

        for (link in linkJson.links) {
            val subtitles = mutableListOf<Track>()
            if (!link.subtitles.isNullOrEmpty()) {
                try {
                    for (sub in link.subtitles) {
                        subtitles.add(Track(sub.src, sub.lang))
                    }
                } catch (_: Error) {}
            }

            if (link.mp4 == true) {
                try {
                    videoList.add(
                        Video(
                            link.link,
                            "Original ($name - ${link.resolutionStr})",
                            link.link,
                            subtitleTracks = subtitles
                        )
                    )
                } catch (_: Error) {
                    videoList.add(
                        Video(
                            link.link,
                            "Original ($name - ${link.resolutionStr})",
                            link.link
                        )
                    )
                }
            } else if (link.hls == true) {
                val newClient = OkHttpClient()
                val resp = runCatching {
                    newClient.newCall(
                        GET(link.link, headers = Headers.headersOf("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0"))
                    ).execute()
                }.getOrNull()

                if (resp != null && resp.code == 200) {
                    val masterPlaylist = resp.body!!.string()
                    masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                        .forEach {
                            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p ($name - ${link.resolutionStr})"
                            var videoUrl = it.substringAfter("\n").substringBefore("\n")

                            if (!videoUrl.startsWith("http")) {
                                videoUrl = resp.request.url.toString().substringBeforeLast("/") + "/$videoUrl"
                            }

                            try {
                                videoList.add(Video(videoUrl, quality, videoUrl, subtitleTracks = subtitles))
                            } catch (_: Error) {
                                videoList.add(Video(videoUrl, quality, videoUrl))
                            }
                        }
                }
            } else {}
        }

        return videoList
    }
}
