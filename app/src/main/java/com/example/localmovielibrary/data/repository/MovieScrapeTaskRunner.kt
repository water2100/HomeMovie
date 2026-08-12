package com.example.localmovielibrary.data.repository

import com.example.localmovielibrary.data.local.MovieEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

class MovieScrapeTaskRunner(
    private val settingsRepository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val cloudVideoTaskRepository: CloudVideoTaskRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MovieScrapeTaskRunnerState())
    private val lock = Any()
    private var job: Job? = null
    private var restartRequested = false

    val state: StateFlow<MovieScrapeTaskRunnerState> = _state

    fun startIssues() {
        synchronized(lock) {
            val currentJob = job
            if (currentJob?.isCompleted == false) {
                if (currentJob.isCancelled) restartRequested = true
                return
            }
            val nextJob = scope.launch(start = CoroutineStart.LAZY) { runIssues() }
            nextJob.invokeOnCompletion {
                val shouldRestart = synchronized(lock) {
                    if (job === nextJob) job = null
                    restartRequested.also { restartRequested = false }
                }
                if (shouldRestart) startIssues()
            }
            job = nextJob
            nextJob.start()
        }
    }

    private suspend fun runIssues() {
            val cloudOwnedMovieIds = cloudVideoTaskRepository.unfinishedOwnedMovieIds()
            movieRepository.resetRunningScrapeTasks(excludedMovieIds = cloudOwnedMovieIds)
            val source = settingsRepository.getDefaultScrapeSource()
            val issueMovies = movieRepository.getScrapeIssueMovies()
            val invalidPathMovies = issueMovies.filter(MovieEntity::hasInvalidStoredMediaPath)
            val tasks = excludeOwnedScrapeTasks(
                tasks = issueMovies.filterNot(MovieEntity::hasInvalidStoredMediaPath),
                ownedMovieIds = cloudOwnedMovieIds,
                idOf = { it.id }
            )
            if (invalidPathMovies.isNotEmpty()) {
                scrapeRepository.appendLog(
                    "[媒体路径] 状态=跳过，操作=影片批量刮削，数量=${invalidPathMovies.size}，详情=这些影片此前已确认数据库路径失效，本次不重复尝试；请在设置页手动扫描"
                )
            }
            if (tasks.isEmpty()) {
                _state.value = MovieScrapeTaskRunnerState(message = "没有待刮削的影片")
                return
            }
            val success = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val processed = AtomicInteger(0)
            val concurrency = settingsRepository.getScrapeConcurrencyLimit()
            _state.value = MovieScrapeTaskRunnerState(
                isRunning = true,
                total = tasks.size,
                message = "正在准备影片批量刮削..."
            )
            scrapeRepository.appendLog("影片批量刮削开始：共 ${tasks.size} 个，并发=$concurrency，来源：${source.name}")
            try {
                runMovieBatchWorkers(tasks, concurrency) { index, movie ->
                    val label = movie.videoName.ifBlank { movie.title }
                    _state.update { state -> state.copy(
                        success = success.get(),
                        failed = failed.get(),
                        message = "正在刮削：$label"
                    ) }
                    val outcome = processMovieScrapeTask(
                        markRunning = { movieRepository.markScrapeTaskRunning(movie.id) },
                        runScrape = {
                            val result = scrapeRepository.scrapeMovieWithOutput(movie, source)
                            val refreshed = movieRepository.refreshMovieAfterScrape(movie, result.strmUri)
                                ?: error("刮削已返回结果，但没有定位到整理后的 STRM")
                            movieRepository.markScrapeTaskCompleted(refreshed.id)
                        },
                        markPending = { movieRepository.markScrapeTaskPending(movie.id) },
                        onRunningStatusTimeout = {
                            scrapeRepository.appendLog("影片状态更新为刮削中超时，继续实际刮削：$label")
                        },
                        markFailed = { error ->
                            val reason = "刮削来源 ${source.name} 失败：${error.message ?: error::class.java.simpleName}"
                            movieRepository.markScrapeTaskFailed(movie.id, reason)
                            scrapeRepository.appendLog("影片批量刮削失败：$label，原因：$reason")
                        },
                        onScrapeTimeout = {
                            scrapeRepository.appendLog("单个影片刮削超过时限，已释放 worker：$label")
                        }
                    )
                    when (outcome) {
                        MovieScrapeTaskOutcome.Completed -> {
                            success.incrementAndGet()
                            scrapeRepository.appendLog("影片批量刮削完成：$label")
                        }
                        MovieScrapeTaskOutcome.Failed -> failed.incrementAndGet()
                    }
                    val completed = processed.incrementAndGet()
                    _state.update { state -> state.copy(
                        current = completed,
                        success = success.get(),
                        failed = failed.get(),
                        message = "影片批量刮削进度：$completed/${tasks.size}"
                    ) }
                }
                val message = "影片批量刮削完成：成功 ${success.get()} 个，失败 ${failed.get()} 个"
                scrapeRepository.appendLog(message)
                _state.value = MovieScrapeTaskRunnerState(
                    total = tasks.size,
                    current = tasks.size,
                    success = success.get(),
                    failed = failed.get(),
                    message = message
                )
            } catch (error: CancellationException) {
                scrapeRepository.appendLog("影片批量刮削已暂停：${error.message ?: "任务被取消"}")
                _state.value = _state.value.copy(
                    isRunning = false,
                    success = success.get(),
                    failed = failed.get(),
                    message = "已暂停影片批量刮削，点击开始可继续"
                )
            } catch (error: Throwable) {
                val message = "影片批量刮削执行器异常退出：${error.message ?: error::class.java.simpleName}"
                runCatching { scrapeRepository.appendLog(message) }
                _state.value = _state.value.copy(
                    isRunning = false,
                    success = success.get(),
                    failed = failed.get(),
                    message = message
                )
            } finally {
                if (_state.value.isRunning) {
                    _state.value = _state.value.copy(isRunning = false)
                }
            }
    }

    fun pause() {
        synchronized(lock) {
            restartRequested = false
            job?.cancel(CancellationException("已手动暂停影片批量刮削"))
        }
    }
}

