package com.xycz.simple_live_tv.data

/** 弹幕屏蔽词：普通词按包含匹配，/.../ 形式按正则；屏蔽词变化时重新编译 */
object ShieldFilter {
    @Volatile
    private var patterns: List<Any> = emptyList()

    fun init() {
        rebuild()
        Store.listen { if (it == Store.TYPE_SHIELD) rebuild() }
    }

    private fun rebuild() {
        patterns = Store.shieldWords().mapNotNull { word ->
            if (word.length > 2 && word.startsWith("/") && word.endsWith("/")) {
                runCatching { Regex(word.substring(1, word.length - 1)) }.getOrNull()
            } else {
                word
            }
        }
    }

    fun isShielded(text: String): Boolean = patterns.any {
        when (it) {
            is Regex -> it.containsMatchIn(text)
            else -> text.contains(it as String)
        }
    }
}
