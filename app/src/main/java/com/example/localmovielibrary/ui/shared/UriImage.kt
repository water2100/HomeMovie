package com.example.localmovielibrary.ui.shared

import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

@Composable
fun UriImage(
    uri: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    maxDecodeSize: Int = 1200,
    cacheKey: Any? = null
) {
    if (uri.isNullOrBlank()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        return
    }

    val context = LocalContext.current
    val image = produceState<ImageBitmap?>(initialValue = null, uri, maxDecodeSize, cacheKey) {
        repeat(VISIBLE_IMAGE_RETRY_COUNT) { attempt ->
            value = withContext(Dispatchers.IO) {
                loadUriImageWithRetry(context, uri, maxDecodeSize, cacheKey?.toString())
            }
            if (value != null) return@produceState
            delay(VISIBLE_IMAGE_RETRY_DELAYS_MS.getOrElse(attempt) { VISIBLE_IMAGE_RETRY_DELAYS_MS.last() })
        }
    }

    if (image.value != null) {
        if (contentScale == ContentScale.Crop && alignment == Alignment.Center) {
            CenterCropImage(bitmap = image.value!!, modifier = modifier)
        } else {
            Image(
                bitmap = image.value!!,
                contentDescription = null,
                modifier = modifier,
                contentScale = contentScale,
                alignment = alignment
            )
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
private fun CenterCropImage(bitmap: ImageBitmap, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        if (imageWidth <= 0 || imageHeight <= 0 || size.width <= 0f || size.height <= 0f) return@Canvas

        val canvasAspect = size.width / size.height
        val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
        val srcWidth: Int
        val srcHeight: Int
        val srcX: Int
        val srcY: Int
        if (imageAspect > canvasAspect) {
            srcHeight = imageHeight
            srcWidth = (imageHeight * canvasAspect).roundToInt().coerceIn(1, imageWidth)
            srcX = ((imageWidth - srcWidth) / 2f).roundToInt()
            srcY = 0
        } else {
            srcWidth = imageWidth
            srcHeight = (imageWidth / canvasAspect).roundToInt().coerceIn(1, imageHeight)
            srcX = 0
            srcY = ((imageHeight - srcHeight) / 2f).roundToInt()
        }

        drawImage(
            image = bitmap,
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(srcWidth, srcHeight),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        )
    }
}

private suspend fun loadUriImageWithRetry(
    context: Context,
    uriString: String,
    maxDecodeSize: Int,
    cacheKey: String?
): ImageBitmap? {
    val resolvedCacheKey = imageCacheKey(context, uriString, maxDecodeSize, cacheKey)
    ImageMemoryCache.get(resolvedCacheKey)?.let { return it }
    if (ImageFailureCache.isRecentlyFailed(resolvedCacheKey)) return null
    repeat(DECODE_RETRY_COUNT) { attempt ->
        val decoded = ImageDecodeLimiter.withPermit {
            if (ImageFailureCache.isRecentlyFailed(resolvedCacheKey)) return null
            ImageMemoryCache.get(resolvedCacheKey)
                ?: runCatching { loadDiskCachedImage(context, resolvedCacheKey) }.getOrNull()
                ?: runCatching {
                    decodeUriImage(context.contentResolver, Uri.parse(uriString), maxDecodeSize).also { bitmap ->
                        writeDiskCachedImage(context, resolvedCacheKey, bitmap)
                    }.asImageBitmap()
                }.getOrNull()
        }
        if (decoded != null) {
            ImageMemoryCache.put(resolvedCacheKey, decoded)
            return decoded
        }
        delay(DECODE_RETRY_DELAYS_MS.getOrElse(attempt) { DECODE_RETRY_DELAYS_MS.last() })
    }
    ImageFailureCache.markFailed(resolvedCacheKey)
    return null
}

private fun imageCacheKey(
    context: Context,
    uriString: String,
    maxDecodeSize: Int,
    cacheKey: String?
): String {
    val sourceVersion = uriSourceVersion(context, uriString)
    return listOf(uriString, maxDecodeSize.toString(), cacheKey.orEmpty(), sourceVersion).joinToString("#")
}

private fun uriSourceVersion(context: Context, uriString: String): String {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return ""
    return when (uri.scheme?.lowercase()) {
        "file" -> uri.path
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?.let { file -> "file:${file.length()}:${file.lastModified()}" }
            .orEmpty()
        "content" -> queryContentVersion(context, uri)
        else -> ""
    }
}

private fun queryContentVersion(context: Context, uri: Uri): String {
    val projections = listOf(
        arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
        null
    )
    projections.forEach { projection ->
        val cursor = runCatching {
            context.contentResolver.query(uri, projection, null, null, null)
        }.getOrNull() ?: return@forEach
        cursor.use {
            if (it.moveToFirst()) {
                val size = it.longOrNull(OpenableColumns.SIZE) ?: -1L
                val modified = it.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED) ?: -1L
                return "content:$size:$modified"
            }
        }
    }
    return ""
}

private fun Cursor.longOrNull(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) return null
    return runCatching { getLong(index) }.getOrNull()
}

private fun decodeUriImage(
    contentResolver: android.content.ContentResolver,
    uri: Uri,
    maxDecodeSize: Int
): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDecodeSize)
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    val bitmap = contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: error("Unable to decode image: $uri")
    return bitmap
}

private fun loadDiskCachedImage(
    context: Context,
    cacheKey: String
): ImageBitmap? {
    val file = diskCacheFile(context, cacheKey)
    if (!file.exists() || file.length() <= 0L) return null
    file.setLastModified(System.currentTimeMillis())
    return BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
}

