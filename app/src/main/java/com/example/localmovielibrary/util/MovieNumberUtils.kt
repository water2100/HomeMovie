package com.example.localmovielibrary.util

import java.util.Locale

data class MovieNumberInfo(
    val number: String,
    val partLabel: String? = null
)

data class MovieSourceIdentity(
    val number: String,
    val partLabel: String?,
    val variant: MovieVariant
) {
    val movieKey: String = number
    val versionKey: String = number + variant.suffix
    val sourceKey: String = buildString {
        append(number)
        partLabel?.takeIf { it.isNotBlank() }?.let { append("-").append(it) }
        append(variant.suffix)
    }
}

fun extractMovieNumberInfo(text: String): MovieNumberInfo? {
    val rawBaseName = text.substringBeforeLast('.', text)
    val stripResult = NumberRecognitionRules.stripIgnoredSuffix(rawBaseName)
    val baseName = stripResult.baseName
    val match = MOVIE_NUMBER_PATTERN
        .findAll(baseName)
        .filter { match -> match.groupValues[1].any { it.isLetter() } }
        .map { match ->
            MovieNumberMatch(
                match = match,
                hasExplicitSeparator = match.hasExplicitSeparator()
            )
        }
        .maxWithOrNull(
            compareBy<MovieNumberMatch> { it.hasExplicitSeparator }
                .thenBy { it.match.range.first }
        )
        ?.match
        ?: return null
    val hasExplicitSeparator = match.hasExplicitSeparator()
    val digits = NumberRecognitionRules.normalizeDigits(
        digits = match.groupValues[2],
        hasExplicitSeparator = hasExplicitSeparator,
        strippedIgnoredSuffix = stripResult.stripped
    )
    val attachedSuffix = match.groupValues.getOrNull(3)
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.ROOT)
        .orEmpty()
    val number = "${match.groupValues[1].uppercase(Locale.ROOT)}-$digits$attachedSuffix"
    val letterPart = match.groupValues.getOrNull(4)
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.ROOT)
    val suffix = baseName.substring(match.range.last + 1)
    val numberedPart = Regex("""(?i)(?:^|[._ -])part\s*0*([0-9]{1,2})(?=$|[^a-z0-9])""")
        .find(suffix)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?.let { "P${it.toInt()}" }
    val normalizedNumberedPart = Regex("""(?i)(?:^|[._ -])p\s*0*([0-9]{1,2})(?=$|[^a-z0-9])""")
        .find(suffix)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?.let { "P${it.toInt()}" }
    val directNumberedPart = Regex("""(?i)(?:^|[._ -])0*([1-9][0-9]?)(?=$|[._ -])""")
        .find(suffix)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?.let { "P${it.toInt()}" }
    val part = numberedPart ?: normalizedNumberedPart ?: directNumberedPart ?: letterPart
    return MovieNumberInfo(number = number, partLabel = part)
}

fun normalizeMovieNumber(text: String): String? = extractMovieNumberInfo(text)?.number

fun extractMovieSourceIdentity(text: String): MovieSourceIdentity? {
    val info = extractMovieNumberInfo(text) ?: return null
    return MovieSourceIdentity(
        number = info.number,
        partLabel = info.partLabel,
        variant = detectMovieVariant(text)
    )
}

fun movieKeyFromText(text: String): String? = extractMovieSourceIdentity(text)?.movieKey

fun movieVersionKeyFromText(text: String): String? = extractMovieSourceIdentity(text)?.versionKey

fun movieSourceKeyFromText(text: String): String? = extractMovieSourceIdentity(text)?.sourceKey

fun partSortKey(label: String?): Int {
    val value = label?.uppercase(Locale.ROOT)
    return when {
        value != null && value.length == 1 && value.first() in 'A'..'Z' -> value.first() - 'A'
        value != null && value.matches(Regex("""P\d{1,2}""")) -> value.drop(1).toIntOrNull() ?: Int.MAX_VALUE
        else -> Int.MAX_VALUE
    }
}

private val MOVIE_NUMBER_PATTERN =
    Regex("""(?i)\b([a-z0-9]{2,12}?)[-_ ]?(\d{2,6})([a-z]{0,2})(?:[-_ ]([a-z]))?(?:$|[^a-z0-9])""")

private data class MovieNumberMatch(
    val match: MatchResult,
    val hasExplicitSeparator: Boolean
)

private fun MatchResult.hasExplicitSeparator(): Boolean {
    val prefixRange = groups[1]?.range ?: return false
    val numberRange = groups[2]?.range ?: return false
    if (numberRange.first <= prefixRange.last + 1) return false
    return value.substring(prefixRange.last + 1 - range.first, numberRange.first - range.first)
        .any { it == '-' || it == '_' || it == ' ' }
}
