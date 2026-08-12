package com.example.localmovielibrary.scraper

data class NumberPrefixRewriteRule(
    val prefix: String,
    val numericPrefix: String,
    val sources: Set<ScrapeSource>
) {
    val rewrittenPrefix: String get() = numericPrefix + prefix
}

fun rewriteNumberPrefix(
    number: String,
    rules: Iterable<NumberPrefixRewriteRule>,
    source: ScrapeSource? = null
): String {
    val normalized = number.trim().uppercase()
    val separatorIndex = normalized.indexOf('-')
    if (separatorIndex <= 0) return normalized
    val currentPrefix = normalized.substring(0, separatorIndex)
    val suffix = normalized.substring(separatorIndex)
    val rule = rules.firstOrNull { candidate ->
        currentPrefix == candidate.prefix || currentPrefix == candidate.rewrittenPrefix
    } ?: return normalized
    if (source != null && source !in rule.sources) return normalized
    return rule.rewrittenPrefix + suffix
}
