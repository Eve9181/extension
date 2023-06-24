package eu.kanade.tachiyomi.multisrc.animestream

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class AnimeStreamGenerator : ThemeSourceGenerator {
    override val themePkg = "animestream"

    override val themeClass = "AnimeStream"

    override val baseVersionCode = 2

    override val sources = listOf(
        SingleLang("AnimeXin", "https://animexin.vip", "all", isNsfw = false, overrideVersionCode = 4),
        SingleLang("LMAnime", "https://lmanime.com", "all", isNsfw = false, overrideVersionCode = 2),
        SingleLang("RineCloud", "https://rine.cloud", "pt-BR", isNsfw = false),
        SingleLang("Animenosub", "https://animenosub.com", "en", isNsfw = true),
        SingleLang("AnimeKhor", "https://animekhor.xyz", "en", isNsfw = false),
        SingleLang("AnimeTitans", "https://animetitans.com", "ar", isNsfw = false, overrideVersionCode = 11),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = AnimeStreamGenerator().createAll()
    }
}
