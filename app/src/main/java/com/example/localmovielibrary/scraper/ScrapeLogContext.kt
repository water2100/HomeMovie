package com.example.localmovielibrary.scraper

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Carries the current movie number through concurrent scrape coroutines. */
object ScrapeLogContext {
    private val numberThreadLocal = ThreadLocal<String?>()

    suspend fun <T> withMovieNumber(number: String, block: suspend () -> T): T =
        withContext(numberThreadLocal.asContextElement(number)) { block() }

    fun tag(message: String): String =
        numberThreadLocal.get()?.let { number -> "【番号=$number】$message" } ?: message
}
