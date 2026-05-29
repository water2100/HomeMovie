package com.example.localmovielibrary.scraper

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class ActorAvatarStore(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun avatarUri(actorName: String): String? {
        val file = avatarFile(actorName)
        return if (file.exists() && file.length() > 0) Uri.fromFile(file).toString() else null
    }

    fun hasAvatar(actorName: String): Boolean {
        val file = avatarFile(actorName)
        return file.exists() && file.length() > 0
    }

    fun saveAvatar(actorName: String, bytes: ByteArray) {
        if (actorName.isBlank() || bytes.isEmpty()) return
        val file = avatarFile(actorName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun avatarFile(actorName: String): File {
        val normalized = actorName.trim().lowercase(Locale.ROOT)
        return File(directory, "${normalized.sha256()}.jpg")
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DIRECTORY_NAME = "actor_avatars"
    }
}
