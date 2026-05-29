package com.example.localmovielibrary.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.local.MovieLibraryMetadata
import com.example.localmovielibrary.data.local.PlaybackProgressEntity
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.data.repository.DomesticMovieWithSources
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.PlaybackProgressRepository
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class HomeViewModel(
    private val repository: MovieRepository,
    private val domesticMovieRepository: DomesticMovieRepository,
    private val settingsRepository: AppSettingsRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val playbackProgressRepository: PlaybackProgressRepository
) : ViewModel() {
    private val scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    private val sortState = MutableStateFlow(loadSavedSortState(settingsRepository))
    private val imageModeState = MutableStateFlow(loadSavedImageMode(settingsRepository))
    private val displayedMovies = MutableStateFlow<List<MovieEntity>>(emptyList())
    private val pendingMovies = MutableStateFlow<List<MovieEntity>>(emptyList())
    private val pendingNewCount = MutableStateFlow(0)
    private val avatarUpdateState = scrapeRepository.actorAvatarUpdateState
    private val libraryUpdateState = combine(avatarUpdateState, pendingNewCount) { avatarUpdate, newCount ->
        avatarUpdate to newCount
    }
    private val libraryMetadata = repository.observeMovieLibraryMetadata()
    private val moviesWithLibraryMetadata = combine(displayedMovies, libraryMetadata) { movieList, metadata ->
        movieList.withLibraryMetadata(metadata)
    }
    private val playbackProgress = playbackProgressRepository.observeAll()
    private val domesticMovies = domesticMovieRepository.observeAllWithSources()
    private val moviesWithProgress = combine(moviesWithLibraryMetadata, playbackProgress) { movieList, progress ->
        movieList to progress
    }
    private val moviesWithProgressAndDomestic = combine(moviesWithProgress, domesticMovies) { movieData, domestic ->
        movieData to domestic
    }

    init {
        viewModelScope.launch {
            repository.observeMovies()
                .debounce(450)
                .collect { latestMovies ->
                    handleDatabaseMovies(latestMovies)
                }
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(moviesWithProgressAndDomestic, scanState, sortState, imageModeState, libraryUpdateState) { combinedMovieData, scan, sort, imageMode, libraryUpdate ->
        val (movieData, domestic) = combinedMovieData
        val (movieList, progress) = movieData
        val (avatarUpdate, newCount) = libraryUpdate
        val sortedByRecent = movieList.sortedByDescending { it.scannedAtMillis }
        val recentlyPlayed = progress.recentlyPlayedMovies(movieList).take(12)
        val sortedMovies = movieList.sortedWith(movieComparator(sort))
        HomeUiState(
            movies = sortedMovies,
            recentlyAdded = sortedByRecent.take(12),
            recentlyPlayed = recentlyPlayed,
            favoriteMovies = movieList.filter { it.isFavorite }.sortedByDescending { it.updatedAt }.take(12),
            domesticMovies = domestic,
            scanState = scan,
            sortState = sort,
            imageMode = imageMode,
            isUpdatingActorAvatars = avatarUpdate.isUpdating,
            actorAvatarUpdateMessage = avatarUpdate.message,
            actorAvatarRefreshVersion = avatarUpdate.refreshVersion,
            pendingNewCount = newCount,
            stats = LibraryStats(
                total = movieList.size,
                watched = movieList.count { it.isWatched },
                favorites = movieList.count { it.isFavorite }
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun scanLibrary(rootUri: Uri) {
        viewModelScope.launch {
            scanState.value = ScanState.Scanning
            runCatching { repository.scanLibrary(rootUri) }
                .onSuccess { count -> scanState.value = ScanState.Done(count) }
                .onFailure { error -> scanState.value = ScanState.Error(error.message ?: "Scan failed") }
        }
    }

    fun applyPendingMovieUpdates() {
        val pending = pendingMovies.value
        if (pending.isNotEmpty()) {
            displayedMovies.value = pending
        }
        pendingMovies.value = emptyList()
        pendingNewCount.value = 0
    }

    private fun handleDatabaseMovies(latestMovies: List<MovieEntity>) {
        val currentMovies = displayedMovies.value
        if (currentMovies.isEmpty()) {
            displayedMovies.value = latestMovies
            pendingMovies.value = emptyList()
            pendingNewCount.value = 0
            return
        }

        val currentIds = currentMovies.map { it.id }.toSet()
        val latestIds = latestMovies.map { it.id }.toSet()
        val newIds = latestIds - currentIds
        if (newIds.isEmpty()) {
            displayedMovies.value = latestMovies
            pendingMovies.value = emptyList()
            pendingNewCount.value = 0
            return
        }

        val latestById = latestMovies.associateBy { it.id }
        displayedMovies.value = currentMovies.mapNotNull { latestById[it.id] }
        pendingMovies.value = latestMovies
        pendingNewCount.value = newIds.size
    }

    fun toggleFavorite(movie: MovieEntity) {
        viewModelScope.launch {
            repository.setFavorite(movie.id, !movie.isFavorite)
        }
    }

    fun toggleWatched(movie: MovieEntity) {
        viewModelScope.launch {
            repository.setWatched(movie.id, !movie.isWatched)
        }
    }

    fun setSortOption(option: HomeSortOption) {
        val next = sortState.value.copy(option = option)
        sortState.value = next
        settingsRepository.saveHomeSortOptionName(next.option.name)
    }

    fun toggleSortDirection() {
        val next = sortState.value.copy(direction = sortState.value.direction.toggle())
        sortState.value = next
        settingsRepository.saveHomeSortDirectionName(next.direction.name)
    }

    fun setSortDirection(direction: HomeSortDirection) {
        val next = sortState.value.copy(direction = direction)
        sortState.value = next
        settingsRepository.saveHomeSortDirectionName(next.direction.name)
    }

    fun setImageMode(mode: HomeImageMode) {
        imageModeState.value = mode
        settingsRepository.saveHomeImageModeName(mode.name)
    }

    fun updateMissingActorAvatars() {
        scrapeRepository.startUpdateMissingActorAvatars(uiState.value.movies)
    }

    companion object {
        fun factory(
            repository: MovieRepository,
            domesticMovieRepository: DomesticMovieRepository,
            settingsRepository: AppSettingsRepository,
            scrapeRepository: StrmScrapeRepository,
            playbackProgressRepository: PlaybackProgressRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repository, domesticMovieRepository, settingsRepository, scrapeRepository, playbackProgressRepository) as T
            }
    }
}

private fun loadSavedSortState(settingsRepository: AppSettingsRepository): HomeSortState {
    val option = settingsRepository.getHomeSortOptionName()
        ?.let { stored -> HomeSortOption.entries.firstOrNull { it.name == stored } }
        ?: HomeSortOption.ReleaseDate
    val direction = settingsRepository.getHomeSortDirectionName()
        ?.let { stored -> HomeSortDirection.entries.firstOrNull { it.name == stored } }
        ?: HomeSortDirection.Descending
    return HomeSortState(option = option, direction = direction)
}

private fun loadSavedImageMode(settingsRepository: AppSettingsRepository): HomeImageMode =
    settingsRepository.getHomeImageModeName()
        ?.let { stored -> HomeImageMode.entries.firstOrNull { it.name == stored } }
        ?: HomeImageMode.Poster

private fun movieComparator(sort: HomeSortState): Comparator<MovieEntity> =
    Comparator { left, right ->
        val result = compareBySortOption(left, right, sort.option)
            .takeIf { it != 0 }
            ?: compareStrings(left.sortTitle.ifBlank { left.title }, right.sortTitle.ifBlank { right.title })
        if (sort.direction == HomeSortDirection.Ascending) result else -result
    }

private fun compareBySortOption(left: MovieEntity, right: MovieEntity, option: HomeSortOption): Int =
    when (option) {
        HomeSortOption.ImdbRating,
        HomeSortOption.CriticRating -> compareNullable(left.rating, right.rating)
        HomeSortOption.Resolution,
        HomeSortOption.MediaContainer,
        HomeSortOption.FrameRate,
        HomeSortOption.FileSize,
        HomeSortOption.Bitrate,
        HomeSortOption.VideoCodec -> 0
        HomeSortOption.DateAdded -> compareValues(left.scannedAtMillis, right.scannedAtMillis)
        HomeSortOption.ReleaseDate -> compareNullable(left.premiered ?: left.year?.toString(), right.premiered ?: right.year?.toString())
        HomeSortOption.ParentalRating -> compareStrings(left.mpaa.orEmpty(), right.mpaa.orEmpty())
        HomeSortOption.Director -> compareStrings(left.directors.firstOrNull().orEmpty(), right.directors.firstOrNull().orEmpty())
        HomeSortOption.Year -> compareNullable(left.year, right.year)
        HomeSortOption.PlayDate -> compareValues(left.updatedAt.takeIf { left.isWatched } ?: 0L, right.updatedAt.takeIf { right.isWatched } ?: 0L)
        HomeSortOption.PlayDuration -> compareNullable(left.runtimeMinutes, right.runtimeMinutes)
        HomeSortOption.PlayCount -> compareValues(if (left.isWatched) 1 else 0, if (right.isWatched) 1 else 0)
        HomeSortOption.FileName -> compareStrings(left.videoName, right.videoName)
        HomeSortOption.Title -> compareStrings(left.sortTitle.ifBlank { left.title }, right.sortTitle.ifBlank { right.title })
        HomeSortOption.Random -> compareValues(left.id.stableRandomKey(), right.id.stableRandomKey())
    }

private fun compareStrings(left: String, right: String): Int =
    left.lowercase().compareTo(right.lowercase())

private fun <T : Comparable<T>> compareNullable(left: T?, right: T?): Int =
    when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right)
    }

private fun Long.stableRandomKey(): Long = (this * 1103515245L + 12345L) and 0x7fffffff

private fun List<MovieEntity>.withLibraryMetadata(metadata: List<MovieLibraryMetadata>): List<MovieEntity> {
    if (isEmpty() || metadata.isEmpty()) return this
    val metadataById = metadata.associateBy { it.id }
    return map { movie ->
        val item = metadataById[movie.id] ?: return@map movie
        movie.copy(
            studios = item.studios,
            series = item.series,
            directors = item.directors,
            actors = item.actors,
            genres = item.genres,
            tags = item.tags
        )
    }
}

private fun List<PlaybackProgressEntity>.recentlyPlayedMovies(movies: List<MovieEntity>): List<MovieEntity> {
    if (isEmpty() || movies.isEmpty()) return emptyList()
    val movieByUri = movies.associateBy { it.videoUri }
    val movieByParent = movies
        .mapNotNull { movie -> movie.videoUri.parentDocumentKey()?.let { it to movie } }
        .toMap()
    return mapNotNull { progress ->
        movieByUri[progress.mediaKey]
            ?: progress.mediaKey.parentDocumentKey()?.let { movieByParent[it] }
    }.distinctBy { it.id }
}

private fun String.parentDocumentKey(): String? {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
    val documentId = uri.documentIdFromSafUri() ?: return null
    return documentId.substringBeforeLast('/', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
}

private fun Uri.documentIdFromSafUri(): String? {
    val index = pathSegments.indexOf("document")
    return index.takeIf { it >= 0 && it + 1 < pathSegments.size }
        ?.let { Uri.decode(pathSegments[it + 1]) }
}

data class HomeUiState(
    val movies: List<MovieEntity> = emptyList(),
    val recentlyAdded: List<MovieEntity> = emptyList(),
    val recentlyPlayed: List<MovieEntity> = emptyList(),
    val favoriteMovies: List<MovieEntity> = emptyList(),
    val domesticMovies: List<DomesticMovieWithSources> = emptyList(),
    val scanState: ScanState = ScanState.Idle,
    val sortState: HomeSortState = HomeSortState(),
    val imageMode: HomeImageMode = HomeImageMode.Poster,
    val isUpdatingActorAvatars: Boolean = false,
    val actorAvatarUpdateMessage: String? = null,
    val actorAvatarRefreshVersion: Int = 0,
    val pendingNewCount: Int = 0,
    val stats: LibraryStats = LibraryStats()
) {
    val hasPendingMovieUpdates: Boolean get() = pendingNewCount > 0
}

data class HomeSortState(
    val option: HomeSortOption = HomeSortOption.ReleaseDate,
    val direction: HomeSortDirection = HomeSortDirection.Descending
)

data class LibraryStats(
    val total: Int = 0,
    val watched: Int = 0,
    val favorites: Int = 0
)

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Done(val count: Int) : ScanState
    data class Error(val message: String) : ScanState
}

enum class HomeSortOption {
    ImdbRating,
    Resolution,
    DateAdded,
    ReleaseDate,
    MediaContainer,
    ParentalRating,
    Director,
    FrameRate,
    Year,
    CriticRating,
    PlayDate,
    PlayDuration,
    PlayCount,
    FileName,
    FileSize,
    Title,
    Bitrate,
    VideoCodec,
    Random
}

enum class HomeSortDirection {
    Ascending,
    Descending;

    fun toggle(): HomeSortDirection =
        if (this == Ascending) Descending else Ascending
}

enum class HomeImageMode {
    Poster,
    Thumb
}
