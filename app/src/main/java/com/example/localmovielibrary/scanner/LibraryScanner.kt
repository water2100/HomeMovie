package com.example.localmovielibrary.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.util.movieKeyFromText
import java.util.Locale

class LibraryScanner(private val context: Context) {
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "wmv", "m4v", "webm", "mpg", "mpeg", "strm")
    private val excludedDirectoryNames = setOf(
        "extrafanart",
        "behind the scenes"
    )

    suspend fun scan(rootUri: Uri): List<MovieEntity> {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val movies = mutableListOf<MovieEntity>()
        scanDirectory(rootUri.toString(), root, movies)
        return movies.deduplicateByMovieNumber()
    }

    suspend fun scanFile(rootUri: Uri, videoUri: Uri): MovieEntity? {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        val target = findFileWithSiblingsFast(root, rootUri, videoUri)
            ?: findFileWithSiblings(root, videoUri.toString())
            ?: return null
        return buildMovie(
            rootUri = rootUri.toString(),
            video = target.file,
            filesByLowerName = target.siblings
                .filter { it.isFile && it.name != null }
                .associateBy { it.name.orEmpty().lowercase(Locale.ROOT) }
        )
    }

    private fun scanDirectory(rootUri: String, directory: DocumentFile, movies: MutableList<MovieEntity>) {
        val children = directory.listFiles().toList()
        val filesByLowerName = children
            .filter { it.isFile && it.name != null }
            .associateBy { it.name.orEmpty().lowercase(Locale.ROOT) }

        children.filter { it.isFile && it.isVideoFile() }.forEach { video ->
            movies += buildMovie(rootUri, video, filesByLowerName)
        }

        children.filter { it.isDirectory && !it.isExcludedMediaAssetDirectory() }.forEach { childDirectory ->
            scanDirectory(rootUri, childDirectory, movies)
        }
    }

    private fun buildMovie(
        rootUri: String,
        video: DocumentFile,
        filesByLowerName: Map<String, DocumentFile>
    ): MovieEntity {
        val videoName = video.name.orEmpty()
        val baseName = videoName.substringBeforeLast('.', videoName)
        val nfoFile = findFirstExisting(
            filesByLowerName,
            listOf("$baseName.nfo", "movie.nfo", "tvshow.nfo")
        ) ?: filesByLowerName.values.firstOrNull { it.name.orEmpty().endsWith(".nfo", ignoreCase = true) }

        val metadata = nfoFile?.let { NfoParser(context.contentResolver).parse(it.uri) } ?: NfoMetadata()
        val posterFile = findImage(filesByLowerName, baseName, ImageKind.Poster)
        val fanartFile = findImage(filesByLowerName, baseName, ImageKind.Fanart)
        val thumbFile = findImage(filesByLowerName, baseName, ImageKind.Thumb)
        val fallbackTitle = baseName.replace('.', ' ').replace('_', ' ').trim()
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: fallbackTitle

        return MovieEntity(
            libraryRootUri = rootUri,
            videoUri = video.uri.toString(),
            videoName = videoName,
            sortTitle = title.removePrefix("The ").removePrefix("A "),
            title = title,
            originalTitle = metadata.originalTitle,
            plot = metadata.plot,
            outline = metadata.outline,
            year = metadata.year,
            premiered = metadata.premiered,
            runtimeMinutes = metadata.runtimeMinutes,
            mpaa = metadata.mpaa,
            studios = metadata.studios,
            series = metadata.series,
            directors = metadata.directors,
            actors = metadata.actors,
            genres = metadata.genres,
            tags = metadata.tags,
            rating = metadata.rating,
            uniqueIds = metadata.uniqueIds,
            posterUri = posterFile?.uri?.toString(),
            fanartUri = fanartFile?.uri?.toString(),
            thumbUri = thumbFile?.uri?.toString(),
            nfoUri = nfoFile?.uri?.toString(),
            scannedAtMillis = System.currentTimeMillis()
        )
    }

    private fun DocumentFile.isVideoFile(): Boolean {
        val extension = name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return isFile && extension in videoExtensions
    }

    private fun DocumentFile.isExcludedMediaAssetDirectory(): Boolean {
        val normalizedName = name.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
        return normalizedName in excludedDirectoryNames
    }

    private fun findImage(
        filesByLowerName: Map<String, DocumentFile>,
        baseName: String,
        kind: ImageKind
    ): DocumentFile? {
        val suffixes = when (kind) {
            ImageKind.Poster -> listOf("$baseName-poster", "poster", "movie-poster")
            ImageKind.Fanart -> listOf("$baseName-fanart", "fanart", "movie-fanart")
            ImageKind.Thumb -> listOf("$baseName-thumb", "thumb", "$baseName-fanart", "fanart", "movie-fanart")
        }
        val extensions = listOf("jpg", "jpeg", "png", "webp")
        val candidates = suffixes.flatMap { suffix -> extensions.map { ext -> "$suffix.$ext" } }
        return findFirstExisting(filesByLowerName, candidates)
            ?: findFirstImageBySuffix(filesByLowerName, kind, extensions)
    }

    private fun findFirstImageBySuffix(
        filesByLowerName: Map<String, DocumentFile>,
        kind: ImageKind,
        extensions: List<String>
    ): DocumentFile? {
        val suffixes = when (kind) {
            ImageKind.Poster -> listOf("-poster", "poster")
            ImageKind.Fanart -> listOf("-fanart", "fanart")
            ImageKind.Thumb -> listOf("-thumb", "thumb", "-fanart", "fanart")
        }
        return filesByLowerName.entries
            .sortedBy { it.key.length }
            .firstOrNull { (name, file) ->
                file.isFile &&
                    extensions.any { ext -> name.endsWith(".$ext") } &&
                    suffixes.any { suffix -> name.substringBeforeLast('.').endsWith(suffix) }
            }
            ?.value
    }

    private fun findFirstExisting(
        filesByLowerName: Map<String, DocumentFile>,
        candidates: List<String>
    ): DocumentFile? = candidates.firstNotNullOfOrNull { candidate ->
        filesByLowerName[candidate.lowercase(Locale.ROOT)]
    }

    private fun findFileWithSiblings(directory: DocumentFile, videoUri: String): FileWithSiblings? {
        val children = directory.listFiles().toList()
        children.forEach { child ->
            if (child.isFile && child.uri.toString() == videoUri) {
                return FileWithSiblings(child, children)
            }
            if (child.isDirectory && !child.isExcludedMediaAssetDirectory()) {
                findFileWithSiblings(child, videoUri)?.let { return it }
            }
        }
        return null
    }

    private fun findFileWithSiblingsFast(root: DocumentFile, rootUri: Uri, videoUri: Uri): FileWithSiblings? {
        val rootDocId = rootUri.treeDocumentId() ?: return null
        val videoDocId = videoUri.documentId() ?: return null
        if (!videoDocId.startsWith(rootDocId)) return null
        val relativePath = videoDocId
            .removePrefix(rootDocId)
            .removePrefix("/")
            .takeIf { it.isNotBlank() }
            ?: return null
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        val fileName = segments.last()
        val parent = segments.dropLast(1).fold(root as DocumentFile?) { directory, segment ->
            directory?.findFile(segment)?.takeIf { it.isDirectory }
        } ?: return null
        val siblings = parent.listFiles().toList()
        val file = siblings.firstOrNull { it.isFile && it.name == fileName && it.uri.toString() == videoUri.toString() }
            ?: siblings.firstOrNull { it.isFile && it.uri.toString() == videoUri.toString() }
            ?: return null
        return FileWithSiblings(file, siblings)
    }

    private fun List<MovieEntity>.deduplicateByMovieNumber(): List<MovieEntity> {
        return groupBy { movie -> movie.movieNumberKey() ?: "uri:${movie.videoUri}" }
            .values
            .map { duplicates ->
                duplicates.maxWith(
                    compareBy<MovieEntity> { if (it.nfoUri != null) 1 else 0 }
                        .thenBy { if (it.posterUri != null) 1 else 0 }
                        .thenBy { if (it.fanartUri != null) 1 else 0 }
                        .thenBy { if (it.thumbUri != null) 1 else 0 }
                        .thenBy { it.videoUri.length }
                )
            }
    }

    private fun MovieEntity.movieNumberKey(): String? {
        val source = listOf(videoName, title, originalTitle.orEmpty(), uniqueIds.joinToString(" "))
            .joinToString(" ")
        return movieKeyFromText(source)
    }
}

private fun Uri.treeDocumentId(): String? {
    val index = pathSegments.indexOf("tree")
    return index.takeIf { it >= 0 && it + 1 < pathSegments.size }
        ?.let { Uri.decode(pathSegments[it + 1]) }
}

private fun Uri.documentId(): String? {
    val index = pathSegments.indexOf("document")
    return index.takeIf { it >= 0 && it + 1 < pathSegments.size }
        ?.let { Uri.decode(pathSegments[it + 1]) }
}

private data class FileWithSiblings(
    val file: DocumentFile,
    val siblings: List<DocumentFile>
)

private enum class ImageKind {
    Poster,
    Fanart,
    Thumb
}
