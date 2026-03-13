package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nidoham.extractor.stream.SearchExtractor.SearchSession
import com.nidoham.extractor.stream.StreamItem
import com.nidoham.opentube.repository.YouTubeSearchRepository
import com.nidoham.server.domain.participant.User
import com.nidoham.server.repository.participant.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class SearchTab { PEOPLE, YOUTUBE }

/**
 * UI state for the YouTube search tab.
 *
 * @property items Accumulated list of [StreamItem] results across all loaded pages.
 * @property isLoading True while the first page of a new query is being fetched.
 * @property isLoadingMore True while a subsequent page is being fetched.
 * @property hasNextPage True when the current session has more pages available.
 * @property error Non-null when the last operation failed; contains a human-readable message.
 * @property searchedQuery The query string that produced the current [items]. Used to render
 *   the empty state with accurate text.
 */
data class YouTubeSearchState(
    val items: List<StreamItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasNextPage: Boolean = false,
    val error: String? = null,
    val searchedQuery: String = "",
)

/**
 * ViewModel for the search screen.
 *
 * Manages two independent data streams:
 * - **People** — a Paging 3 flow gated on [SearchTab.PEOPLE] and debounced query changes.
 * - **YouTube** — manual state driven by [YouTubeSearchRepository]; supports initial search,
 *   pagination via [loadMoreYouTube], and retry via [retryYouTubeSearch].
 *
 * YouTube searches are triggered automatically when the active tab is [SearchTab.YOUTUBE] and
 * the query changes (debounced at 400 ms). Switching to the YouTube tab with a non-blank query
 * in the field also triggers an immediate search.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val youTubeRepository: YouTubeSearchRepository,
) : ViewModel() {

    // ── Shared query / tab ─────────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchTab = MutableStateFlow(SearchTab.PEOPLE)
    val searchTab: StateFlow<SearchTab> = _searchTab.asStateFlow()

    // ── People (Paging 3) ──────────────────────────────────────────────────────

    val peopleResults: Flow<PagingData<User>> = combine(
        _searchQuery.debounce(300).distinctUntilChanged(),
        _searchTab,
    ) { query, tab -> query to tab }
        .flatMapLatest { (query, tab) ->
            when {
                tab != SearchTab.PEOPLE -> flowOf(PagingData.empty())
                query.isBlank()         -> flowOf(PagingData.empty())
                else                    -> userRepository.searchUsers(query)
            }
        }
        .cachedIn(viewModelScope)

    // ── YouTube (manual state + session) ──────────────────────────────────────

    private val _youTubeState = MutableStateFlow(YouTubeSearchState())
    val youTubeState: StateFlow<YouTubeSearchState> = _youTubeState.asStateFlow()

    /** Holds the active NewPipe session for pagination; null when idle or after an error. */
    private var currentSession: SearchSession? = null

    /** Tracks the in-flight initial search job so we can cancel it on a new query. */
    private var searchJob: Job? = null

    init {
        // React to combined query + tab changes to drive YouTube search.
        viewModelScope.launch {
            combine(
                _searchQuery.debounce(400).distinctUntilChanged(),
                _searchTab,
            ) { query, tab -> query to tab }
                .collect { (query, tab) ->
                    if (tab == SearchTab.YOUTUBE) {
                        if (query.isBlank()) {
                            resetYouTubeState()
                        } else {
                            performYouTubeSearch(query)
                        }
                    }
                }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onTabSelected(tab: SearchTab) {
        _searchTab.value = tab
    }

    /**
     * Loads the next page of results for the current YouTube search session.
     * No-op if there is no active session, no next page, or a load is already in progress.
     */
    fun loadMoreYouTube() {
        val session = currentSession ?: return
        val state = _youTubeState.value
        if (!state.hasNextPage || state.isLoadingMore || state.isLoading) return

        viewModelScope.launch {
            _youTubeState.value = state.copy(isLoadingMore = true, error = null)
            youTubeRepository.fetchMore(session)
                .onSuccess { result ->
                    currentSession = session.copy(nextPage = result.nextPage)
                    _youTubeState.value = _youTubeState.value.copy(
                        items = _youTubeState.value.items + result.items,
                        isLoadingMore = false,
                        hasNextPage = result.hasNextPage,
                    )
                }
                .onFailure { error ->
                    Timber.e(error, "YouTube fetchMore failed")
                    _youTubeState.value = _youTubeState.value.copy(
                        isLoadingMore = false,
                        error = error.localizedMessage ?: "Failed to load more results.",
                    )
                }
        }
    }

    /**
     * Retries the last YouTube search query. No-op if the query is blank.
     */
    fun retryYouTubeSearch() {
        val query = _searchQuery.value
        if (query.isNotBlank()) performYouTubeSearch(query)
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private fun performYouTubeSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            currentSession = null
            _youTubeState.value = YouTubeSearchState(isLoading = true)

            youTubeRepository.search(query)
                .onSuccess { (result, session) ->
                    currentSession = session.copy(nextPage = result.nextPage)
                    _youTubeState.value = YouTubeSearchState(
                        items = result.items,
                        isLoading = false,
                        hasNextPage = result.hasNextPage,
                        searchedQuery = query,
                    )
                }
                .onFailure { error ->
                    Timber.e(error, "YouTube search failed for query='$query'")
                    _youTubeState.value = YouTubeSearchState(
                        isLoading = false,
                        error = error.localizedMessage ?: "Search failed. Please try again.",
                        searchedQuery = query,
                    )
                }
        }
    }

    private fun resetYouTubeState() {
        searchJob?.cancel()
        currentSession = null
        _youTubeState.value = YouTubeSearchState()
    }
}