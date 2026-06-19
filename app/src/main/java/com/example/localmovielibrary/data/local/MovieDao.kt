package com.example.localmovielibrary.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {
    @Query("SELECT COUNT(*) FROM movies")
    fun observeMovieListInvalidation(): Flow<Int>

    @Query("SELECT COUNT(*) FROM movies WHERE scrapeTaskStatus IN (:statuses)")
    fun observeScrapeTaskCount(statuses: List<String>): Flow<Int>

    @Query("SELECT COUNT(*) FROM movies WHERE scrapeTaskStatus = :status")
    suspend fun countScrapeTasks(status: String): Int

    @Query("SELECT COUNT(*) FROM movies WHERE scrapeTaskStatus IN (:statuses)")
    suspend fun countScrapeTasks(statuses: List<String>): Int

    @Query("SELECT * FROM movies WHERE scrapeTaskStatus IN (:statuses) ORDER BY updatedAt ASC, scannedAtMillis ASC")
    suspend fun getScrapeTaskMovies(statuses: List<String>): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, videoUri, videoName, sortTitle, title, originalTitle,
            year, premiered, runtimeMinutes, mpaa, rating,
            genres, tags, posterUri, fanartUri, thumbUri,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        ORDER BY sortTitle COLLATE NOCASE
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getMovieListItemsPage(limit: Int, offset: Int): List<MovieListItem>

    @Query("SELECT COUNT(*) FROM movies WHERE isFavorite = 1")
    fun observeFavoriteMovieListInvalidation(): Flow<Int>

    @Query(
        """
        SELECT 
            id, videoUri, videoName, sortTitle, title, originalTitle,
            year, premiered, runtimeMinutes, mpaa, rating,
            genres, tags, posterUri, fanartUri, thumbUri,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE isFavorite = 1
        ORDER BY updatedAt DESC, scannedAtMillis DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getFavoriteMovieListItemsPage(limit: Int, offset: Int): List<MovieListItem>

    @Query("SELECT COUNT(*) FROM movies")
    fun observeMoviePlaybackKeyInvalidation(): Flow<Int>

    @Query(
        """
        SELECT id, videoUri
        FROM movies
        ORDER BY id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getMoviePlaybackKeyItemsPage(limit: Int, offset: Int): List<MoviePlaybackKeyItem>

    @Query("SELECT actors AS items FROM movies WHERE actors != ''")
    suspend fun getActorMetadataLists(): List<MovieMetadataList>

    @Query("SELECT tags AS items FROM movies WHERE tags != ''")
    suspend fun getTagMetadataLists(): List<MovieMetadataList>

    @Query("SELECT genres AS items FROM movies WHERE genres != ''")
    suspend fun getGenreMetadataLists(): List<MovieMetadataList>

    @Query("SELECT studios AS items FROM movies WHERE studios != ''")
    suspend fun getStudioMetadataLists(): List<MovieMetadataList>

    @Query("SELECT series AS value FROM movies WHERE series IS NOT NULL AND series != ''")
    suspend fun getSeriesMetadataTexts(): List<MovieMetadataText>

    @Query("SELECT * FROM movies WHERE id = :id")
    fun observeMovie(id: Long): Flow<MovieEntity?>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE id = :id
        """
    )
    suspend fun getMovieLite(id: Long): MovieEntity?

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE videoUri = :videoUri
        LIMIT 1
        """
    )
    suspend fun getMovieByVideoUriLite(videoUri: String): MovieEntity?

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE libraryRootUri = :rootUri
        """
    )
    suspend fun getMoviesByLibraryRootLite(rootUri: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE libraryRootUri = :rootUri
            AND (
                videoName LIKE :pattern OR
                title LIKE :pattern OR
                originalTitle LIKE :pattern
            )
        """
    )
    suspend fun getMovieNumberCandidatesByLibraryRootLite(rootUri: String, pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            studios, series, directors, actors, genres, tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        """
    )
    suspend fun getMoviesForMetadataLookupLite(): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE actors LIKE :pattern
        """
    )
    suspend fun getMoviesForActorLookupLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE tags LIKE :pattern
        """
    )
    suspend fun getMoviesForTagLookupLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE genres LIKE :pattern
        """
    )
    suspend fun getMoviesForGenreLookupLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE studios LIKE :pattern
        """
    )
    suspend fun getMoviesForStudioLookupLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE series LIKE :pattern
        """
    )
    suspend fun getMoviesForCollectionLookupLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE actors LIKE :pattern
        """
    )
    suspend fun getSimilarCandidatesByActorLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE videoName LIKE :pattern OR title LIKE :pattern OR originalTitle LIKE :pattern
        """
    )
    suspend fun getSimilarCandidatesByCodeLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        """
    )
    suspend fun getMoviesSnapshotLite(): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE 
            title LIKE :pattern OR originalTitle LIKE :pattern OR videoName LIKE :pattern OR
            CAST(year AS TEXT) LIKE :pattern OR actors LIKE :pattern OR genres LIKE :pattern OR tags LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE title LIKE :pattern OR originalTitle LIKE :pattern OR videoName LIKE :pattern OR CAST(year AS TEXT) LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByTitleLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE actors LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByActorLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE videoName LIKE :pattern OR title LIKE :pattern OR originalTitle LIKE :pattern OR uniqueIds LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByNumberLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE tags LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByTagLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE genres LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByGenreLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE studios LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByStudioLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE series LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByCollectionLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE CAST(year AS TEXT) = :year
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByYearLite(year: String): List<MovieEntity>

    @Query("SELECT DISTINCT libraryRootUri FROM movies WHERE libraryRootUri != ''")
    suspend fun getLibraryRootUris(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(movies: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(movie: MovieEntity)

    @Transaction
    suspend fun synchronizeLibraryMovies(removedIds: List<Long>, movies: List<MovieEntity>) {
        if (removedIds.isNotEmpty()) {
            deleteByIds(removedIds)
        }
        if (movies.isNotEmpty()) {
            upsertAll(movies)
        }
    }

    @Query("UPDATE movies SET isFavorite = :isFavorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean, updatedAt: Long)

    @Query("UPDATE movies SET isWatched = :isWatched, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setWatched(id: Long, isWatched: Boolean, updatedAt: Long)

    @Query("UPDATE movies SET scrapeFailureReason = :reason, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setScrapeFailureReason(id: Long, reason: String?, updatedAt: Long)

    @Query("UPDATE movies SET scrapeTaskStatus = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setScrapeTaskStatus(id: Long, status: String, updatedAt: Long)

    @Query("UPDATE movies SET scrapeTaskStatus = :status, scrapeFailureReason = :reason, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setScrapeTaskStatusAndFailureReason(id: Long, status: String, reason: String?, updatedAt: Long)

    @Query("UPDATE movies SET scrapeTaskStatus = :toStatus, updatedAt = :updatedAt WHERE scrapeTaskStatus IN (:fromStatuses)")
    suspend fun updateScrapeTaskStatuses(fromStatuses: List<String>, toStatus: String, updatedAt: Long): Int

    @Query("UPDATE movies SET scrapeTaskStatus = :toStatus, scrapeFailureReason = NULL, updatedAt = :updatedAt WHERE scrapeTaskStatus IN (:fromStatuses)")
    suspend fun updateScrapeTaskStatusesAndClearFailureReason(fromStatuses: List<String>, toStatus: String, updatedAt: Long): Int

    @Query("DELETE FROM movies WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM movies WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM movies WHERE libraryRootUri = :rootUri")
    suspend fun deleteByLibraryRoot(rootUri: String)
}
