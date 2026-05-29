package com.example.localmovielibrary.ui.shared

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
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
    maxDecodeSize: Int = 1200
) {
    val context = LocalContext.current
    val image = produceState<ImageBitmap?>(initialValue = null, uri, maxDecodeSize) {
        value = withContext(Dispatchers.IO) {
            uri?.let { loadUriImageWithRetry(context, it, maxDecodeSize) }
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
    maxDecodeSize: Int
): ImageBitmap? {
    ImageMemoryCache.get(uriString, maxDecodeSize)?.let { return it }
    repeat(DECODE_RETRY_COUNT) { attempt ->
        val decoded = ImageDecodeLimiter.withPermit {
            ImageMemoryCache.get(uriString, maxDecodeSize)
                ?: runCatching { loadDiskCachedImage(context, uriString, maxDecodeSize) }.getOrNull()
                ?: runCatching {
                    decodeUriImage(context.contentResolver, Uri.parse(uriString), maxDecodeSize).also { bitmap ->
                        writeDiskCachedImage(context, uriString, maxDecodeSize, bitmap)
                    }.asImageBitmap()
                }.getOrNull()
        }
        if (decoded != null) {
            ImageMemoryCache.put(uriString, maxDecodeSize, decoded)
            return decoded
        }
        delay(DECODE_RETRY_DELAYS_MS.getOrElse(attempt) { DECODE_RETRY_DELAYS_MS.last() })
    }
    return null
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
    uriString: String,
    maxDecodeSize: Int
): ImageBitmap? {
    val file = diskCacheFile(context, uriString, maxDecodeSize)
    if (!file.exists() || file.length() <= 0L) return null
    file.setLastModified(System.currentTimeMillis())
    return BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
}

private fun writeDiskCachedImage(
    context: Context,
    uriString: String,
    maxDecodeSize: Int,
    bitmap: Bitmap
) {
    val file = diskCacheFile(context, uriString, maxDecodeSize)
    if (file.exists() && file.length() > 0L) return
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.outputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_JPEG_QUALITY, output)
    }
    if (!temp.renameTo(file)) {
        temp.delete()
    }
    trimDiskCache(file.parentFile ?: return)
}

private fun diskCacheFile(context: Context, uriString: String, maxDecodeSize: Int): File {
    val key = "$uriString#$maxDecodeSize".sha256()
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
    private val cache = object : LruCache<String, ImageBitmap>(128) {}

    fun get(uri: String, maxDecodeSize: Int): ImageBitmap? = cache.get(key(uri, maxDecodeSize))

    fun put(uri: String, maxDecodeSize: Int, image: ImageBitmap) {
        cache.put(key(uri, maxDecodeSize), image)
    }

    private fun key(uri: String, maxDecodeSize: Int): String = "$uri#$maxDecodeSize"
}

private val ImageDecodeLimiter = Semaphore(permits = 3)
private const val DECODE_RETRY_COUNT = 5
private val DECODE_RETRY_DELAYS_MS = listOf(120L, 260L, 520L, 900L, 1400L)
private const val DISK_CACHE_DIR = "movie_image_cache"
private const val DISK_CACHE_JPEG_QUALITY = 92
private const val MAX_DISK_CACHE_BYTES = 320L * 1024L * 1024L
private const val TARGET_DISK_CACHE_BYTES = 260L * 1024L * 1024L
