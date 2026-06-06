package com.example.localmovielibrary.util

import java.util.Locale

fun String.metadataKey(): String =
    trim()
        .replace(Regex("""\s+"""), " ")
        .lowercase(Locale.ROOT)

fun List<String>.containsMetadataValue(value: String, exact: Boolean): Boolean {
    val query = value.metadataKey()
    if (query.isBlank()) return false
    return any { item ->
        val candidate = item.metadataKey()
        candidate.isNotBlank() && if (exact) candidate == query else candidate.contains(query)
    }
}

fun List<String>.normalizedMetadataValues(): List<String> =
    map { it.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.isNotBlank() }
        .distinctBy { it.metadataKey() }
