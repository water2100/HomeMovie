package com.example.localmovielibrary.util

import java.util.Locale

data class MovieNumberInfo(
    val number: String,
    val partLabel: String? = null
)

fun extractMovieNumberInfo(text: String): MovieNumberInfo? {
    val baseName = text.substringBeforeLast('.', text)
    val match = Regex("""(?i)\b([a-z]{2,10})[-_ ]?(\d{2,6})(?:[-_ ]([a-z]))?(?:$|[^a-z0-9])""")
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
    val directNumberedPart = Regex("""(?i)^0*([1-9][0-9]?)(?=$|[._ -])""")
        .find(suffix)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?.let { "P${it.toInt()}" }
    val part = numberedPart ?: normalizedNumberedPart ?: directNumberedPart ?: letterPart
    return MovieNumberInfo(number = number, partLabel = part)
}

fun normalizeMovieNumber(text: String): String? = extractMovieNumberInfo(text)?.number

fun partSortKey(label: String?): Int {
    val value = label?.uppercase(Locale.ROOT)
    return when {
        value != null && value.length == 1 && value.first() in 'A'..'Z' -> value.first() - 'A'
        value != null && value.matches(Regex("""P\d{1,2}""")) -> value.drop(1).toIntOrNull() ?: Int.MAX_VALUE
        else -> Int.MAX_VALUE
    }
}
