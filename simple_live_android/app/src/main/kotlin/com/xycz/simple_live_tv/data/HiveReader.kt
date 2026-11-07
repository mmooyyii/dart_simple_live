package com.xycz.simple_live_tv.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Hive 2.x 自定义对象：typeId + 字段号 -> 值 */
data class HiveObject(val typeId: Int, val fields: Map<Int, Any?>)

/** Hive 内置的带时区 DateTime（typeId 18） */
data class HiveDateTime(val millis: Long, val isUtc: Boolean)

/**
 * 只读解析 Hive 2.2.3 的 .hive 文件（Flutter 版的存储），用于迁移旧数据
 *
 * 帧格式：uint32 长度(含自身和 CRC) | key | value | uint32 CRC32，均为小端；
 * 文件是追加日志，同一 key 以最后一帧为准，只有 key 没有 value 的帧表示删除
 */
object HiveReader {
    fun readBox(file: File): Map<Any, Any?> {
        if (!file.exists()) return emptyMap()
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = LinkedHashMap<Any, Any?>()
        while (buf.remaining() >= 4) {
            val start = buf.position()
            val frameLength = buf.int
            if (frameLength < 8 || start + frameLength > bytes.size) break
            val crcStored = ByteBuffer.wrap(bytes, start + frameLength - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val crc = CRC32().apply { update(bytes, start, frameLength - 4) }.value.toInt()
            if (crc != crcStored) break // 文件尾部写了一半的帧
            val frame = ByteBuffer.wrap(bytes, start + 4, frameLength - 8).slice().order(ByteOrder.LITTLE_ENDIAN)
            val key = readKey(frame)
            if (frame.hasRemaining()) {
                result[key] = readValue(frame)
            } else {
                result.remove(key)
            }
            buf.position(start + frameLength)
        }
        return result
    }

    private fun readKey(buf: ByteBuffer): Any = when (val type = buf.get().toInt()) {
        0 -> buf.int.toLong() and 0xFFFFFFFFL
        1 -> {
            val len = buf.get().toInt() and 0xFF
            String(ByteArray(len).also { buf.get(it) }, Charsets.UTF_8)
        }
        else -> throw IllegalStateException("未知的 Hive key 类型 $type")
    }

    private fun readString(buf: ByteBuffer): String {
        val len = buf.int
        return String(ByteArray(len).also { buf.get(it) }, Charsets.UTF_8)
    }

    private fun readValue(buf: ByteBuffer): Any? {
        val typeId = buf.get().toInt() and 0xFF
        return when (typeId) {
            0 -> null
            1 -> buf.double.toLong() // int 按 float64 存
            2 -> buf.double
            3 -> buf.get().toInt() != 0
            4 -> readString(buf)
            5 -> ByteArray(buf.int).also { buf.get(it) }
            6 -> List(buf.int) { buf.double.toLong() }
            7 -> List(buf.int) { buf.double }
            8 -> List(buf.int) { buf.get().toInt() != 0 }
            9 -> List(buf.int) { readString(buf) }
            10 -> List(buf.int) { readValue(buf) }
            11 -> {
                val size = buf.int
                val map = LinkedHashMap<Any?, Any?>()
                repeat(size) { map[readValue(buf)] = readValue(buf) }
                map
            }
            16 -> HiveDateTime(buf.double.toLong(), false)
            18 -> HiveDateTime(buf.double.toLong(), buf.get().toInt() != 0)
            else -> {
                if (typeId < 32) throw IllegalStateException("不支持的 Hive 内置类型 $typeId")
                // 生成的 Adapter：字段数 + (字段号, 值)*
                val count = buf.get().toInt() and 0xFF
                val fields = HashMap<Int, Any?>()
                repeat(count) {
                    val index = buf.get().toInt() and 0xFF
                    fields[index] = readValue(buf)
                }
                HiveObject(typeId - 32, fields)
            }
        }
    }
}
