package com.example.localmovielibrary.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.repository.MovieRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repository: MovieRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val scope = MutableStateFlow(SearchScope.All)

    private val searchResults = combine(
        query
            .debounce(450)
            .distinctUntilChanged(),
        scope
    ) { text, selectedScope ->
        text.trim() to selectedScope
    }
        .distinctUntilChanged()
        .mapLatest { (text, selectedScope) ->
            val results = if (text.length < MIN_SEARCH_LENGTH) {
                emptyList()
            } else {
                repository.searchMovies(text, selectedScope.name)
            }
            SearchResult(
                normalizedQuery = text,
                scope = selectedScope,
                movies = results
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResult())

    val uiState: StateFlow<SearchUiState> = combine(query, scope, searchResults) { text, selectedScope, result ->
        val normalized = text.trim()
        SearchUiState(
            query = text,
            scope = selectedScope,
            results = if (result.normalizedQuery == normalized && result.scope == selectedScope) result.movies else emptyList(),
            hasQuery = normalized.length >= MIN_SEARCH_LENGTH,
            minSearchLength = MIN_SEARCH_LENGTH
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun updateQuery(value: String) {
        query.value = value
    }

    fun clearQuery() {
        query.value = ""
    }

    fun setScope(value: SearchScope) {
        scope.value = value
    }

    companion object {
        private const val MIN_SEARCH_LENGTH = 2

        fun factory(repository: MovieRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(repository) as T
            }
    }
}

data class SearchUiState(
    val query: String = "",
    val scope: SearchScope = SearchScope.All,
    val results: List<MovieEntity> = emptyList(),
    val hasQuery: Boolean = false,
    val minSearchLength: Int = 2
)

private data class SearchResult(
    val normalizedQuery: String = "",
    val scope: SearchScope = SearchScope.All,
    val movies: List<MovieEntity> = emptyList()
)

enum class SearchScope {
    All,
    Title,
    Number,
    Actor,
    Tag,
    Genre
}
