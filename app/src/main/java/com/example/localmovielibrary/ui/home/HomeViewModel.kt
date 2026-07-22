package com.example.localmovielibrary.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.local.MoviePlaybackKeyItem
import com.example.localmovielibrary.data.local.PlaybackProgressListItem
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.data.repository.DomesticMovieWithSources
import com.example.localmovielibrary.data.repository.MovieLibrarySummaries
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.PlaybackProgressRepository
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

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
    private val domesticPageEnabledState = MutableStateFlow(settingsRepository.isDomesticPageEnabled())
    private val displayedMovies = MutableStateFlow<List<MovieEntity>>(emptyList())
    private val pendingMovies = MutableStateFlow<List<MovieEntity>>(emptyList())
    private val pendingNewCount = MutableStateFlow(0)
    private val librarySummaries = MutableStateFlow(MovieLibrarySummaries())
    private val avatarUpdateState = scrapeRepository.actorAvatarUpdateState
    private val libraryUpdateState = combine(avatarUpdateState, pendingNewCount) { avatarUpdate, newCount ->
        avatarUpdate to newCount
    }
    private val movieBaseBuckets = displayedMovies.map { movieList ->
        HomeMovieBaseBuckets(
            sourceMovies = movieList,
            recentlyAdded = movieList.sortedByDescending { it.scannedAtMillis }.take(12),
            favoriteMovies = movieList.filter { it.isFavorite }.sortedByDescending { it.updatedAt }.take(12),
            stats = LibraryStats(
                total = movieList.size,
                watched = movieList.count { it.isWatched },
                favorites = movieList.count { it.isFavorite }
            )
        )
    }.flowOn(Dispatchers.Default)
    private val movieBuckets = combine(movieBaseBuckets, sortState) { base, sort ->
        HomeMovieBuckets(
            sourceMovies = base.sourceMovies,
            movies = if (sort.option == HomeSortOption.Random) {
                base.sourceMovies.shuffled(Random(sort.randomSeed))
            } else {
                base.sourceMovies.sortedWith(movieComparator(sort))
            },
            recentlyAdded = base.recentlyAdded,
            favoriteMovies = base.favoriteMovies,
            sortState = sort,
            stats = base.stats
        )
    }.flowOn(Dispatchers.Default)
    private val playbackProgress = playbackProgressRepository.observeRecent(HOME_RECENT_PROGRESS_LIMIT)
    private val playbackKeys = repository.observeMoviePlaybackKeys()
    private val moviesWithProgress = combine(movieBuckets, playbackProgress, playbackKeys) { buckets, progress, keys ->
        val playbackIndex = buckets.sourceMovies.toPlaybackMovieIndex(keys)
        buckets to progress.recentlyPlayedMovies(playbackIndex).take(12)
    }.flowOn(Dispatchers.Default)
    private val domesticMovies = domesticMovieRepository.observeAllWithSources()
    private val moviesWithProgressAndDomestic = combine(moviesWithProgress, domesticMovies) { movieData, domestic ->
        movieData to domestic
    }
    private val homeMovieData = combine(moviesWithProgressAndDomestic, librarySummaries) { movieData, summaries ->
        movieData to summaries
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

    val uiState: StateFlow<HomeUiState> = combine(homeMovieData, scanState, imageModeState, libraryUpdateState, domesticPageEnabledState) { combinedHomeData, scan, imageMode, libraryUpdate, domesticPageEnabled ->
        val (combinedMovieData, summaries) = combinedHomeData
        val (movieData, domestic) = combinedMovieData
        val (buckets, recentlyPlayed) = movieData
        val (avatarUpdate, newCount) = libraryUpdate
        HomeUiState(
            movies = buckets.movies,
            recentlyAdded = buckets.recentlyAdded,
            recentlyPlayed = recentlyPlayed,
            favoriteMovies = buckets.favoriteMovies,
            domesticMovies = domestic,
            domesticPageEnabled = domesticPageEnabled,
            scanState = scan,
            sortState = buckets.sortState,
            imageMode = imageMode,
            isUpdatingActorAvatars = avatarUpdate.isUpdating,
            actorAvatarUpdateMessage = avatarUpdate.message,
            actorAvatarRefreshVersion = avatarUpdate.refreshVersion,
            pendingNewCount = newCount,
            librarySummaries = summaries,
            stats = buckets.stats
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
        // 首页的影片区（含“最近添加”）始终反映影视库的最新状态。
        displayedMovies.value = latestMovies
        pendingMovies.value = emptyList()
        pendingNewCount.value = 0
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
        val next = sortState.value.copy(
            option = option,
            randomSeed = if (option == HomeSortOption.Random) Random.nextLong() else sortState.value.randomSeed
        )
        sortState.value = next
        settingsRepository.saveHomeSortOptionName(next.option.name)
    }

    fun toggleSortDirection() {
        val current = sortState.value
        val next = current.copy(
            direction = current.direction.toggle(),
            randomSeed = if (current.option == HomeSortOption.Random) Random.nextLong() else current.randomSeed
        )
        sortState.value = next
        settingsRepository.saveHomeSortDirectionName(next.direction.name)
    }

    fun setSortDirection(direction: HomeSortDirection) {
        val current = sortState.value
        val next = current.copy(
            direction = direction,
            randomSeed = if (current.option == HomeSortOption.Random) Random.nextLong() else current.randomSeed
        )
        sortState.value = next
        settingsRepository.saveHomeSortDirectionName(next.direction.name)
    }

    fun setImageMode(mode: HomeImageMode) {
        imageModeState.value = mode
        settingsRepository.saveHomeImageModeName(mode.name)
    }

    fun refreshDomesticPageEnabled() {
        domesticPageEnabledState.value = settingsRepository.isDomesticPageEnabled()
    }

    fun refreshLibrarySummaries() {
        viewModelScope.launch {
            runCatching { repository.getLibrarySummaries() }
                .onSuccess { librarySummaries.value = it }
        }
    }

    companion object {
        private const val HOME_RECENT_PROGRESS_LIMIT = 100

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
        HomeSortOption.ImdbRating -> compareNullable(left.rating, right.rating)
        HomeSortOption.DateAdded -> compareValues(left.scannedAtMillis, right.scannedAtMillis)
        HomeSortOption.ReleaseDate -> compareNullable(left.premiered ?: left.year?.toString(), right.premiered ?: right.year?.toString())
        HomeSortOption.Director -> compareStrings(left.directors.firstOrNull().orEmpty(), right.directors.firstOrNull().orEmpty())
        HomeSortOption.Year -> compareNullable(left.year, right.year)
        HomeSortOption.PlayDuration -> compareNullable(left.runtimeMinutes, right.runtimeMinutes)
        HomeSortOption.FileName -> compareStrings(left.videoName, right.videoName)
        HomeSortOption.Title -> compareStrings(left.sortTitle.ifBlank { left.title }, right.sortTitle.ifBlank { right.title })
        HomeSortOption.Random -> 0
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

private fun List<PlaybackProgressListItem>.recentlyPlayedMovies(index: PlaybackMovieIndex): List<MovieEntity> {
    if (isEmpty() || index.isEmpty) return emptyList()
    return mapNotNull { progress ->
        index.byUri[progress.mediaKey]
            ?: progress.mediaKey.parentDocumentKey()?.let { index.byParentDocument[it] }
    }.distinctBy { it.id }
}

private fun List<MovieEntity>.toPlaybackMovieIndex(keys: List<MoviePlaybackKeyItem>): PlaybackMovieIndex {
    if (isEmpty() || keys.isEmpty()) return PlaybackMovieIndex()
    val moviesById = associateBy { it.id }
    val keyPairs = keys.mapNotNull { key ->
        val movie = moviesById[key.id] ?: return@mapNotNull null
        key.videoUri to movie
    }
    return PlaybackMovieIndex(
        byUri = keyPairs.toMap(),
        byParentDocument = keyPairs.mapNotNull { (videoUri, movie) ->
            videoUri.parentDocumentKey()?.let { parent -> parent to movie }
        }.toMap()
    )
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
    val domesticPageEnabled: Boolean = false,
    val scanState: ScanState = ScanState.Idle,
    val sortState: HomeSortState = HomeSortState(),
    val imageMode: HomeImageMode = HomeImageMode.Poster,
    val isUpdatingActorAvatars: Boolean = false,
    val actorAvatarUpdateMessage: String? = null,
    val actorAvatarRefreshVersion: Int = 0,
    val pendingNewCount: Int = 0,
    val librarySummaries: MovieLibrarySummaries = MovieLibrarySummaries(),
    val stats: LibraryStats = LibraryStats()
) {
    val hasPendingMovieUpdates: Boolean get() = pendingNewCount > 0
}

data class HomeSortState(
    val option: HomeSortOption = HomeSortOption.ReleaseDate,
    val direction: HomeSortDirection = HomeSortDirection.Descending,
    val randomSeed: Long = Random.nextLong()
)

data class LibraryStats(
    val total: Int = 0,
    val watched: Int = 0,
    val favorites: Int = 0
)

private data class HomeMovieBuckets(
    val sourceMovies: List<MovieEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val recentlyAdded: List<MovieEntity> = emptyList(),
    val favoriteMovies: List<MovieEntity> = emptyList(),
    val sortState: HomeSortState = HomeSortState(),
    val stats: LibraryStats = LibraryStats()
)

private data class HomeMovieBaseBuckets(
    val sourceMovies: List<MovieEntity> = emptyList(),
    val recentlyAdded: List<MovieEntity> = emptyList(),
    val favoriteMovies: List<MovieEntity> = emptyList(),
    val stats: LibraryStats = LibraryStats()
)

private data class PlaybackMovieIndex(
    val byUri: Map<String, MovieEntity> = emptyMap(),
    val byParentDocument: Map<String, MovieEntity> = emptyMap()
) {
    val isEmpty: Boolean get() = byUri.isEmpty() && byParentDocument.isEmpty()
}

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Done(val count: Int) : ScanState
    data class Error(val message: String) : ScanState
}

enum class HomeSortOption {
    ImdbRating,
    DateAdded,
    ReleaseDate,
    Director,
    Year,
    PlayDuration,
    FileName,
    Title,
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
