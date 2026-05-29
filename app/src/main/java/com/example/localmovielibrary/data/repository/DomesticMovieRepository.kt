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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class DomesticMovieRepository(
    private val context: Context,
    private val dao: DomesticMovieDao,
    private val sourceDao: DomesticVideoSourceDao,
    private val cloud115Client: Cloud115Client
) {
    fun observeAll(): Flow<List<DomesticMovieEntity>> = dao.observeAll()

    fun observeAllWithSources(): Flow<List<DomesticMovieWithSources>> =
        combine(dao.observeAll(), sourceDao.observeAll()) { movies, sources ->
            val sourcesByFolder = sources.groupBy { it.folderCid }
            movies.map { movie ->
                DomesticMovieWithSources(
                    movie = movie,
                    sources = sourcesByFolder[movie.folderCid].orEmpty()
                        .ifEmpty { movie.toFallbackSource() }
                )
            }
        }.onEach { movies ->
            movies.forEach { item ->
                ensureLocalImageIfNeeded(item.movie)
            }
        }

    suspend fun addedFolderCids(): Set<Long> = withContext(Dispatchers.IO) {
        dao.getAddedFolderCids().toSet()
    }

    suspend fun addFolder(folder: Cloud115FileItem): DomesticMovieEntity = withContext(Dispatchers.IO) {
        val folderCid = folder.cid ?: error("A目录项目不是文件夹")
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
        val images = cloud115Client.listFiles(A_DIRECTORY_CID)
            .filter { !it.isDirectory && it.isImageFile() && !it.pickcode.isNullOrBlank() }
        val key = folderName.matchKey()
        return images.firstOrNull { image -> image.name.substringBeforeLast('.', image.name).matchKey() == key }
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
        const val A_DIRECTORY_CID: Long = 3435367965829999146L
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "m4v", "ts", "iso", "flv", "webm")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

data class DomesticMovieWithSources(
    val movie: DomesticMovieEntity,
    val sources: List<DomesticVideoSourceEntity>
)
