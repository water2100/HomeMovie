package com.example.localmovielibrary.data.repository

import android.content.Context
import android.net.Uri
import com.example.localmovielibrary.cloud115.Cloud115Client
import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.data.local.DomesticMovieDao
import com.example.localmovielibrary.data.local.DomesticMovieEntity
import com.example.localmovielibrary.data.local.DomesticVideoSourceDao
import com.example.localmovielibrary.data.local.DomesticVideoSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class DomesticMovieRepository(
    private val context: Context,
    private val dao: DomesticMovieDao,
    private val sourceDao: DomesticVideoSourceDao,
    private val cloud115Client: Cloud115Client,
    private val settingsRepository: AppSettingsRepository
) {
    private val imageSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncingImageFolderCids = mutableSetOf<Long>()
    private val imageIndexMutex = Mutex()
    private var imageIndexCache: DomesticImageIndexCache? = null

    fun observeAllWithSources(): Flow<List<DomesticMovieWithSources>> =
        combine(dao.observeAll(), sourceDao.observeAll()) { movies, sources ->
            val sourcesByFolder = sources.groupBy { it.folderCid }
            val result = movies.map { movie ->
                DomesticMovieWithSources(
                    movie = movie,
                    sources = sourcesByFolder[movie.folderCid].orEmpty()
                        .ifEmpty { movie.toFallbackSource() }
                )
            }
            val missingImages = result
                .map { it.movie }
                .filter { movie -> !movie.imagePickcode.isNullOrBlank() && !movie.imageUrl.orEmpty().isUsableLocalImage() }
                .filter { movie -> claimImageSync(movie.folderCid) }
            if (missingImages.isNotEmpty()) {
                imageSyncScope.launch {
                    missingImages.forEach { movie ->
                        try {
                            ensureLocalImageIfNeeded(movie)
                        } finally {
                            releaseImageSync(movie.folderCid)
                        }
                    }
                }
            }
            result
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    suspend fun addedFolderCidsForVisibleItems(items: List<Cloud115FileItem>): Set<Long> = withContext(Dispatchers.IO) {
        val folderCids = items
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { it.cid }
            .toSet()
        if (folderCids.isEmpty()) emptySet() else dao.getAddedFolderCids(folderCids.toList()).toSet()
    }

    suspend fun addFolder(folder: Cloud115FileItem): DomesticMovieEntity = withContext(Dispatchers.IO) {
        val folderCid = folder.cid ?: error("A 目录项目不是文件夹")
        val existing = dao.getByFolderCid(folderCid)

        val children = cloud115Client.listFiles(folderCid)
        val videos = children
            .filter { !it.isDirectory && it.isVideoFile() && !it.pickcode.isNullOrBlank() }
            .sortedWith(compareBy<Cloud115FileItem> { it.name.naturalSortKey() }.thenBy { it.name.lowercase(Locale.ROOT) })
        val primaryVideo = videos.firstOrNull()
            ?: error("这个文件夹内没有可添加的视频")

        val now = System.currentTimeMillis()
        val entity = existing?.copy(
            videoName = primaryVideo.name,
            videoPickcode = primaryVideo.pickcode.orEmpty(),
            imageUrl = existing.imageUrl?.takeIf { it.isUsableLocalImage() } ?: existing.imagePickcode?.let { downloadImageToLocalFile(folderCid, it, existing.imageName) },
            updatedAt = now
        ) ?: run {
            val image = findMatchingImage(folder.name)
            val imageUrl = image?.pickcode?.let { downloadImageToLocalFile(folderCid, it, image.name) }
            DomesticMovieEntity(
                folderCid = folderCid,
                folderName = folder.name,
                videoName = primaryVideo.name,
                videoPickcode = primaryVideo.pickcode.orEmpty(),
                imageName = image?.name,
                imagePickcode = image?.pickcode,
                imageUrl = imageUrl,
                createdAt = now,
                updatedAt = now
            )
        }

        dao.upsert(entity)
        sourceDao.upsertAll(
            videos.mapIndexed { index, item ->
                DomesticVideoSourceEntity(
                    folderCid = folderCid,
                    videoName = item.name,
                    videoPickcode = item.pickcode.orEmpty(),
                    sortOrder = index,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            }
        )
        entity
    }

    private suspend fun findMatchingImage(folderName: String): Cloud115FileItem? {
        val key = folderName.matchKey()
        if (key.isBlank()) return null
        return loadDomesticImageIndex()[key]
    }

    private suspend fun loadDomesticImageIndex(): Map<String, Cloud115FileItem> {
        val cached = synchronized(this) {
            imageIndexCache?.takeIf { System.currentTimeMillis() - it.loadedAtMs <= IMAGE_INDEX_CACHE_TTL_MS }
        }
        if (cached != null) return cached.imagesByKey

        return imageIndexMutex.withLock {
            val cachedInsideLock = synchronized(this) {
                imageIndexCache?.takeIf { System.currentTimeMillis() - it.loadedAtMs <= IMAGE_INDEX_CACHE_TTL_MS }
            }
            if (cachedInsideLock != null) return@withLock cachedInsideLock.imagesByKey

            val domesticRootCid = settingsRepository.getDomesticRootCid()
                ?: error("请先在设置页配置 A目录 CID")
            val index = cloud115Client.listFiles(domesticRootCid)
                .asSequence()
                .filter { !it.isDirectory && it.isImageFile() && !it.pickcode.isNullOrBlank() }
                .mapNotNull { image ->
                    val key = image.name.substringBeforeLast('.', image.name).matchKey()
                    key.takeIf { it.isNotBlank() }?.let { it to image }
                }
                .distinctBy { it.first }
                .toMap()
            synchronized(this) {
                imageIndexCache = DomesticImageIndexCache(index, System.currentTimeMillis())
            }
            index
        }
    }

    private suspend fun ensureLocalImageIfNeeded(movie: DomesticMovieEntity) {
        val pickcode = movie.imagePickcode?.takeIf { it.isNotBlank() } ?: return
        val current = movie.imageUrl.orEmpty()
        if (current.isUsableLocalImage()) return
        runCatching {
            val localUri = downloadImageToLocalFile(movie.folderCid, pickcode, movie.imageName)
            dao.updateImageUrl(movie.folderCid, localUri, System.currentTimeMillis())
        }
    }

    private fun claimImageSync(folderCid: Long): Boolean =
        synchronized(syncingImageFolderCids) {
            syncingImageFolderCids.add(folderCid)
        }

    private fun releaseImageSync(folderCid: Long) {
        synchronized(syncingImageFolderCids) {
            syncingImageFolderCids.remove(folderCid)
        }
    }

    private suspend fun downloadImageToLocalFile(folderCid: Long, pickcode: String, imageName: String?): String {
        val extension = imageName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in IMAGE_EXTENSIONS }
            ?: "jpg"
        val directory = File(context.filesDir, "domestic_images").apply { mkdirs() }
        val target = File(directory, "$folderCid.$extension")
        if (target.exists() && target.length() > 0) {
            return Uri.fromFile(target).toString()
        }
        val directUrl = cloud115Client.fetchDirectUrl(pickcode)
        val bytes = cloud115Client.downloadBytes(directUrl)
        target.writeBytes(bytes)
        return Uri.fromFile(target).toString()
    }

    private fun String.isUsableLocalImage(): Boolean {
        if (!startsWith("file://")) return false
        val path = runCatching { Uri.parse(this).path }.getOrNull() ?: return false
        return File(path).let { it.exists() && it.length() > 0 }
    }

    private fun Cloud115FileItem.isVideoFile(): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in VIDEO_EXTENSIONS
    }

    private fun Cloud115FileItem.isImageFile(): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in IMAGE_EXTENSIONS
    }

    private fun String.matchKey(): String {
        val base = substringBeforeLast('.', this).trim().lowercase(Locale.ROOT)
        return if (base.length >= 10) base.take(10) else base
    }

    private fun String.naturalSortKey(): String =
        lowercase(Locale.ROOT).replace(Regex("\\d+")) { match ->
            match.value.padStart(10, '0')
        }

    private fun DomesticMovieEntity.toFallbackSource(): List<DomesticVideoSourceEntity> =
        listOf(
            DomesticVideoSourceEntity(
                folderCid = folderCid,
                videoName = videoName,
                videoPickcode = videoPickcode,
                sortOrder = 0,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        )

    companion object {
        private const val IMAGE_INDEX_CACHE_TTL_MS = 5 * 60_000L
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "m4v", "ts", "iso", "flv", "webm")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

data class DomesticMovieWithSources(
    val movie: DomesticMovieEntity,
    val sources: List<DomesticVideoSourceEntity>
)

private data class DomesticImageIndexCache(
    val imagesByKey: Map<String, Cloud115FileItem>,
    val loadedAtMs: Long
)
