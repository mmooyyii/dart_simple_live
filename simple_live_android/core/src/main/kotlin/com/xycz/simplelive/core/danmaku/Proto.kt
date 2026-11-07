package com.xycz.simplelive.core.danmaku

import java.io.ByteArrayOutputStream

/** 最小 protobuf 解码：字段号 -> 值列表（varint 为 Long，长度定界为 ByteArray） */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {
    fun long(field: Int): Long = fields[field]?.firstOrNull() as? Long ?: 0
    fun bool(field: Int): Boolean = long(field) != 0L
    fun bytes(field: Int): ByteArray = fields[field]?.firstOrNull() as? ByteArray ?: ByteArray(0)
    fun string(field: Int): String = String(bytes(field), Charsets.UTF_8)
    fun message(field: Int): ProtoMessage = decode(bytes(field))
    fun repeatedBytes(field: Int): List<ByteArray> = fields[field]?.filterIsInstance<ByteArray>() ?: emptyList()

    companion object {
        fun decode(data: ByteArray): ProtoMessage {
            val fields = HashMap<Int, MutableList<Any>>()
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b < 0x80) return result
                    shift += 7
                }
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> varint()
                    1 -> { pos += 8; 0L }
                    2 -> {
                        val len = varint().toInt()
                        data.copyOfRange(pos, pos + len).also { pos += len }
                    }
                    5 -> { pos += 4; 0L }
                    else -> throw IllegalStateException("不支持的 protobuf wire type")
                }
                fields.getOrPut(field) { mutableListOf() }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}

class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun long(field: Int, value: Long): ProtoWriter {
        varint((field shl 3).toLong())
        varint(value)
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        varint(((field shl 3) or 2).toLong())
        varint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()
}
