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
    val baseName = text.substringBeforeLast('.', text)
    val match = MOVIE_NUMBER_PATTERN
        .findAll(baseName)
        .lastOrNull()
        ?: return null
    val number = "${match.groupValues[1].uppercase(Locale.ROOT)}-${match.groupValues[2]}"
    val letterPart = match.groupValues.getOrNull(3)
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
    Regex("""(?i)\b([a-z]{2,10})[-_ ]?(\d{2,6})(?:[-_ ]([a-z]))?(?:$|[^a-z0-9])""")
