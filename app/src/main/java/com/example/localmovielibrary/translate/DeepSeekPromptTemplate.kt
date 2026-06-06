package com.example.localmovielibrary.translate

data class DeepSeekPromptTemplate(
    val id: String,
    val label: String,
    val assetPath: String? = null
)

object DeepSeekPromptTemplates {
    const val CUSTOM_ID = "custom"
    const val DEFAULT_ID = "av_prompt_1"

    val options = listOf(
        DeepSeekPromptTemplate(
            id = "av_prompt_1",
            label = "AV翻译提示词1",
            assetPath = "prompts/av_prompt_1.txt"
        ),
        DeepSeekPromptTemplate(
            id = "av_prompt_2",
            label = "AV翻译提示词2",
            assetPath = "prompts/av_prompt_2.txt"
        ),
        DeepSeekPromptTemplate(
            id = CUSTOM_ID,
            label = "自定义提示词"
        )
    )

    fun find(id: String?): DeepSeekPromptTemplate =
        options.firstOrNull { it.id == id } ?: options.first()
}
