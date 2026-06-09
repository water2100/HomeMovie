package com.example.localmovielibrary.util

object NumberRecognitionRules {
    val DEFAULT_IGNORED_SUFFIXES = setOf("HHB")

    @Volatile
    private var ignoredSuffixes: Set<String> = DEFAULT_IGNORED_SUFFIXES

    fun ignoredSuffixes(): Set<String> = ignoredSuffixes

    fun updateIgnoredSuffixes(value: Set<String>) {
        ignoredSuffixes = normalizeIgnoredSuffixes(DEFAULT_IGNORED_SUFFIXES + value)
    }

    fun normalizeIgnoredSuffixes(value: Iterable<String>): Set<String> =
        value.mapNotNull { it.normalizedIgnoredSuffixOrNull() }.toSet()

    fun stripIgnoredSuffix(baseName: String, suffixes: Set<String> = ignoredSuffixes()): IgnoredSuffixStripResult {
        val normalizedSuffixes = suffixes
            .mapNotNull { it.normalizedIgnoredSuffixOrNull() }
            .sortedByDescending { it.length }
        val match = normalizedSuffixes.firstOrNull { suffix ->
            baseName.endsWith(suffix, ignoreCase = true) &&
                baseName.length > suffix.length &&
                baseName.dropLast(suffix.length).any { it.isDigit() }
        }
        return if (match == null) {
            IgnoredSuffixStripResult(baseName = baseName, stripped = false)
        } else {
            IgnoredSuffixStripResult(
                baseName = baseName.dropLast(match.length),
                stripped = true
            )
        }
    }

    fun normalizeDigits(digits: String, hasExplicitSeparator: Boolean, strippedIgnoredSuffix: Boolean): String {
        if (hasExplicitSeparator || !strippedIgnoredSuffix || digits.length <= 3 || !digits.startsWith("0")) {
            return digits
        }
        return digits.trimStart('0').ifBlank { "0" }.padStart(3, '0')
    }

    fun String.normalizedIgnoredSuffixOrNull(): String? =
        trim()
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() && it.any { char -> char.isLetter() } }
}

data class IgnoredSuffixStripResult(
    val baseName: String,
    val stripped: Boolean
)
