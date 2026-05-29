package com.example.localmovielibrary.util

enum class MovieVariant(val suffix: String) {
    Standard(""),
    FourK("-4K"),
    EightK("-8K")
}

fun detectMovieVariant(text: String): MovieVariant {
    val normalized = text.uppercase()
    return when {
        EIGHT_K_PATTERNS.any { it.containsMatchIn(normalized) } -> MovieVariant.EightK
        FOUR_K_PATTERNS.any { it.containsMatchIn(normalized) } -> MovieVariant.FourK
        else -> MovieVariant.Standard
    }
}

fun displayNumberWithVariant(number: String, sourceText: String): String {
    val cleanNumber = number.trim().uppercase()
    val variant = detectMovieVariant(sourceText)
    return cleanNumber + variant.suffix
}

fun playbackSourceSuffix(partLabel: String?, variant: MovieVariant): String {
    val tokens = buildList {
        partLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        variant.suffix
            .removePrefix("-")
            .takeIf { it.isNotBlank() }
            ?.let { add(it) }
    }
    return if (tokens.isEmpty()) "" else "-${tokens.joinToString("-")}"
}

private val FOUR_K_PATTERNS = listOf(
    Regex("""(^|[^A-Z0-9])4K(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])4KS(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])4K\d{2,3}FPS(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])4K2160P(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])UHD(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])2160P(?=$|[^A-Z0-9])""")
)

private val EIGHT_K_PATTERNS = listOf(
    Regex("""(^|[^A-Z0-9])8K(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])8KS(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])8K\d{2,3}FPS(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])8K4320P(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])4320P(?=$|[^A-Z0-9])""")
)
