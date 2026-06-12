package com.example.localmovielibrary.util

object NumberRecognitionRules {
    val DEFAULT_IGNORED_SUFFIXES = setOf("HHB")
    val DEFAULT_PART_MARKERS = setOf("RESTORED")
    val DEFAULT_ATTACHED_LETTER_SEGMENT_PREFIXES = setOf("NHDTC")
    val DEFAULT_NUMERIC_PREFIX_ALIASES = mapOf(
        "DANDY" to "104DANDY",
        "SHN" to "116SHN",
        "GANA" to "200GANA",
        "SCUTE" to "229SCUTE",
        "LUXU" to "259LUXU",
        "ARA" to "261ARA",
        "DCV" to "277DCV",
        "MY" to "292MY",
        "EWDX" to "299EWDX",
        "MAAN" to "300MAAN",
        "MIUM" to "300MIUM",
        "NTK" to "300NTK",
        "KIRAY" to "314KIRAY",
        "KJO" to "326KJO",
        "NAMA" to "332NAMA",
        "KNB" to "336KNB",
        "SIMM" to "345SIMM",
        "NTR" to "348NTR",
        "ICHK" to "368ICHK",
        "JAC" to "390JAC",
        "KIWVR" to "408KIWVR",
        "INST" to "413INST",
        "SRYA" to "417SRYA",
        "SUKE" to "428SUKE",
        "MFC" to "435MFC",
        "HHH" to "451HHH",
        "TEN" to "459TEN",
        "MLA" to "476MLA",
        "SGK" to "483SGK",
        "GCB" to "485GCB",
        "SEI" to "502SEI",
        "STCV" to "529STCV"
    )

    @Volatile
    private var ignoredSuffixes: Set<String> = DEFAULT_IGNORED_SUFFIXES
    @Volatile
    private var partMarkers: Set<String> = DEFAULT_PART_MARKERS
    @Volatile
    private var numericPrefixAliases: Map<String, String> = DEFAULT_NUMERIC_PREFIX_ALIASES

    fun ignoredSuffixes(): Set<String> = ignoredSuffixes

    fun partMarkers(): Set<String> = partMarkers

    fun updateIgnoredSuffixes(value: Set<String>) {
        ignoredSuffixes = normalizeIgnoredSuffixes(DEFAULT_IGNORED_SUFFIXES + value)
    }

    fun updatePartMarkers(value: Set<String>) {
        partMarkers = normalizeRuleTokens(DEFAULT_PART_MARKERS + value)
    }

    fun updateNumericPrefixAliases(value: Map<String, String>) {
        numericPrefixAliases = (DEFAULT_NUMERIC_PREFIX_ALIASES + value).mapNotNull { (prefix, searchPrefix) ->
            val canonical = prefix.normalizedPrefixOrNull() ?: return@mapNotNull null
            val search = searchPrefix.normalizedPrefixOrNull() ?: return@mapNotNull null
            canonical to search
        }.toMap()
    }

    fun canonicalizePrefix(value: String): String {
        val normalized = value.normalizedPrefixOrNull() ?: return value.uppercase()
        return numericPrefixAliases.entries
            .firstOrNull { (_, searchPrefix) -> searchPrefix == normalized }
            ?.key
            ?: normalized
    }

    fun numericPrefixAliasFor(value: String): String? {
        val normalized = value.normalizedPrefixOrNull() ?: return null
        numericPrefixAliases[normalized]
            ?.takeIf { it.any(Char::isDigit) }
            ?.let { return it }
        return numericPrefixAliases.entries
            .firstOrNull { (_, searchPrefix) ->
                searchPrefix == normalized && searchPrefix.any(Char::isDigit)
            }
            ?.value
    }

    fun isAttachedLetterSegment(prefix: String, suffix: String): Boolean {
        val normalizedSuffix = suffix.trim().uppercase().filter(Char::isLetter)
        if (normalizedSuffix.length != 1) return false
        return canonicalizePrefix(prefix) in DEFAULT_ATTACHED_LETTER_SEGMENT_PREFIXES
    }

    fun normalizeIgnoredSuffixes(value: Iterable<String>): Set<String> =
        normalizeRuleTokens(value)

    fun normalizePartMarkers(value: Iterable<String>): Set<String> =
        normalizeRuleTokens(value)

    fun stripIgnoredSuffix(baseName: String, suffixes: Set<String> = ignoredSuffixes()): IgnoredSuffixStripResult {
        val markerTokens = normalizePartMarkers(partMarkers())
        val normalizedSuffixes = suffixes
            .mapNotNull { it.normalizedIgnoredSuffixOrNull() }
            .filterNot { it in markerTokens }
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

    fun partAfterMarker(suffix: String, markers: Set<String> = partMarkers()): String? {
        val normalizedMarkers = normalizePartMarkers(markers)
        if (normalizedMarkers.isEmpty()) return null
        val tokens = suffix.split(Regex("[._\\s-]+"))
            .mapNotNull { it.normalizedLooseTokenOrNull() }
        for (index in tokens.indices) {
            val token = tokens[index]
            if (token !in normalizedMarkers) continue
            val next = tokens.getOrNull(index + 1)
            if (next == null) return token
            if (next.length == 1 && next.first() in 'A'..'Z') return "$token-$next"
            Regex("""(?:PART|P)?0*([1-9][0-9]?)""")
                .matchEntire(next)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { return "$token-P$it" }
            return token
        }
        return null
    }

    fun String.normalizedIgnoredSuffixOrNull(): String? =
        normalizedRuleTokenOrNull()

    private fun normalizeRuleTokens(value: Iterable<String>): Set<String> =
        value.mapNotNull { it.normalizedRuleTokenOrNull() }.toSet()

    private fun String.normalizedRuleTokenOrNull(): String? =
        trim()
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() && it.any { char -> char.isLetter() } }

    private fun String.normalizedLooseTokenOrNull(): String? =
        trim()
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() }

    private fun String.normalizedPrefixOrNull(): String? =
        trim()
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() && it.any(Char::isLetter) }
}

data class IgnoredSuffixStripResult(
    val baseName: String,
    val stripped: Boolean
)
