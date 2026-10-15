package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.lang.awaitSingle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import rx.Observable

interface CatalogueSource : MangaSource {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang: String

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean

    /**
     * Returns an observable containing a page with a list of manga.
     *
     * @param page the page number to retrieve.
     */
    fun fetchPopularManga(page: Int): Observable<MangasPage> =
        throw UnsupportedOperationException("Not implemented")

    /**
     * Get a page with a list of manga.
     *
     * @param page the page number to retrieve.
     */
    suspend fun getPopularManga(page: Int): MangasPage =
        fetchPopularManga(page).awaitSingle()

    /**
     * Returns an observable containing a page with a list of manga.
     *
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        throw UnsupportedOperationException("Not implemented")

    /**
     * Get a page with a list of manga.
     *
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        fetchSearchManga(page, query, filters).awaitSingle()

    /**
     * Returns an observable containing a page with a list of latest manga updates.
     *
     * @param page the page number to retrieve.
     */
    fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        throw UnsupportedOperationException("Not implemented")

    /**
     * Get a page with a list of latest manga updates.
     *
     * @param page the page number to retrieve.
     */
    suspend fun getLatestUpdates(page: Int): MangasPage =
        fetchLatestUpdates(page).awaitSingle()

    /**
     * Fetches updated information for a manga.
     *
     * @param manga The manga to fetch updates for.
     * @param chapters Existing chapters of the manga
     * @param fetchDetails Whether to include updated manga details.
     * @param fetchChapters Whether to include available chapters.
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val asyncManga = if (fetchDetails) async { getMangaDetails(manga) } else null
        val asyncChapters = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(
            asyncManga?.await() ?: manga,
            asyncChapters?.await() ?: chapters,
        )
    }

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): FilterList
}
