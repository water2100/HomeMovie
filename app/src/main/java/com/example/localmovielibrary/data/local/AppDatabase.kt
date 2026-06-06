package com.example.localmovielibrary.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [MovieEntity::class, DirectLinkEntity::class, PlaybackProgressEntity::class, CloudStrmRecordEntity::class, DomesticMovieEntity::class, DomesticVideoSourceEntity::class],
    version = 10,
    exportSchema = false
)
@TypeConverters(MovieTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun movieDao(): MovieDao
    abstract fun directLinkDao(): DirectLinkDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun cloudStrmRecordDao(): CloudStrmRecordDao
    abstract fun domesticMovieDao(): DomesticMovieDao
    abstract fun domesticVideoSourceDao(): DomesticVideoSourceDao
}