private fun writeDiskCachedImage(
    context: Context,
    cacheKey: String,
    bitmap: Bitmap
) {
    val file = diskCacheFile(context, cacheKey)
    if (file.exists() && file.length() > 0L) return
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.outputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, output)
    }
    if (!temp.renameTo(file)) {
        temp.delete()
    }
    val cacheDirectory = file.parentFile ?: return
    if (DiskCacheTrimGate.shouldTrim()) {
        trimDiskCache(cacheDirectory)
    }
}

private fun diskCacheFile(context: Context, cacheKey: String): File {
    val key = cacheKey.sha256()
    return File(File(context.cacheDir, DISK_CACHE_DIR), "$key.jpg")
}

private fun String.sha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun trimDiskCache(directory: File) {
    val files = directory.listFiles()?.filter { it.isFile } ?: return
    var total = files.sumOf { it.length() }
    if (total <= MAX_DISK_CACHE_BYTES) return
    files.sortedBy { it.lastModified() }.forEach { file ->
        if (total <= TARGET_DISK_CACHE_BYTES) return
        val size = file.length()
        if (file.delete()) total -= size
    }
}

private fun calculateInSampleSize(width: Int, height: Int, maxDecodeSize: Int): Int {
    if (width <= 0 || height <= 0 || maxDecodeSize <= 0) return 1
    var sample = 1
    var halfWidth = width / 2
    var halfHeight = height / 2
    while (halfWidth / sample >= maxDecodeSize || halfHeight / sample >= maxDecodeSize) {
        sample *= 2
    }
    return sample.coerceAtLeast(1)
}

private object ImageMemoryCache {
    private val cache = object : LruCache<String, ImageBitmap>(MAX_MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height.toLong() * IMAGE_BITMAP_BYTES_PER_PIXEL) / 1024L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }

    fun get(cacheKey: String): ImageBitmap? = cache.get(cacheKey)

    fun put(cacheKey: String, image: ImageBitmap) {
        cache.put(cacheKey, image)
    }

    fun clear() {
        cache.evictAll()
    }
}

private object ImageFailureCache {
    private val failedAtMs = linkedMapOf<String, Long>()

    @Synchronized
    fun isRecentlyFailed(cacheKey: String): Boolean {
        val failedAt = failedAtMs[cacheKey] ?: return false
        val now = System.currentTimeMillis()
        if (now - failedAt <= FAILURE_CACHE_TTL_MS) return true
        failedAtMs.remove(cacheKey)
        return false
    }

    @Synchronized
    fun markFailed(cacheKey: String) {
        failedAtMs[cacheKey] = System.currentTimeMillis()
        trim()
    }

    @Synchronized
    fun clear() {
        failedAtMs.clear()
    }

    private fun trim() {
        while (failedAtMs.size > MAX_FAILURE_CACHE_ENTRIES) {
            val oldestKey = failedAtMs.keys.firstOrNull() ?: return
            failedAtMs.remove(oldestKey)
        }
    }
}

object MovieImageCacheStore {
    fun diskCacheSizeBytes(context: Context): Long =
        diskCacheDirectory(context)
            .listFiles()
            ?.sumOf { file -> file.sizeBytes() }
            ?: 0L

    fun clear(context: Context): Long {
        val directory = diskCacheDirectory(context)
        val sizeBeforeClear = diskCacheSizeBytes(context)
        directory.listFiles()?.forEach { file -> file.deleteRecursively() }
        ImageMemoryCache.clear()
        ImageFailureCache.clear()
        return sizeBeforeClear
    }

    private fun diskCacheDirectory(context: Context): File =
        File(context.cacheDir, DISK_CACHE_DIR)

    private fun File.sizeBytes(): Long =
        if (isDirectory) {
            listFiles()?.sumOf { it.sizeBytes() } ?: 0L
        } else {
            length()
        }
}

private object DiskCacheTrimGate {
    private var writeCount = 0
    private var lastTrimAtMs = 0L

    @Synchronized
    fun shouldTrim(): Boolean {
        writeCount += 1
        val now = System.currentTimeMillis()
        val enoughWrites = writeCount >= DISK_CACHE_TRIM_WRITE_INTERVAL
        val enoughTime = now - lastTrimAtMs >= DISK_CACHE_TRIM_TIME_INTERVAL_MS
        if (!enoughWrites && !enoughTime) return false
        writeCount = 0
        lastTrimAtMs = now
        return true
    }
}

private val ImageDecodeLimiter = Semaphore(permits = 3)
private const val DECODE_RETRY_COUNT = 5
private val DECODE_RETRY_DELAYS_MS = listOf(120L, 260L, 520L, 900L, 1400L)
private const val DISK_CACHE_DIR = "movie_image_cache"
private const val DISK_CACHE_JPEG_QUALITY = 92
private const val MAX_DISK_CACHE_BYTES = 320L * 1024L * 1024L
private const val TARGET_DISK_CACHE_BYTES = 260L * 1024L * 1024L
private const val DISK_CACHE_TRIM_WRITE_INTERVAL = 24
private const val DISK_CACHE_TRIM_TIME_INTERVAL_MS = 60_000L
private const val MAX_MEMORY_CACHE_KB = 48 * 1024
private const val IMAGE_BITMAP_BYTES_PER_PIXEL = 4
private const val FAILURE_CACHE_TTL_MS = 8_000L
private const val MAX_FAILURE_CACHE_ENTRIES = 512
private const val VISIBLE_IMAGE_RETRY_COUNT = 4
private val VISIBLE_IMAGE_RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 6_000L)
