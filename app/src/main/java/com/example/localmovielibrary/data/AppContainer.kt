package com.example.localmovielibrary.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.localmovielibrary.asr.AsrModelManager
import com.example.localmovielibrary.cloud115.Cloud115ApiClient
import com.example.localmovielibrary.cloud115.Cloud115CookieProvider
import com.example.localmovielibrary.cloud115.Cloud115QrLoginClient
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.AppUpdateRepository
import com.example.localmovielibrary.data.repository.Cloud115StrmRepository
import com.example.localmovielibrary.data.local.AppDatabase
import com.example.localmovielibrary.data.repository.CloudFolderBatchTaskRepository
import com.example.localmovielibrary.data.repository.CloudFolderBatchTaskRunner
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.DirectLinkRepository
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.PlaybackProgressRepository
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.scanner.LibraryScanner

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "local_movies.db"
    ).addMigrations(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13
    ).build()

    val scanner = LibraryScanner(appContext)
    val settingsRepository = AppSettingsRepository(appContext)
    val appUpdateRepository = AppUpdateRepository(appContext, settingsRepository)
    val asrModelManager = AsrModelManager(appContext, settingsRepository)
    val cloud115Client = Cloud115ApiClient(Cloud115CookieProvider(appContext))
    val cloud115QrLoginClient = Cloud115QrLoginClient(appContext, settingsRepository)
    val cloudStrmRecordRepository = CloudStrmRecordRepository(
        context = appContext,
        dao = database.cloudStrmRecordDao(),
        movieDao = database.movieDao(),
        settingsRepository = settingsRepository
    )
    val cloud115StrmRepository = Cloud115StrmRepository(
        context = appContext,
        cloud115Client = cloud115Client,
        settingsRepository = settingsRepository,
        recordRepository = cloudStrmRecordRepository
    )
    val strmScrapeRepository = StrmScrapeRepository(
        context = appContext,
        settingsRepository = settingsRepository
    )
    val directLinkRepository = DirectLinkRepository(
        directLinkDao = database.directLinkDao(),
        cloud115Client = cloud115Client
    )
    val playbackProgressRepository = PlaybackProgressRepository(database.playbackProgressDao())
    val cloudFolderBatchTaskRepository = CloudFolderBatchTaskRepository(database.cloudFolderBatchTaskDao())
    val domesticMovieRepository = DomesticMovieRepository(
        context = appContext,
        dao = database.domesticMovieDao(),
        sourceDao = database.domesticVideoSourceDao(),
        cloud115Client = cloud115Client,
        settingsRepository = settingsRepository
    )
    val movieRepository = MovieRepository(
        context = appContext,
        movieDao = database.movieDao(),
        cloudStrmRecordDao = database.cloudStrmRecordDao(),
        scanner = scanner,
        contentResolver = appContext.contentResolver
    )
    val cloudFolderBatchTaskRunner = CloudFolderBatchTaskRunner(
        taskRepository = cloudFolderBatchTaskRepository,
        strmRepository = cloud115StrmRepository,
        recordRepository = cloudStrmRecordRepository,
        settingsRepository = settingsRepository,
        movieRepository = movieRepository,
        scrapeRepository = strmScrapeRepository
    )

    private companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN mpaa TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE movies ADD COLUMN isWatched INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE movies ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `direct_links` (
                        `pickcode` TEXT NOT NULL, 
                        `url` TEXT NOT NULL, 
                        `expiresAt` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`pickcode`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_progress` (
                        `mediaKey` TEXT NOT NULL,
                        `positionMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`mediaKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN series TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_strm_records` (
                        `pickcode` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `movieNumber` TEXT,
                        `variant` TEXT,
                        `partLabel` TEXT,
                        `strmUri` TEXT NOT NULL,
                        `libraryRootUri` TEXT,
                        `movieId` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`pickcode`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_strm_records_movieNumber` ON `cloud_strm_records` (`movieNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_strm_records_libraryRootUri` ON `cloud_strm_records` (`libraryRootUri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_strm_records_movieId` ON `cloud_strm_records` (`movieId`)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `domestic_movies` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `folderCid` INTEGER NOT NULL,
                        `folderName` TEXT NOT NULL,
                        `videoName` TEXT NOT NULL,
                        `videoPickcode` TEXT NOT NULL,
                        `imageName` TEXT,
                        `imagePickcode` TEXT,
                        `imageUrl` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_domestic_movies_folderCid` ON `domestic_movies` (`folderCid`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_domestic_movies_videoPickcode` ON `domestic_movies` (`videoPickcode`)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `domestic_video_sources` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `folderCid` INTEGER NOT NULL,
                        `videoName` TEXT NOT NULL,
                        `videoPickcode` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_domestic_video_sources_folderCid` ON `domestic_video_sources` (`folderCid`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_domestic_video_sources_videoPickcode` ON `domestic_video_sources` (`videoPickcode`)")
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `domestic_video_sources` (
                        `folderCid`, `videoName`, `videoPickcode`, `sortOrder`, `createdAt`, `updatedAt`
                    )
                    SELECT `folderCid`, `videoName`, `videoPickcode`, 0, `createdAt`, `updatedAt`
                    FROM `domestic_movies`
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sortTitle` ON `movies` (`sortTitle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_scannedAtMillis` ON `movies` (`scannedAtMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_updatedAt` ON `movies` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_year` ON `movies` (`year`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_isFavorite` ON `movies` (`isFavorite`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_isWatched` ON `movies` (`isWatched`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_direct_links_expiresAt` ON `direct_links` (`expiresAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_progress_updatedAt` ON `playback_progress` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_strm_records_movieNumber_partLabel_variant_fileName` ON `cloud_strm_records` (`movieNumber`, `partLabel`, `variant`, `fileName`)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN scrapeFailureReason TEXT")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN scrapeTaskStatus TEXT NOT NULL DEFAULT 'None'")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_scrapeTaskStatus` ON `movies` (`scrapeTaskStatus`)")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_folder_batch_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `folderCid` INTEGER NOT NULL,
                        `folderName` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `currentPath` TEXT,
                        `currentFileName` TEXT,
                        `queuedVideos` INTEGER NOT NULL,
                        `processedVideos` INTEGER NOT NULL,
                        `addedVideos` INTEGER NOT NULL,
                        `scrapeFailedVideos` INTEGER NOT NULL,
                        `skippedVideos` INTEGER NOT NULL,
                        `failedVideos` INTEGER NOT NULL,
                        `failedFolders` INTEGER NOT NULL,
                        `failureMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_folder_batch_tasks_status` ON `cloud_folder_batch_tasks` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_folder_batch_tasks_folderCid` ON `cloud_folder_batch_tasks` (`folderCid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_folder_batch_tasks_updatedAt` ON `cloud_folder_batch_tasks` (`updatedAt`)")
            }
        }
    }
}
