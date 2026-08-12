package com.example.localmovielibrary.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** Resolves only the exact URI persisted in the database. It never searches a directory tree. */
class StoredDocumentLocator(private val context: Context) {
    fun find(rootUriString: String, fileUriString: String): LocatedDocument? {
        val rootUri = Uri.parse(rootUriString)
        val fileUri = Uri.parse(fileUriString)
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        val rootDocumentId = rootUri.treeDocumentId() ?: return null
        val fileDocumentId = fileUri.documentId() ?: return null
        val resolved = resolveStoredDocument(
            rootDocumentId = rootDocumentId,
            fileDocumentId = fileDocumentId,
            root = root,
            fileDocument = { documentId -> singleDocument(rootUri, documentId) },
            childDirectory = { parent, name ->
                parent.findFile(name)?.takeIf { it.exists() && it.isDirectory }
            },
            isFile = { document -> document.exists() && document.isFile },
            isDirectory = { document -> document.exists() && document.isDirectory }
        ) ?: return null
        return LocatedDocument(
            file = resolved.file,
            directory = resolved.directory,
            parentDirectory = resolved.parentDirectory
        )
    }

    private fun singleDocument(treeUri: Uri, documentId: String): DocumentFile? = runCatching {
        DocumentFile.fromSingleUri(
            context,
            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        )
    }.getOrNull()
}

internal data class ResolvedStoredDocument<T>(
    val file: T,
    val directory: T,
    val parentDirectory: T?
)

internal fun <T> resolveStoredDocument(
    rootDocumentId: String,
    fileDocumentId: String,
    root: T,
    fileDocument: (String) -> T?,
    childDirectory: (parent: T, name: String) -> T?,
    isFile: (T) -> Boolean,
    isDirectory: (T) -> Boolean
): ResolvedStoredDocument<T>? {
    if (fileDocumentId == rootDocumentId) return null
    if (!fileDocumentId.startsWith("$rootDocumentId/")) return null

    val file = fileDocument(fileDocumentId)?.takeIf(isFile) ?: return null
    val relativeSegments = fileDocumentId
        .removePrefix("$rootDocumentId/")
        .split('/')
        .filter(String::isNotBlank)
    if (relativeSegments.isEmpty()) return null

    var directory = root
    var parentDirectory: T? = null
    relativeSegments.dropLast(1).forEach { directoryName ->
        parentDirectory = directory
        directory = childDirectory(directory, directoryName)?.takeIf(isDirectory) ?: return null
    }
    return ResolvedStoredDocument(file, directory, parentDirectory)
}

data class LocatedDocument(
    val file: DocumentFile,
    val directory: DocumentFile,
    val parentDirectory: DocumentFile?
)

class StoredMediaPathException(
    val movieId: Long?,
    val fileName: String,
    val databaseUri: String,
    val operation: String
) : IllegalStateException(
    buildString {
        append("媒体路径失效：操作=").append(operation)
        movieId?.let { append("，movieId=").append(it) }
        append("，文件=").append(fileName)
        append("，状态=该数据库记录当前不可访问")
        append("；可能原因=文件已被移动、删除，或目录授权失效")
        append("；说明=此 URI 来自影片表，不是应用搜索得到的文件当前位置")
        append("；处理=当前操作已停止，不会自动扫描媒体库")
        append("；建议=请在设置页的本地影片库目录中手动扫描")
    }
)

internal fun <T> requireStoredDocument(
    movieId: Long?,
    fileName: String,
    databaseUri: String,
    operation: String,
    directLookup: () -> T?
): T = directLookup() ?: throw StoredMediaPathException(
    movieId = movieId,
    fileName = fileName,
    databaseUri = databaseUri,
    operation = operation
)

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
