package com.hymt2.app.translate

/**
 * Official Hy-MT2 default-translation instructions wrapped in the model's chat format
 * (equivalent to the GGUF's jinja template with one user turn and add_generation_prompt).
 */
object PromptBuilder {
    private const val BOS = "<｜hy_begin▁of▁sentence｜>"
    private const val USER = "<｜hy_User｜>"
    private const val ASSISTANT = "<｜hy_Assistant｜>"

    /** @param source null means auto-detect; only used to choose the instruction language. */
    fun build(text: String, source: Language?, target: Language): String {
        val chinesePrompt = source?.isChinese ?: containsCjk(text)
        val instruction = if (chinesePrompt) {
            "将以下文本翻译为${target.nameZh}，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
        } else {
            "Translate the following text into ${target.nameEn}. Note that you should only output " +
                "the translated result without any additional explanation:\n\n$text"
        }
        return BOS + USER + instruction + ASSISTANT
    }

    internal fun containsCjk(text: String): Boolean =
        text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
}
