package com.example.localmovielibrary.cloud115

interface Cloud115Client {
    suspend fun listFiles(cid: Long): List<Cloud115FileItem>
    suspend fun fetchDirectUrl(pickcode: String): String
    suspend fun downloadBytes(url: String): ByteArray
}
