package com.example.localmovielibrary.cloud115

import java.math.BigInteger
import java.util.Base64

object P115Cipher {
    private val gKts = byteArrayOf(
        0xf0, 0xe5, 0x69, 0xae, 0xbf, 0xdc, 0xbf, 0x8a, 0x1a, 0x45, 0xe8, 0xbe, 0x7d, 0xa6, 0x73, 0xb8,
        0xde, 0x8f, 0xe7, 0xc4, 0x45, 0xda, 0x86, 0xc4, 0x9b, 0x64, 0x8b, 0x14, 0x6a, 0xb4, 0xf1, 0xaa,
        0x38, 0x01, 0x35, 0x9e, 0x26, 0x69, 0x2c, 0x86, 0x00, 0x6b, 0x4f, 0xa5, 0x36, 0x34, 0x62, 0xa6,
        0x2a, 0x96, 0x68, 0x18, 0xf2, 0x4a, 0xfd, 0xbd, 0x6b, 0x97, 0x8f, 0x4d, 0x8f, 0x89, 0x13, 0xb7,
        0x6c, 0x8e, 0x93, 0xed, 0x0e, 0x0d, 0x48, 0x3e, 0xd7, 0x2f, 0x88, 0xd8, 0xfe, 0xfe, 0x7e, 0x86,
        0x50, 0x95, 0x4f, 0xd1, 0xeb, 0x83, 0x26, 0x34, 0xdb, 0x66, 0x7b, 0x9c, 0x7e, 0x9d, 0x7a, 0x81,
        0x32, 0xea, 0xb6, 0x33, 0xde, 0x3a, 0xa9, 0x59, 0x34, 0x66, 0x3b, 0xaa, 0xba, 0x81, 0x60, 0x48,
        0xb9, 0xd5, 0x81, 0x9c, 0xf8, 0x6c, 0x84, 0x77, 0xff, 0x54, 0x78, 0x26, 0x5f, 0xbe, 0xe8, 0x1e,
        0x36, 0x9f, 0x34, 0x80, 0x5c, 0x45, 0x2c, 0x9b, 0x76, 0xd5, 0x1b, 0x8f, 0xcc, 0xc3, 0xb8, 0xf5
    )
    private val gKeyL = byteArrayOf(0x78, 0x06, 0xad, 0x4c, 0x33, 0x86, 0x5d, 0x18, 0x4c, 0x01, 0x3f, 0x46)
    private val rsaRandKey = ByteArray(16)
    private val rsaKey = byteArrayOf(0x8d, 0xa5, 0xa5, 0x8d)
    private val modulus = BigInteger(
        "8686980c0f5a24c4b9d43020cd2c22703ff3f450756529058b1cf88f09b8602136477198a6e2683149659bd122c33592fdb5ad47944ad1ea4d36c6b172aad6338c3bb6ac6227502d010993ac967d1aef00f0c8e038de2e4d3bc2ec368af2e9f10a6f1eda4f7262f136420c07c331b871bf139f74f3010e3c4fe57df3afb71683",
        16
    )
    private val exponent = BigInteger("10001", 16)

    fun encrypt(data: String): String {
        val tmp = xor(data.toByteArray(Charsets.UTF_8), rsaKey).reversedArray()
        val payload = rsaRandKey + xor(tmp, gKeyL)
        return Base64.getEncoder().encodeToString(rsaEncryptWithPubkey(payload))
    }

    fun decrypt(cipherText: String): String {
        val decoded = Base64.getDecoder().decode(cipherText)
        val data = rsaDecryptWithPubkey(decoded)
        if (data.size <= 16) return ""
        val randKey = data.copyOfRange(0, 16)
        val keyL = rsaGenKey(randKey, 12)
        val tmp = xor(data.copyOfRange(16, data.size), keyL).reversedArray()
        return String(xor(tmp, rsaKey), Charsets.UTF_8)
    }

    private fun rsaEncryptWithPubkey(data: ByteArray): ByteArray {
        val result = mutableListOf<Byte>()
        data.toList().chunked(117).forEach { chunk ->
            val encrypted = padPkcs1V15(chunk.toByteArray()).modPow(exponent, modulus)
            result += encrypted.toFixedByteArray(128).toList()
        }
        return result.toByteArray()
    }

    private fun rsaDecryptWithPubkey(data: ByteArray): ByteArray {
        val result = mutableListOf<Byte>()
        data.toList().chunked(128).forEach { chunk ->
            val decrypted = BigInteger(1, chunk.toByteArray()).modPow(exponent, modulus)
            val bytes = decrypted.toMinimalByteArray()
            val zeroIndex = bytes.indexOf(0)
            if (zeroIndex >= 0 && zeroIndex + 1 < bytes.size) {
                result += bytes.copyOfRange(zeroIndex + 1, bytes.size).toList()
            }
        }
        return result.toByteArray()
    }

    private fun padPkcs1V15(message: ByteArray): BigInteger {
        val padded = ByteArray(128)
        padded[0] = 0x00
        val fillLength = 126 - message.size
        for (i in 1 until 1 + fillLength) padded[i] = 0x02
        padded[1 + fillLength] = 0x00
        message.copyInto(padded, 2 + fillLength)
        return BigInteger(1, padded)
    }

    private fun rsaGenKey(randKey: ByteArray, skLen: Int): ByteArray {
        val xorKey = ByteArray(skLen)
        var length = skLen * (skLen - 1)
        var index = 0
        for (i in 0 until skLen) {
            val x = ((randKey[i].toInt() and 0xff) + (gKts[index].toInt() and 0xff)) and 0xff
            xorKey[i] = ((gKts[length].toInt() and 0xff) xor x).toByte()
            length -= skLen
            index += skLen
        }
        return xorKey
    }

    private fun xor(src: ByteArray, key: ByteArray): ByteArray {
        val result = ByteArray(src.size)
        val rem = src.size and 0b11
        for (i in src.indices) {
            val keyIndex = if (i < rem) i else (i - rem) % key.size
            result[i] = ((src[i].toInt() and 0xff) xor (key[keyIndex].toInt() and 0xff)).toByte()
        }
        return result
    }

    private fun BigInteger.toFixedByteArray(size: Int): ByteArray {
        val raw = toMinimalByteArray()
        return when {
            raw.size == size -> raw
            raw.size > size -> raw.copyOfRange(raw.size - size, raw.size)
            else -> ByteArray(size - raw.size) + raw
        }
    }

    private fun BigInteger.toMinimalByteArray(): ByteArray {
        val raw = toByteArray()
        return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    private fun ByteArray.reversedArray(): ByteArray =
        ByteArray(size) { index -> this[size - 1 - index] }
}

private fun byteArrayOf(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