internal fun MovieEntity.hasInvalidStoredMediaPath(): Boolean =
    scrapeFailureReason.orEmpty().contains("媒体路径失效")

internal fun <T> excludeOwnedScrapeTasks(
    tasks: List<T>,
    ownedMovieIds: Set<Long>,
    idOf: (T) -> Long
): List<T> = if (ownedMovieIds.isEmpty()) {
    tasks
} else {
    tasks.filterNot { idOf(it) in ownedMovieIds }
}

internal suspend fun processMovieScrapeTask(
    markRunning: suspend () -> Unit,
    runScrape: suspend () -> Unit,
    markPending: suspend () -> Unit,
    markFailed: suspend (Throwable) -> Unit,
    runningStatusTimeoutMillis: Long = RUNNING_STATUS_TIMEOUT_MS,
    onRunningStatusTimeout: suspend () -> Unit = {},
    scrapeTimeoutMillis: Long = MOVIE_SCRAPE_TIMEOUT_MS,
    onScrapeTimeout: suspend () -> Unit = {}
): MovieScrapeTaskOutcome {
    try {
        withTimeout(runningStatusTimeoutMillis) { markRunning() }
    } catch (_: TimeoutCancellationException) {
        onRunningStatusTimeout()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        markFailed(error)
        return MovieScrapeTaskOutcome.Failed
    }
    return try {
        withTimeout(scrapeTimeoutMillis) { runScrape() }
        MovieScrapeTaskOutcome.Completed
    } catch (_: TimeoutCancellationException) {
        val error = IllegalStateException("单个影片刮削超过 ${scrapeTimeoutMillis / 1_000} 秒")
        markFailed(error)
        onScrapeTimeout()
        MovieScrapeTaskOutcome.Failed
    } catch (error: CancellationException) {
        withContext(NonCancellable) { markPending() }
        throw error
    } catch (error: Throwable) {
        markFailed(error)
        MovieScrapeTaskOutcome.Failed
    }
}

internal enum class MovieScrapeTaskOutcome {
    Completed,
    Failed
}

private const val RUNNING_STATUS_TIMEOUT_MS = 5_000L
private const val MOVIE_SCRAPE_TIMEOUT_MS = 5L * 60L * 1_000L

internal suspend fun <T> runMovieBatchWorkers(
    tasks: List<T>,
    concurrency: Int,
    processor: suspend (index: Int, task: T) -> Unit
) {
    require(concurrency > 0) { "concurrency must be positive" }
    if (tasks.isEmpty()) return
    val nextIndex = AtomicInteger(0)
    val workerCount = minOf(concurrency, tasks.size)
    coroutineScope {
        repeat(workerCount) {
            launch {
                while (true) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= tasks.size) return@launch
                    processor(index, tasks[index])
                }
            }
        }
    }
}

data class MovieScrapeTaskRunnerState(
    val isRunning: Boolean = false,
    val total: Int = 0,
    val current: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val message: String = ""
)
