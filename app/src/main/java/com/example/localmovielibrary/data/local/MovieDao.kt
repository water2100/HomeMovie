package com.example.localmovielibrary.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {
    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            year, premiered, runtimeMinutes, mpaa, rating,
            posterUri, fanartUri, thumbUri,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    fun observeMovieListItems(): Flow<List<MovieListItem>>

    @Query(
        """
        SELECT id, studios, series, directors, actors, genres, tags
        FROM movies
        """
    )
    fun observeMovieLibraryMetadata(): Flow<List<MovieLibraryMetadata>>

    @Query("SELECT * FROM movies WHERE id = :id")
    fun observeMovie(id: Long): Flow<MovieEntity?>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun getMovie(id: Long): MovieEntity?

    @Query("SELECT * FROM movies WHERE videoUri = :videoUri LIMIT 1")
    suspend fun getMovieByVideoUri(videoUri: String): MovieEntity?

    @Query("SELECT * FROM movies WHERE libraryRootUri = :rootUri")
    suspend fun getMoviesByLibraryRoot(rootUri: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE libraryRootUri = :rootUri
        """
    )
    suspend fun getMoviesByLibraryRootLite(rootUri: String): List<MovieEntity>

    @Query("SELECT * FROM movies")
    suspend fun getMoviesSnapshot(): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri,
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
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri,
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
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri,
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
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri,
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
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri,
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
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri,
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
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri,
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
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri,
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
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri,
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

    @Query("UPDATE movies SET isFavorite = :isFavorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean, updatedAt: Long)

    @Query("UPDATE movies SET isWatched = :isWatched, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setWatched(id: Long, isWatched: Boolean, updatedAt: Long)

    @Query("DELETE FROM movies WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM movies WHERE libraryRootUri = :rootUri")
    suspend fun deleteByLibraryRoot(rootUri: String)
}
