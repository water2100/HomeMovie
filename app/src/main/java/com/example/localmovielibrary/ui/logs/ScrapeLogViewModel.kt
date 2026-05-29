package com.example.localmovielibrary.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScrapeLogViewModel(
    private val repository: StrmScrapeRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScrapeLogUiState(isLoading = true))
    val uiState: StateFlow<ScrapeLogUiState> = _uiState

    init {
        loadAsync()
        viewModelScope.launch {
            repository.logUpdates().collect {
                loadAsync(selectedDate = _uiState.value.selectedDate, showLoading = false)
            }
        }
    }

    fun selectDate(date: String) {
        loadAsync(selectedDate = date)
    }

    fun refresh() {
        loadAsync(selectedDate = _uiState.value.selectedDate)
    }

    fun clearSelected() {
        val selectedDate = _uiState.value.selectedDate
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.clearLogs(selectedDate)
            }
            loadAsync()
        }
    }

    private fun loadAsync(selectedDate: String? = null, showLoading: Boolean = true) {
        if (showLoading) {
            _uiState.update { it.copy(isLoading = true) }
        }
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                loadState(selectedDate)
            }
            _uiState.value = state
        }
    }

    private fun loadState(selectedDate: String? = null): ScrapeLogUiState {
        val dates = repository.logDates()
        val selected = selectedDate?.takeIf { it in dates } ?: dates.firstOrNull().orEmpty()
        return ScrapeLogUiState(
            dates = dates,
            selectedDate = selected,
            log = repository.readLogs(selected),
            isLoading = false
        )
    }

    companion object {
        fun factory(repository: StrmScrapeRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ScrapeLogViewModel(repository) as T
            }
    }
}

data class ScrapeLogUiState(
    val dates: List<String> = emptyList(),
    val selectedDate: String = "",
    val log: String = "",
    val isLoading: Boolean = false
)
