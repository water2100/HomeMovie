package com.example.localmovielibrary.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INITIAL_VISIBLE_LINES = 180
private const val LOG_PAGE_SIZE = 180
private const val LOG_UPDATE_DEBOUNCE_MS = 120L

@OptIn(FlowPreview::class)
class ScrapeLogViewModel(
    private val repository: StrmScrapeRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScrapeLogUiState(isLoading = true))
    val uiState: StateFlow<ScrapeLogUiState> = _uiState
    private var loadJob: Job? = null

    init {
        loadAsync()
        viewModelScope.launch {
            repository.logUpdates().debounce(LOG_UPDATE_DEBOUNCE_MS).collect {
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

    fun loadMore() {
        _uiState.update { state ->
            val nextCount = (state.visibleLineCount + LOG_PAGE_SIZE).coerceAtMost(state.totalLineCount)
            state.copy(
                visibleLineCount = nextCount,
                visibleLines = state.allLines.take(nextCount)
            )
        }
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
        loadJob?.cancel()
        val previousVisibleLineCount = _uiState.value.visibleLineCount
        if (showLoading) {
            _uiState.update { it.copy(isLoading = true) }
        }
        loadJob = viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                loadState(selectedDate, previousVisibleLineCount)
            }
            _uiState.value = state
        }
    }

    private fun loadState(selectedDate: String? = null, visibleLineHint: Int = INITIAL_VISIBLE_LINES): ScrapeLogUiState {
        val dates = repository.logDates()
        val selected = selectedDate?.takeIf { it in dates } ?: dates.firstOrNull().orEmpty()
        return ScrapeLogUiState(
            dates = dates,
            selectedDate = selected,
            log = repository.readLogs(selected),
            isLoading = false
        ).withVisibleLines(visibleLineHint)
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
    val allLines: List<String> = emptyList(),
    val visibleLines: List<String> = emptyList(),
    val visibleLineCount: Int = 0,
    val totalLineCount: Int = 0,
    val isLoading: Boolean = false
) {
    val hasMoreLines: Boolean get() = visibleLineCount < totalLineCount
}

private fun ScrapeLogUiState.withVisibleLines(visibleLineHint: Int): ScrapeLogUiState {
    val lines = log.lineSequence()
        .filter { it.isNotBlank() }
        .toList()
    val requestedCount = maxOf(INITIAL_VISIBLE_LINES, visibleLineHint)
    val count = minOf(lines.size, requestedCount)
    return copy(
        allLines = lines,
        visibleLines = lines.take(count),
        visibleLineCount = count,
        totalLineCount = lines.size
    )
}
