package eu.kanade.tachiyomi.animeextension.pt.animestc

import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.AnimeDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime

object ATCFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class TriStateFilterList(name: String, values: List<TriState>) : AnimeFilter.Group<AnimeFilter.TriState>(name, values)
    private class TriStateVal(name: String) : AnimeFilter.TriState(name)

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    class InitialLetterFilter : QueryPartFilter("Primeira letra", ATCFiltersData.INITIAL_LETTER)
    class StatusFilter : QueryPartFilter("Status", ATCFiltersData.STATUS)

    class SortFilter : AnimeFilter.Sort(
        "Ordenar",
        ATCFiltersData.ORDERS.map { it.first }.toTypedArray(),
        Selection(0, true),
    )

    class GenresFilter : TriStateFilterList(
        "Gêneros",
        ATCFiltersData.GENRES.map(::TriStateVal),
    )

    val FILTER_LIST get() = AnimeFilterList(
        InitialLetterFilter(),
        StatusFilter(),
        SortFilter(),

        AnimeFilter.Separator(),
        GenresFilter(),
    )

    data class FilterSearchParams(
        val initialLetter: String = "",
        val status: String = "",
        var orderAscending: Boolean = true,
        var sortBy: String = "",
        val blackListedGenres: ArrayList<String> = ArrayList(),
        val includedGenres: ArrayList<String> = ArrayList(),
        var animeName: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        val searchParams = FilterSearchParams(
            filters.asQueryPart<InitialLetterFilter>(),
            filters.asQueryPart<StatusFilter>(),
        )

        filters.getFirst<SortFilter>().state?.let {
            val order = ATCFiltersData.ORDERS[it.index].second
            searchParams.orderAscending = it.ascending
            searchParams.sortBy = order
        }

        filters.getFirst<GenresFilter>()
            .state.forEach { genre ->
                if (genre.isIncluded()) {
                    searchParams.includedGenres.add(genre.name)
                } else if (genre.isExcluded()) {
                    searchParams.blackListedGenres.add(genre.name)
                }
            }

        return searchParams
    }

    private fun mustRemove(anime: AnimeDto, params: FilterSearchParams): Boolean {
        return when {
            params.animeName != "" && !anime.title.contains(params.animeName, true) -> true
            params.initialLetter != "" && !anime.title.lowercase().startsWith(params.initialLetter) -> true
            params.blackListedGenres.size > 0 && params.blackListedGenres.any {
                anime.genres.contains(it, true)
            } -> true
            params.includedGenres.size > 0 && params.includedGenres.any {
                !anime.genres.contains(it, true)
            } -> true
            params.status != "" && anime.status != SAnime.UNKNOWN && anime.status != params.status.toInt() -> true
            else -> false
        }
    }

    private inline fun <T, R : Comparable<R>> List<T>.sortedByIf(
        isAscending: Boolean,
        crossinline selector: (T) -> R,
    ): List<T> {
        return when {
            isAscending -> sortedBy(selector)
            else -> sortedByDescending(selector)
        }
    }

    fun List<AnimeDto>.applyFilterParams(params: FilterSearchParams): List<AnimeDto> {
        return filterNot { mustRemove(it, params) }.let { results ->
            when (params.sortBy) {
                "A-Z" -> results.sortedByIf(params.orderAscending) { it.title.lowercase() }
                "year" -> results.sortedByIf(params.orderAscending) { it.year ?: 0 }
                else -> results
            }
        }
    }

    private object ATCFiltersData {

        val ORDERS = arrayOf(
            Pair("Alfabeticamente", "A-Z"),
            Pair("Por ano", "year"),
        )

        val STATUS = arrayOf(
            Pair("Selecione", ""),
            Pair("Completo", SAnime.COMPLETED.toString()),
            Pair("Em Lançamento", SAnime.ONGOING.toString()),
        )

        val INITIAL_LETTER = arrayOf(Pair("Selecione", "")) + ('A'..'Z').map {
            Pair(it.toString(), it.toString().lowercase())
        }.toTypedArray()

        val GENRES = arrayOf(
            "Ação",
            "Action",
            "Adventure",
            "Artes Marciais",
            "Aventura",
            "Carros",
            "Comédia",
            "Comédia Romântica",
            "Demônios",
            "Drama",
            "Ecchi",
            "Escolar",
            "Esporte",
            "Fantasia",
            "Historical",
            "Histórico",
            "Horror",
            "Jogos",
            "Kids",
            "Live Action",
            "Magia",
            "Mecha",
            "Militar",
            "Mistério",
            "Psicológico",
            "Romance",
            "Samurai",
            "School Life",
            "Sci-Fi", // Yeah
            "SciFi",
            "Seinen",
            "Shoujo",
            "Shounen",
            "Sobrenatural",
            "Super Poder",
            "Supernatural",
            "Terror",
            "Tragédia",
            "Vampiro",
            "Vida Escolar",
        )
    }
}
