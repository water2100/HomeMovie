package com.example.localmovielibrary.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class StoredDocumentLocatorTest {
    @Test
    fun `nested file uses file access while its folders use directory access`() {
        val fileRequests = mutableListOf<String>()
        val directoryRequests = mutableListOf<Pair<String, String>>()

        val located = resolveStoredDocument(
            rootDocumentId = "primary:myMovies",
            fileDocumentId = "primary:myMovies/actor/movie/movie.strm",
            root = "root",
            fileDocument = { id ->
                fileRequests += id
                "file:$id"
            },
            childDirectory = { parent, name ->
                directoryRequests += parent to name
                "$parent/$name"
            },
            isFile = { it.startsWith("file:") },
            isDirectory = { it == "root" || it.startsWith("root/") }
        )

        assertNotNull(located)
        assertEquals(listOf("primary:myMovies/actor/movie/movie.strm"), fileRequests)
        assertEquals(
            listOf("root" to "actor", "root/actor" to "movie"),
            directoryRequests
        )
        assertEquals("root/actor/movie", located?.directory)
        assertEquals("root/actor", located?.parentDirectory)
    }

    @Test
    fun `new media location is persisted before metadata refresh can fail`() = runBlocking {
        val calls = mutableListOf<String>()

        val failed = runCatching {
            persistLocationBeforeMetadataRefresh(
                persistLocation = { calls += "persist" },
                refreshMetadata = {
                    calls += "refresh"
                    error("metadata refresh failed")
                }
            )
        }.isFailure

        assertTrue(failed)
        assertEquals(listOf("persist", "refresh"), calls)
    }
}
