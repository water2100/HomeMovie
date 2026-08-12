package com.example.localmovielibrary.subtitle

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class DecodedSubtitleText(
    val text: String,
    val isUtf8: Boolean
)

/** Decodes common subtitle encodings, preferring UTF-8 and falling back to Chinese legacy encodings. */
fun decodeSubtitleBytes(bytes: ByteArray): DecodedSubtitleText {
    decodeStrict(bytes, Charsets.UTF_8)?.let { return DecodedSubtitleText(it.removePrefix("\uFEFF"), true) }

    val utf16 = when {
        bytes.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE
        bytes.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE
        else -> null
    }
    utf16?.let { charset ->
        decodeStrict(bytes.copyOfRange(2, bytes.size), charset)?.let {
            return DecodedSubtitleText(it.removePrefix("\uFEFF"), false)
        }
    }

    // GB18030 is a superset of GBK and is the safest legacy fallback for Chinese SRT files.
    val gb18030 = Charset.forName("GB18030")
    return DecodedSubtitleText(decodeStrict(bytes, gb18030).orEmpty(), false)
}

private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

private fun ByteArray.startsWith(first: Int, second: Int): Boolean =
    size >= 2 && this[0].toInt() and 0xFF == first && this[1].toInt() and 0xFF == second
