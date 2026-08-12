package com.example.localmovielibrary.data.repository

import android.net.Uri
import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.data.local.CloudVideoTaskEntity
import com.example.localmovielibrary.scraper.MovieNumberExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CloudVideoAddProcessor(
    private val strmRepository: Cloud115StrmRepository,
    private val recordRepository: CloudStrmRecordRepository,
    private val settingsRepository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository
) {
    private val addLocks = mutableMapOf<String, Mutex>()

    suspend fun process(task: CloudVideoTaskEntity) {
        process(
            item = task.toCloudFileItem(),
            pickcode = task.pickcode,
            forceDistinct = task.forceDistinct
        )
    }

    /** Single-video and folder imports share this complete import-and-scrape pipeline. */
    suspend fun process(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ) {
        val lockKey = extractMovieNumberWithRules(item.name)?.uppercase()
            ?: item.name.trim().lowercase()
        val lock = synchronized(addLocks) { addLocks.getOrPut(lockKey) { Mutex() } }
        if (lock.isLocked) {
            scrapeRepository.appendLog("同番号持久化任务等待整理完成：$lockKey")
        }
        lock.withLock {
            processLocked(item, pickcode, forceDistinct)
        }
    }

    private suspend fun processLocked(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ) {
        scrapeRepository.appendLog("开始处理持久化网盘任务：${item.name} / $pickcode")
        val generated = strmRepository.generateStrmForVideo(item, forceDistinct = forceDistinct)
        val libraryRoot = settingsRepository.getLibraryRootUri()
            ?: error("请先到设置页选择影片库目录")
        val rootUri = Uri.parse(libraryRoot)

        if (!generated.shouldScrape) {
            scrapeRepository.appendLog("附加播放源 STRM 写入完成，不单独入库：${generated.fileName}")
            return
        }

        scrapeRepository.appendLog("STRM 写入完成，开始扫描单个 STRM：${generated.fileName}")
        val addedMovie = movieRepository.scanSingleMovie(
            rootUri,
            Uri.parse(generated.strmUri),
            mergeByMovieNumber = !generated.forceDistinct
        )
        addedMovie?.let { recordRepository.attachMovie(pickcode, it.id) }

        val number = extractMovieNumberWithRules(generated.fileName, item.name)
        if (number == null) {
            val reason = "STRM 已添加，但无法从文件名提取番号，无法自动刮削"
            addedMovie?.let { movieRepository.markScrapeTaskFailed(it.id, reason) }
            error(reason)
        }
        val movieForScrape = addedMovie
            ?: movieRepository.findMovieByNumberAndVariant(libraryRoot, number, generated.fileName)
            ?: error("STRM 已添加，但扫描后没有在影片库中找到 $number")
        recordRepository.attachMovie(pickcode, movieForScrape.id)
        movieRepository.markScrapeTaskPending(movieForScrape.id)

        val source = settingsRepository.getDefaultScrapeSource()
        movieRepository.markScrapeTaskRunning(movieForScrape.id)
        scrapeRepository.appendLog("开始刮削持久化网盘任务：$number，来源：$source")
        val scrapeResult = try {
            scrapeRepository.scrapeStrmUriWithOutput(
                libraryRootUri = libraryRoot,
                strmUri = generated.strmUri,
                source = source,
                forceDistinct = generated.forceDistinct
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                movieRepository.markScrapeTaskPending(movieForScrape.id)
            }
            throw error
        } catch (error: Throwable) {
            val reason = "刮削来源 ${source.name} 失败：${error.message ?: error::class.java.simpleName}"
            movieRepository.markScrapeTaskFailed(movieForScrape.id, reason)
            throw IllegalStateException(reason, error)
        }

        scrapeRepository.appendLog("文件整理完成，开始同步影片数据库：$number")
        val refreshedMovie = finalizeOrganizedScrape(
            refreshMovie = {
                movieRepository.refreshMovieAfterScrape(
                    original = movieForScrape,
                    scrapedStrmUri = scrapeResult.strmUri,
                    mergeByMovieNumber = !generated.forceDistinct
                )
            },
            markCompleted = { movieRepository.markScrapeTaskCompleted(it.id) },
            markFailed = { reason -> movieRepository.markScrapeTaskFailed(movieForScrape.id, reason) },
            missingMovieMessage = "整理后的 STRM 已写入，但影片路径或元数据未同步到数据库：$number"
        )
        recordRepository.updateStrmLocation(
            pickcode = pickcode,
            strmUri = refreshedMovie.videoUri,
            libraryRootUri = refreshedMovie.libraryRootUri,
            movieId = refreshedMovie.id
        )
        scrapeRepository.appendLog(
            "[媒体路径] 状态=成功，操作=网盘影片刮削整理并更新数据库，movieId=${refreshedMovie.id}，文件=${refreshedMovie.videoName}，详情=影片表和 STRM 索引表已同步"
        )
        scrapeRepository.appendLog("持久化网盘任务完成：$number / $pickcode")
    }

    private suspend fun extractMovieNumberWithRules(vararg values: String?): String? {
        scrapeRepository.loadCachedNumberRecognitionRules()
        values.mapNotNull { it?.takeIf(String::isNotBlank) }.forEach { value ->
            MovieNumberExtractor.extract(value)?.let { return it }
        }
        return null
    }
}

private fun CloudVideoTaskEntity.toCloudFileItem(): Cloud115FileItem =
    Cloud115FileItem(
        name = fileName,
        cid = cid,
        fid = fid,
        pickcode = pickcode,
        size = size,
        addedAt = addedAt,
        modifiedAt = modifiedAt,
        isDirectory = false
    )

internal suspend fun <T : Any> finalizeOrganizedScrape(
    refreshMovie: suspend () -> T?,
    markCompleted: suspend (T) -> Unit,
    markFailed: suspend (String) -> Unit,
    missingMovieMessage: String
): T {
    return try {
        val refreshedMovie = refreshMovie() ?: error(missingMovieMessage)
        markCompleted(refreshedMovie)
        refreshedMovie
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        markFailed(error.message?.takeIf(String::isNotBlank) ?: missingMovieMessage)
        throw error
    }
}
