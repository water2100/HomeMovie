package com.example.localmovielibrary.scraper

import com.example.localmovielibrary.util.NumberRecognitionRules
import java.util.Locale

object MovieNumberExtractor {
    private val numberPattern = Regex("""(?i)(?:^|[^a-z0-9])([a-z0-9]{2,12}?)([-_\s]?)(\d{2,6})([a-z]{0,2})(?=$|[^a-z0-9])""")

    fun extract(
        fileName: String,
        ignoredSuffixes: Set<String> = NumberRecognitionRules.ignoredSuffixes()
    ): String? {
        val rawBaseName = fileName.substringBeforeLast('.', fileName)
        val stripResult = NumberRecognitionRules.stripIgnoredSuffix(rawBaseName, ignoredSuffixes)
        val baseName = stripResult.baseName
        val match = numberPattern.findAll(baseName)
            .filter { match -> match.groupValues[1].any { it.isLetter() } }
            .map { match ->
                NumberMatch(
                    match = match,
                    hasExplicitSeparator = match.groupValues.getOrNull(2)?.isNotEmpty() == true,
                    strippedIgnoredSuffix = stripResult.stripped
                )
            }
            .maxWithOrNull(
                compareBy<NumberMatch> { it.hasExplicitSeparator }
                    .thenBy { it.match.range.first }
            )
            ?.match
            ?: return null
        val prefix = match.groupValues[1].uppercase(Locale.ROOT)
        val separator = match.groupValues.getOrNull(2).orEmpty()
        val number = NumberRecognitionRules.normalizeDigits(
            digits = match.groupValues[3],
            hasExplicitSeparator = separator.isNotEmpty(),
            strippedIgnoredSuffix = stripResult.stripped
        )
        val suffix = match.groupValues[4].uppercase(Locale.ROOT)
        return "$prefix-$number$suffix"
    }

    private data class NumberMatch(
        val match: MatchResult,
        val hasExplicitSeparator: Boolean,
        val strippedIgnoredSuffix: Boolean
    )
}
