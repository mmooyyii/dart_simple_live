package com.xycz.simplelive.core.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** 各平台接口返回的 JSON 结构多变，统一用动态访问 */
val JsonCodec = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

fun parseJson(text: String): JsonElement = JsonCodec.parseToJsonElement(text)

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

/** 字符串值；null 返回空串，对象/数组返回 JSON 文本 */
val JsonElement?.str: String
    get() = when (this) {
        null, is JsonNull -> ""
        is JsonPrimitive -> content
        else -> toString()
    }

val JsonElement?.strOrNull: String?
    get() = if (this == null || this is JsonNull) null else str

val JsonElement?.long: Long?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.trim().toLongOrNull()
    }

val JsonElement?.int: Int? get() = long?.toInt()

/** 布尔值，兼容 1/0、"1"/"0" */
val JsonElement?.bool: Boolean
    get() {
        val p = this as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: (long == 1L)
    }

val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()

val JsonElement?.obj: JsonObject? get() = this as? JsonObject

/** 从 [start] 开始按括号配对截出一个完整的 JSON 对象（会跳过字符串里的括号） */
fun extractJsonObject(text: String, start: Int): String? {
    var depth = 0
    var inString = false
    var escape = false
    for (i in start until text.length) {
        val c = text[i]
        if (inString) {
            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}
