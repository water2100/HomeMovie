package com.example.localmovielibrary.subtitle

enum class SubtitleSearchProvider(
    val id: String,
    val label: String,
    val fileSuffix: String
) {
    Javzimu("javzimu", "Javzimu.com", "javzimu"),
    Avsubtitles("avsubtitles", "AVSubtitles", "avsubtitles"),
    Xunlei("xunlei", "迅雷字幕", "xunlei");

    companion object {
        fun fromId(id: String?): SubtitleSearchProvider =
            entries.firstOrNull { it.id == id } ?: Xunlei
    }
}
