package com.hymt2.app.translate

data class Language(val code: String, val nameEn: String, val nameZh: String) {
    val isChinese: Boolean get() = code == "zh" || code == "zh-Hant" || code == "yue"
}

/** Languages supported by Hy-MT (full names are used inside the prompts). */
object Languages {
    val all: List<Language> = listOf(
        Language("zh", "Chinese", "中文"),
        Language("en", "English", "英语"),
        Language("fr", "French", "法语"),
        Language("pt", "Portuguese", "葡萄牙语"),
        Language("es", "Spanish", "西班牙语"),
        Language("ja", "Japanese", "日语"),
        Language("tr", "Turkish", "土耳其语"),
        Language("ru", "Russian", "俄语"),
        Language("ar", "Arabic", "阿拉伯语"),
        Language("ko", "Korean", "韩语"),
        Language("th", "Thai", "泰语"),
        Language("it", "Italian", "意大利语"),
        Language("de", "German", "德语"),
        Language("vi", "Vietnamese", "越南语"),
        Language("ms", "Malay", "马来语"),
        Language("id", "Indonesian", "印尼语"),
        Language("tl", "Filipino", "菲律宾语"),
        Language("hi", "Hindi", "印地语"),
        Language("zh-Hant", "Traditional Chinese", "繁体中文"),
        Language("pl", "Polish", "波兰语"),
        Language("cs", "Czech", "捷克语"),
        Language("nl", "Dutch", "荷兰语"),
        Language("km", "Khmer", "高棉语"),
        Language("my", "Burmese", "缅甸语"),
        Language("fa", "Persian", "波斯语"),
        Language("gu", "Gujarati", "古吉拉特语"),
        Language("ur", "Urdu", "乌尔都语"),
        Language("te", "Telugu", "泰卢固语"),
        Language("mr", "Marathi", "马拉地语"),
        Language("he", "Hebrew", "希伯来语"),
        Language("bn", "Bengali", "孟加拉语"),
        Language("ta", "Tamil", "泰米尔语"),
        Language("uk", "Ukrainian", "乌克兰语"),
        Language("bo", "Tibetan", "藏语"),
        Language("kk", "Kazakh", "哈萨克语"),
        Language("mn", "Mongolian", "蒙古语"),
        Language("ug", "Uyghur", "维吾尔语"),
        Language("yue", "Cantonese", "粤语"),
    )

    fun byCode(code: String): Language? = all.firstOrNull { it.code == code }
}
