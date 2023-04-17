package eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors

import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.VideoDto
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES.decodeHex
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class KickAssAnimeExtractor(private val client: OkHttpClient, private val json: Json) {
    private val isStable by lazy {
        runCatching {
            Track("", "")
            false
        }.getOrDefault(true)
    }

    fun videosFromUrl(url: String): List<Video> {
        val idQuery = url.substringAfterLast("?")
        val baseUrl = url.substringBeforeLast("/") // baseUrl + endpoint/player
        val response = client.newCall(GET("$baseUrl/source.php?$idQuery")).execute()
            .body.string()

        val (encryptedData, ivhex) = response.substringAfter(":\"")
            .substringBefore('"')
            .replace("\\", "")
            .split(":")

        // TODO: Create something to get the key dynamically.
        // Maybe we can do something like what is being used at Dopebox, Sflix and Zoro:
        // Leave the hard work to github actions and make the extension just fetch the key
        // from the repository.
        val key = "7191d608bd4deb4dc36f656c4bbca1b7".toByteArray()
        val iv = ivhex.decodeHex()

        val videoObject = try {
            val decrypted = CryptoAES.decrypt(encryptedData, key, iv)
            json.decodeFromString<VideoDto>(decrypted)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        val subtitles = if (isStable || videoObject.subtitles.isEmpty()) {
            emptyList()
        } else {
            videoObject.subtitles.map {
                val subUrl: String = it.src.let { src ->
                    if (src.startsWith("/")) {
                        baseUrl.substringBeforeLast("/") + "/$src"
                    } else {
                        src
                    }
                }

                val language = "${it.name} (${it.language})"

                println("subUrl -> $subUrl")
                Track(subUrl, language)
            }
        }

        val masterPlaylist = client.newCall(GET(videoObject.playlistUrl)).execute()
            .body.string()

        val prefix = if ("pink" in url) "PinkBird" else "SapphireDuck"

        return when {
            videoObject.hls.isBlank() ->
                extractVideosFromDash(masterPlaylist, prefix, subtitles)
            else -> extractVideosFromHLS(masterPlaylist, prefix, subtitles)
        }
    }

    private fun extractVideosFromHLS(playlist: String, prefix: String, subs: List<Track>): List<Video> {
        val separator = "#EXT-X-STREAM-INF"
        return playlist.substringAfter(separator).split(separator).map {
            val resolution = it.substringAfter("RESOLUTION=")
                .substringBefore("\n")
                .substringAfter("x")
                .substringBefore(",") + "p"

            val videoUrl = it.substringAfter("\n").substringBefore("\n")

            if (isStable) {
                Video(videoUrl, "$prefix - $resolution", videoUrl)
            } else {
                Video(videoUrl, "$prefix - $resolution", videoUrl, subtitleTracks = subs)
            }
        }
    }

    private fun extractVideosFromDash(playlist: String, prefix: String, subs: List<Track>): List<Video> {
        return playlist.split("<Representation").drop(1).dropLast(1).map {
            val resolution = it.substringAfter("height=\"").substringBefore('"') + "p"
            val url = it.substringAfter("<BaseURL>").substringBefore("</Base")
                .replace("&amp;", "&")
            if (isStable) {
                Video(url, "$prefix - $resolution", url)
            } else {
                Video(url, "$prefix - $resolution", url, subtitleTracks = subs)
            }
        }
    }
}
