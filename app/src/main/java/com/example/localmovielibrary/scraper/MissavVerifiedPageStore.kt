package com.example.localmovielibrary.scraper

import java.util.concurrent.ConcurrentHashMap

object MissavVerifiedPageStore {
    private val pages = ConcurrentHashMap<String, String>()

    fun put(number: String, html: String) {
        pages[normalize(number)] = html
    }

    fun consume(number: String): String? {
        return pages.remove(normalize(number))
    }

    private fun normalize(number: String): String =
        number.trim().uppercase().replace("_", "-")
}
