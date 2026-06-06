package com.example.localmovielibrary.scraper

object NfoWriter {
    fun build(info: ScrapedMovieInfo): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("<movie>")
        tag("title", info.formattedTitle())
        tag("originaltitle", info.originalTitle)
        tag("sorttitle", info.number)
        tag("num", info.number)
        tag("plot", info.plot)
        tag("outline", info.outline)
        tag("premiered", info.premiered)
        tag("releasedate", info.premiered)
        tag("year", info.year)
        tag("runtime", info.runtime)
        tag("studio", info.studio)
        tag("maker", info.studio)
        tag("publisher", info.publisher)
        tag("label", info.publisher)
        tag("series", info.series)
        tag("rating", info.rating)
        tag("trailer", info.trailer)
        tag("website", info.website)
        tag("source", info.source)
        tag("thumb", info.thumbUrl)
        tag("poster", info.posterUrl)
        info.directors.normalizedValues().forEach { tag("director", it) }
        info.genres.normalizedValues().forEach { tag("genre", it) }
        info.tags.normalizedValues().forEach { tag("tag", it) }
        info.actors.normalizedValues().forEach { actor ->
            appendLine("  <actor>")
            tag("name", actor, indent = "    ")
            info.actorImageUrls[actor]?.takeIf { it.isNotBlank() }?.let { tag("thumb", it, indent = "    ") }
            tag("type", "Actor", indent = "    ")
            appendLine("  </actor>")
        }
        appendLine("</movie>")
    }

    private fun StringBuilder.tag(name: String, value: String, indent: String = "  ") {
        append(indent)
        append("<")
        append(name)
        append(">")
        append(value.escapeXml())
        append("</")
        append(name)
        appendLine(">")
    }

    private fun String.escapeXml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun ScrapedMovieInfo.formattedTitle(): String {
        val number = number.trim().uppercase()
        val rawTitle = title.trim().ifBlank { originalTitle.trim() }
        if (number.isBlank()) return rawTitle

        val body = rawTitle
            .withoutNumberPrefix(number)
            .ifBlank { originalTitle.trim().withoutNumberPrefix(number) }
            .ifBlank { rawTitle }
            .trim()

        return "[$number]$body"
    }

    private fun String.withoutNumberPrefix(number: String): String {
        if (isBlank()) return this
        val numberVariants = numberPrefixVariants(number)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("|") { Regex.escape(it) }
        if (numberVariants.isBlank()) return trim()

        val prefixPattern = Regex(
            pattern = "^\\s*[\\[\\u3010(\\uFF08]?\\s*(?:$numberVariants)\\s*[\\]\\u3011)\\uFF09]?\\s*[-_:\\uFF1A\\uFF0D\\u2014\\s]*",
            option = RegexOption.IGNORE_CASE
        )
        return replace(prefixPattern, "").trim()
    }

    private fun numberPrefixVariants(number: String): List<String> {
        val normalized = number.trim().uppercase()
        val compact = normalized.replace("-", "")
        val withoutLeadingZeros = Regex("""^([A-Z]{2,10})-(0*)(\d{1,6})$""")
            .find(normalized)
            ?.let { match ->
                val prefix = match.groupValues[1]
                val digits = match.groupValues[3].toIntOrNull()?.toString() ?: match.groupValues[3]
                "$prefix-$digits"
            }
        return buildList {
            add(normalized)
            add(compact)
            withoutLeadingZeros?.let {
                add(it)
                add(it.replace("-", ""))
            }
        }
    }

    private fun List<String>.normalizedValues(): List<String> =
        flatMap { value ->
            value.split(Regex("[,\\uFF0C\\u3001|;\\r\\n\\t]+"))
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
}
