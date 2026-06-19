package com.example.localmovielibrary.ui.filter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.repository.MovieRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class FilterResultViewModel(
    private val filterType: String,
    private val filterValue: String,
    private val repository: MovieRepository
) : ViewModel() {
    private val normalizedType = filterType.lowercase()

    val uiState: StateFlow<FilterResultUiState> = repository.observeFilteredMovies(normalizedType, filterValue)
        .map { results ->
            FilterResultUiState(
                filterType = normalizedType,
                filterValue = filterValue,
                title = filterTitle(normalizedType, filterValue),
                movies = results
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilterResultUiState())

    companion object {
        fun factory(filterType: String, filterValue: String, repository: MovieRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FilterResultViewModel(filterType, filterValue, repository) as T
            }
    }
}

data class FilterResultUiState(
    val filterType: String = "",
    val filterValue: String = "",
    val title: String = "",
    val movies: List<MovieEntity> = emptyList()
)

private fun filterTitle(type: String, value: String): String = when (type) {
    "actor" -> "\u6F14\u5458\uFF1A$value"
    "tag" -> "\u6807\u7B7E\uFF1A$value"
    "genre" -> "\u7C7B\u578B\uFF1A$value"
    "year" -> "\u5E74\u4EFD\uFF1A$value"
    "studio" -> "工作室：$value"
    "collection" -> "合集：$value"
    else -> value
}
