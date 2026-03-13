package com.nidoham.opentube.repository

import com.nidoham.extractor.stream.SearchExtractor
import com.nidoham.extractor.stream.SearchExtractor.SearchFilter
import com.nidoham.extractor.stream.SearchExtractor.SearchResult
import com.nidoham.extractor.stream.SearchExtractor.SearchSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for YouTube search operations, backed by [SearchExtractor].
 *
 * Exposes coroutine-friendly wrappers for initial search and pagination.
 * All network I/O is delegated to [SearchExtractor], which runs on the IO
 * dispatcher internally; callers may invoke these functions from any coroutine
 * context.
 *
 * @property extractor The underlying NewPipe search extractor instance.
 */
@Singleton
class YouTubeSearchRepository @Inject constructor() {

    private val extractor = SearchExtractor()

    /**
     * Performs an initial YouTube search.
     *
     * @param query The user's search string.
     * @param filter Content-type filter; defaults to [SearchFilter.ALL].
     * @return A [Result] wrapping a [Pair] of [SearchResult] and [SearchSession] on
     *   success, or an exception on failure after all retries are exhausted.
     */
    suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
    ): Result<Pair<SearchResult, SearchSession>> = extractor.search(query, filter)

    /**
     * Fetches the next page of results for an active search session.
     *
     * @param session The session returned from the previous [search] or [fetchMore] call,
     *   with [SearchSession.nextPage] pointing to the page to load.
     * @return A [Result] wrapping the next [SearchResult] page on success.
     */
    suspend fun fetchMore(session: SearchSession): Result<SearchResult> =
        extractor.fetchMore(session)
}