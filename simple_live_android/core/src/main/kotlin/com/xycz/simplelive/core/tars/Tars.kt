package com.xycz.simplelive.core.tars

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Tars 数据类型 */
private object T {
    const val BYTE = 0
    const val SHORT = 1
    const val INT = 2
    const val LONG = 3
    const val FLOAT = 4
    const val DOUBLE = 5
    const val STRING1 = 6
    const val STRING4 = 7
    const val MAP = 8
    const val LIST = 9
    const val STRUCT_BEGIN = 10
    const val STRUCT_END = 11
    const val ZERO = 12
    const val SIMPLE_LIST = 13
}

/** Tars 编码 */
class TarsWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun head(type: Int, tag: Int) {
        if (tag < 15) {
            out.write((tag shl 4) or type)
        } else {
            out.write((15 shl 4) or type)
            out.write(tag)
        }
    }

    private fun raw(value: Long, bytes: Int) {
        for (i in bytes - 1 downTo 0) out.write(((value shr (i * 8)) and 0xFF).toInt())
    }

    fun long(value: Long, tag: Int): TarsWriter {
        when (value) {
            0L -> head(T.ZERO, tag)
            in -128..127 -> { head(T.BYTE, tag); raw(value, 1) }
            in -32768..32767 -> { head(T.SHORT, tag); raw(value, 2) }
            in Int.MIN_VALUE..Int.MAX_VALUE -> { head(T.INT, tag); raw(value, 4) }
            else -> { head(T.LONG, tag); raw(value, 8) }
        }
        return this
    }

    fun bool(value: Boolean, tag: Int) = long(if (value) 1 else 0, tag)

    fun string(value: String, tag: Int): TarsWriter {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > 255) {
            head(T.STRING4, tag)
            raw(bytes.size.toLong(), 4)
        } else {
            head(T.STRING1, tag)
            out.write(bytes.size)
        }
        out.write(bytes)
        return this
    }

    fun bytes(value: ByteArray, tag: Int): TarsWriter {
        head(T.SIMPLE_LIST, tag)
        head(T.BYTE, 0)
        long(value.size.toLong(), 0)
        out.write(value)
        return this
    }

    fun struct(tag: Int, block: TarsWriter.() -> Unit): TarsWriter {
        head(T.STRUCT_BEGIN, tag)
        block()
        head(T.STRUCT_END, 0)
        return this
    }

    fun stringMap(value: Map<String, String>, tag: Int): TarsWriter {
        head(T.MAP, tag)
        long(value.size.toLong(), 0)
        for ((k, v) in value) {
            string(k, 0)
            string(v, 1)
        }
        return this
    }

    fun bytesMap(value: Map<String, ByteArray>, tag: Int): TarsWriter {
        head(T.MAP, tag)
        long(value.size.toLong(), 0)
        for ((k, v) in value) {
            string(k, 0)
            bytes(v, 1)
        }
        return this
    }
}

/** 解码后的 Tars 结构：tag -> 值（Long / Double / String / ByteArray / List / Map / TarsStruct） */
class TarsStruct(val fields: Map<Int, Any?>) {
    fun long(tag: Int, default: Long = 0): Long = when (val v = fields[tag]) {
        is Long -> v
        is Double -> v.toLong()
        is String -> v.toLongOrNull() ?: default
        else -> default
    }

    fun int(tag: Int, default: Int = 0): Int = long(tag, default.toLong()).toInt()

    fun string(tag: Int): String = when (val v = fields[tag]) {
        is String -> v
        is ByteArray -> String(v, Charsets.UTF_8)
        null -> ""
        else -> v.toString()
    }

    fun bytes(tag: Int): ByteArray = when (val v = fields[tag]) {
        is ByteArray -> v
        is String -> v.toByteArray(Charsets.UTF_8)
        is List<*> -> ByteArray(v.size) { (v[it] as? Long)?.toByte() ?: 0 }
        else -> ByteArray(0)
    }

    fun struct(tag: Int): TarsStruct = fields[tag] as? TarsStruct ?: TarsStruct(emptyMap())

    fun map(tag: Int): Map<*, *> = fields[tag] as? Map<*, *> ?: emptyMap<Any, Any>()

    fun list(tag: Int): List<*> = fields[tag] as? List<*> ?: emptyList<Any>()

    companion object {
        /** 把一段字节当作结构体的字段序列解析（顶层没有 STRUCT_BEGIN） */
        fun decode(bytes: ByteArray, offset: Int = 0): TarsStruct =
            TarsReader(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).readFields()
    }
}

private class TarsReader(private val buf: ByteBuffer) {
    private var type = 0
    private var tag = 0

    private fun readHead() {
        val b = buf.get().toInt() and 0xFF
        type = b and 0x0F
        tag = (b shr 4) and 0x0F
        if (tag == 15) tag = buf.get().toInt() and 0xFF
    }

    fun readFields(): TarsStruct {
        val fields = HashMap<Int, Any?>()
        while (buf.hasRemaining()) {
            readHead()
            if (type == T.STRUCT_END) break
            fields[tag] = readValue(type)
        }
        return TarsStruct(fields)
    }

    private fun readValue(valueType: Int): Any? = when (valueType) {
        T.BYTE -> buf.get().toLong()
        T.SHORT -> buf.short.toLong()
        T.INT -> buf.int.toLong()
        T.LONG -> buf.long
        T.FLOAT -> buf.float.toDouble()
        T.DOUBLE -> buf.double
        T.STRING1 -> readString(buf.get().toInt() and 0xFF)
        T.STRING4 -> readString(buf.int)
        T.MAP -> {
            val size = readLengthField()
            val map = LinkedHashMap<Any?, Any?>()
            repeat(size) {
                readHead()
                val k = readValue(type)
                readHead()
                val v = readValue(type)
                map[k] = v
            }
            map
        }
        T.LIST -> {
            val size = readLengthField()
            List(size) {
                readHead()
                readValue(type)
            }
        }
        T.STRUCT_BEGIN -> readFields()
        T.STRUCT_END -> null
        T.ZERO -> 0L
        T.SIMPLE_LIST -> {
            readHead() // 元素类型，固定为 BYTE
            val size = readLengthField()
            ByteArray(size).also { buf.get(it) }
        }
        else -> throw IllegalStateException("未知的 Tars 类型 $valueType")
    }

    private fun readLengthField(): Int {
        readHead()
        return (readValue(type) as Long).toInt()
    }

    private fun readString(len: Int): String {
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
