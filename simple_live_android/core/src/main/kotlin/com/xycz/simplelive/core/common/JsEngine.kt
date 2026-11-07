package com.xycz.simplelive.core.common

/** 执行 JS 的引擎：App 用 QuickJS，JVM 单测用 node */
interface JsEngine {
    /** 依次执行 [scripts]，再执行 [expression] 并返回字符串结果 */
    fun evaluate(scripts: List<String>, expression: String): String
}

object Js {
    @Volatile
    lateinit var engine: JsEngine

    private val cache = HashMap<String, String>()

    /** 读取 core 资源里的 JS（resources/js/） */
    fun resource(name: String): String = synchronized(cache) {
        cache.getOrPut(name) {
            Js::class.java.getResourceAsStream("/js/$name")!!.bufferedReader().use { it.readText() }
        }
    }

    /** 把字符串转成 JS 字符串字面量 */
    fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(c)
            }
        }
        append('"')
    }
}
