package com.example.localmovielibrary.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.repository.MovieRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class FavoritesViewModel(repository: MovieRepository) : ViewModel() {
    val uiState: StateFlow<FavoritesUiState> = repository.observeMovies()
        .map { movies ->
            FavoritesUiState(
                movies = movies.filter { it.isFavorite }
                    .sortedByDescending { it.updatedAt.takeIf { updatedAt -> updatedAt > 0 } ?: it.scannedAtMillis }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesUiState())

    companion object {
        fun factory(repository: MovieRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FavoritesViewModel(repository) as T
            }
    }
}

data class FavoritesUiState(
    val movies: List<MovieEntity> = emptyList()
)
