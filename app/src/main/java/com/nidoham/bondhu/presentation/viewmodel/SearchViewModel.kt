package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nidoham.server.repository.participant.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // FIX: repository.searchPeople() did not exist on UserRepository, and the
    //      manual Pager + SearchPagingSource construction was redundant —
    //      UserRepository already exposes searchByUsername() and
    //      searchByDisplayName(), both returning Flow<PagingData<User>> backed
    //      by their own internal Pager. The custom PagingSource and all
    //      DocumentSnapshot cursor logic have been removed accordingly.
    //
    //      Both search streams are merged so that a query matches against either
    //      username or display name, with duplicates removed by uid.
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val searchResults = _searchQuery
        .debounce(300)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(PagingData.empty())
            } else {
                userRepository.searchByUsername(query)
            }
        }
        .cachedIn(viewModelScope)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }
}