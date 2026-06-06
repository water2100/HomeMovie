package com.example.localmovielibrary.util

enum class MovieVariant(val suffix: String, val displayName: String) {
    Standard("", ""),
    SixtyFps("-60FPS", "60FPS"),
    FourK("-4K", "4K"),
    FourK60Fps("-4K60FPS", "4K60FPS"),
    EightK("-8K", "8K"),
    EightK60Fps("-8K60FPS", "8K60FPS")
}

fun detectMovieVariant(text: String): MovieVariant {
    val normalized = text.uppercase()
    return when {
        EIGHT_K_PATTERNS.any { it.containsMatchIn(normalized) } && FPS_60_PATTERNS.any { it.containsMatchIn(normalized) } -> MovieVariant.EightK60Fps
        FOUR_K_PATTERNS.any { it.containsMatchIn(normalized) } && FPS_60_PATTERNS.any { it.containsMatchIn(normalized) } -> MovieVariant.FourK60Fps
        EIGHT_K_PATTERNS.any { it.containsMatchIn(normalized) } -> MovieVariant.EightK
        FOUR_K_PATTERNS.any { it.containsMatchIn(normalized) } -> MovieVariant.FourK
        FPS_60_PATTERNS.any { it.containsMatchIn(normalized) } -> MovieVariant.SixtyFps
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
        variant.displayName
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

private val FPS_60_PATTERNS = listOf(
    Regex("""(^|[^A-Z0-9])60FPS(?=$|[^A-Z0-9])"""),
    Regex("""60FPS(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])60P(?=$|[^A-Z0-9])"""),
    Regex("""(^|[^A-Z0-9])FPS60(?=$|[^A-Z0-9])""")
)
