package com.example.localmovielibrary.scanner

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

class NfoParser(private val contentResolver: ContentResolver) {
    fun parse(uri: Uri): NfoMetadata = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            parseMovie(parser)
        }
    }.getOrNull() ?: NfoMetadata()

    private fun parseMovie(parser: XmlPullParser): NfoMetadata {
        val studios = mutableListOf<String>()
        val directors = mutableListOf<String>()
        val actors = mutableListOf<String>()
        val genres = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val uniqueIds = mutableListOf<String>()

        var title: String? = null
        var originalTitle: String? = null
        var plot: String? = null
        var outline: String? = null
        var year: Int? = null
        var premiered: String? = null
        var runtime: Int? = null
        var mpaa: String? = null
        var series: String? = null
        var rating: Double? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name.lowercase()) {
                "title" -> title = parser.readText()
                "originaltitle" -> originalTitle = parser.readText()
                "plot" -> plot = parser.readText()
                "outline" -> outline = parser.readText()
                "year" -> year = parser.readText().toIntOrNull()
                "premiered" -> premiered = parser.readText()
                "runtime" -> runtime = parser.readText().extractFirstInt()
                "mpaa" -> mpaa = parser.readText()
                "certification" -> mpaa = parser.readText()
                "studio" -> studios += parser.readText().splitMultiValue()
                "series", "set" -> series = parser.readText()
                "director" -> directors += parser.readText().splitMultiValue()
                "genre" -> genres += parser.readText().splitMultiValue()
                "tag" -> {
                    val rawTag = parser.readText()
                    when (val fieldTag = rawTag.asFieldLikeTag()) {
                        is FieldLikeTag.Series -> if (series.isNullOrBlank()) series = fieldTag.value
                        is FieldLikeTag.Studio -> if (studios.isEmpty()) studios += fieldTag.value
                        null -> tags += rawTag.splitMultiValue().filterNot { it.asFieldLikeTag() != null }
                    }
                }
                "rating" -> rating = parseRating(parser) ?: rating
                "uniqueid" -> {
                    val type = parser.getAttributeValue(null, "type")
                    val value = parser.readText()
                    if (value.isNotBlank()) uniqueIds += listOfNotNull(type?.takeIf { it.isNotBlank() }, value)
                        .joinToString(":")
                }
                "actor" -> parseActor(parser)?.let { actors += it }
            }
        }

        return NfoMetadata(
            title = title.clean(),
            originalTitle = originalTitle.clean(),
            plot = plot.clean(),
            outline = outline.clean(),
            year = year,
            premiered = premiered.clean(),
            runtimeMinutes = runtime,
            mpaa = mpaa.clean(),
            studios = studios.cleanedDistinct(),
            series = series.clean(),
            directors = directors.cleanedDistinct(),
            actors = actors.cleanedDistinct(),
            genres = genres.cleanedDistinct(),
            tags = tags.cleanedDistinct(),
            rating = rating,
            uniqueIds = uniqueIds.cleanedDistinct()
        )
    }

    private fun parseActor(parser: XmlPullParser): String? {
        val depth = parser.depth
        var name: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("name", ignoreCase = true)) {
                name = parser.readText()
            }
        }
        return name.clean()
    }

    private fun parseRating(parser: XmlPullParser): Double? {
        if (parser.isEmptyElementTag) return null
        val depth = parser.depth
        var value: Double? = null
        var sawNestedTag = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType == XmlPullParser.TEXT && !sawNestedTag) {
                value = parser.text.trim().toDoubleOrNull() ?: value
            }
            if (parser.eventType == XmlPullParser.START_TAG) {
                sawNestedTag = true
                if (parser.name.equals("value", ignoreCase = true)) {
                    value = parser.readText().toDoubleOrNull() ?: value
                }
            }
        }
        return value
    }

    private fun XmlPullParser.readText(): String {
        if (next() != XmlPullParser.TEXT) return ""
        val result = text.orEmpty()
        nextTag()
        return result.trim()
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun String.extractFirstInt(): Int? =
        Regex("""\d+""").find(this)?.value?.toIntOrNull()

    private fun String.splitMultiValue(): List<String> =
        split(Regex("""[/,，、|;\r\n\t]+""")).map { it.trim() }.filter { it.isNotBlank() }

    private fun List<String>.cleanedDistinct(): List<String> =
        map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun String.asFieldLikeTag(): FieldLikeTag? {
        val value = trim()
        if (value.isBlank()) return null
        val separatorIndex = listOf(value.indexOf(':'), value.indexOf('：'))
            .filter { it >= 0 }
            .minOrNull()
            ?: return null
        val key = value.substring(0, separatorIndex).trim().lowercase()
        val content = value.substring(separatorIndex + 1).trim().takeIf { it.isNotBlank() } ?: return null
        return when (key) {
            "系列", "series", "シリーズ" -> FieldLikeTag.Series(content)
            "片商", "发行", "發行", "maker", "メーカー", "studio", "label", "レーベル", "publisher" -> FieldLikeTag.Studio(content)
            else -> null
        }
    }

    private sealed interface FieldLikeTag {
        val value: String

        data class Series(override val value: String) : FieldLikeTag
        data class Studio(override val value: String) : FieldLikeTag
    }
}
