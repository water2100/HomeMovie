package com.example.localmovielibrary.data.repository

import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.scraper.formatMediaPathLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredStrmTargetTest {
    @Test
    fun `missing stored strm fails after one direct lookup`() {
        var directLookups = 0

        val error = runCatching {
            requireStoredDocument<String>(
                movieId = 42L,
                fileName = "sone-866.strm",
                databaseUri = "content://library/sone-866.strm",
                operation = "批量刮削定位"
            ) {
                directLookups += 1
                null
            }
        }.exceptionOrNull()

        assertEquals(1, directLookups)
        assertTrue(error is StoredMediaPathException)
        assertTrue(error?.message.orEmpty().contains("数据库已存URI=content://library/sone-866.strm"))
        assertTrue(error?.message.orEmpty().contains("不是应用搜索得到的文件当前位置"))
        assertTrue(error?.message.orEmpty().contains("不会自动扫描媒体库"))
        assertTrue(error?.message.orEmpty().contains("设置页的本地影片库目录中手动扫描"))
    }

    @Test
    fun `known invalid database path is not retried automatically`() {
        val movie = MovieEntity(
            id = 42L,
            libraryRootUri = "content://library/root",
            videoUri = "content://library/missing.strm",
            videoName = "missing.strm",
            title = "missing",
            sortTitle = "missing",
            originalTitle = null,
            plot = null,
            outline = null,
            year = null,
            premiered = null,
            runtimeMinutes = null,
            mpaa = null,
            studios = emptyList(),
            series = null,
            directors = emptyList(),
            actors = emptyList(),
            genres = emptyList(),
            tags = emptyList(),
            rating = null,
            uniqueIds = emptyList(),
            posterUri = null,
            fanartUri = null,
            thumbUri = null,
            nfoUri = null,
            scannedAtMillis = 0L,
            scrapeFailureReason = "刮削来源 Priority 失败：媒体路径失效：文件不存在"
        )

        assertTrue(movie.hasInvalidStoredMediaPath())
    }

    @Test
    fun `media path log excludes database uri but retains operation status and resolution`() {
        val line = formatMediaPathLog(
            operation = "STRM 重命名并更新数据库",
            status = "成功",
            movieId = 42L,
            fileName = "SOME-001.strm",
            databaseUri = "content://library/SOME-001.strm",
            detail = "影片表和 STRM 索引表已同步"
        )

        assertTrue(line.contains("状态=成功"))
        assertTrue(line.contains("movieId=42"))
        assertTrue(!line.contains("content://library/SOME-001.strm"))
        assertTrue(line.contains("影片表和 STRM 索引表已同步"))
    }
}
