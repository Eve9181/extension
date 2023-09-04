package eu.kanade.tachiyomi.multisrc.dooplay

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class DooPlayGenerator : ThemeSourceGenerator {
    override val themePkg = "dooplay"

    override val themeClass = "Dooplay"

    override val baseVersionCode = 1

    override val sources = listOf(
        SingleLang("AnimeOnline360", "https://animeonline360.me", "en", isNsfw = false),
        SingleLang("AnimeOnline.Ninja", "https://ww3.animeonline.ninja", "es", className = "AnimeOnlineNinja", isNsfw = false, overrideVersionCode = 31),
        SingleLang("AnimesOnline", "https://animesonline.nz", "pt-BR", isNsfw = false, overrideVersionCode = 3, pkgName = "animesgratis"),
        SingleLang("AnimePlayer", "https://animeplayer.com.br", "pt-BR", isNsfw = true, overrideVersionCode = 1),
        SingleLang("AnimePlayer", "https://animeplayer.com.br", "pt-BR", isNsfw = true),
        SingleLang("AnimeSync", "https://animesync.org", "pt-BR", isNsfw = true),
        SingleLang("AnimeSAGA", "https://www.animesaga.in", "hi", isNsfw = false, overrideVersionCode = 4),
        SingleLang("AnimesFox BR", "https://animesfox.net", "pt-BR", isNsfw = false, overrideVersionCode = 2),
        SingleLang("Animes House", "https://animeshouse.net", "pt-BR", isNsfw = false, overrideVersionCode = 5),
        SingleLang("Cinemathek", "https://cinemathek.net", "de", isNsfw = true, overrideVersionCode = 15),
        SingleLang("DonghuaX", "https://donghuax.com", "pt-BR", isNsfw = false, overrideVersionCode = 1),
        SingleLang("GoAnimes", "https://goanimes.net", "pt-BR", isNsfw = true, overrideVersionCode = 2),
        SingleLang("JetAnime", "https://ssl.jetanimes.com", "fr", isNsfw = false),
        SingleLang("Kinoking", "https://kinoking.cc", "de", isNsfw = false, overrideVersionCode = 17),
        SingleLang("Multimovies", "https://multimovies.shop", "en", isNsfw = false, overrideVersionCode = 9),
        SingleLang("pactedanime", "https://pactedanime.com", "en", isNsfw = false, className = "PactedAnime", overrideVersionCode = 4),
        SingleLang("Pi Fansubs", "https://pifansubs.org", "pt-BR", isNsfw = true, overrideVersionCode = 17),
        SingleLang("Pobreflix", "https://pobreflix.biz", "pt-BR", isNsfw = true, overrideVersionCode = 1),
        SingleLang("UniqueStream", "https://uniquestream.net", "en", isNsfw = false, overrideVersionCode = 2),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = DooPlayGenerator().createAll()
    }
}
