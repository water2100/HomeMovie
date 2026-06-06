package com.example.localmovielibrary.translate

enum class TranslateProvider(
    val id: String,
    val label: String
) {
    Baidu("baidu", "百度翻译"),
    DeepSeek("deepseek", "DeepSeek");

    companion object {
        fun fromId(id: String?): TranslateProvider =
            entries.firstOrNull { it.id == id } ?: Baidu
    }
}
