package com.example.localmovielibrary.scraper

object MovieNumberExtractor {
    private val numberPattern = Regex("""(?i)([a-z]{2,8})[-_\s]?(\d{2,6})""")

    fun extract(fileName: String): String? {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val matches = numberPattern.findAll(baseName).toList()
        val match = matches.lastOrNull() ?: return null
        val prefix = match.groupValues[1].uppercase()
        val number = match.groupValues[2]
        return "$prefix-$number"
    }
}
