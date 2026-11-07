package com.xycz.simplelive.core.danmaku

import com.xycz.simplelive.core.model.LiveMessage
import com.xycz.simplelive.core.model.LiveMessageColor
import com.xycz.simplelive.core.model.LiveMessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DouyuDanmaku : WebSocketDanmaku() {
    override val heartbeatInterval = 45_000L
    private var roomId = ""

    override fun start(args: Any?) {
        roomId = args.toString()
        connect("wss://danmuproxy.douyu.com:8506")
    }

    override fun onSocketOpen() {
        send(serialize("type@=loginreq/roomid@=$roomId/"))
        send(serialize("type@=joingroup/rid@=$roomId/gid@=-9999/"))
    }

    override fun heartbeat() {
        send(serialize("type@=mrkl/"))
    }

    private fun serialize(body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val len = 4 + 4 + bytes.size + 1
        val buf = ByteBuffer.allocate(4 + len).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(len)
        buf.putInt(len)
        buf.putShort(689)
        buf.put(0)
        buf.put(0)
        buf.put(bytes)
        buf.put(0)
        return buf.array()
    }

    override fun onBinary(data: ByteArray) {
        // 一帧里可能有多个包
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 12) {
            val len = buf.int
            if (len < 9 || len > buf.remaining()) break
            val start = buf.position()
            buf.int // len 重复
            buf.short // 消息类型
            buf.get() // 加密
            buf.get() // 保留
            val bodyLen = len - 9
            val body = ByteArray(bodyLen)
            buf.get(body)
            buf.position(start + len)
            handle(String(body, Charsets.UTF_8).trimEnd('\u0000'))
        }
    }

    private fun handle(text: String) {
        if (!text.startsWith("type@=chatmsg/")) return
        @Suppress("UNCHECKED_CAST")
        val obj = sttToObject(text) as? Map<String, Any?> ?: return
        // 没有 dms 的是阴间弹幕
        if (obj["dms"] == null) return
        emit(
            LiveMessage(
                type = LiveMessageType.CHAT,
                userName = obj["nn"]?.toString() ?: "",
                message = obj["txt"]?.toString() ?: "",
                color = color(obj["col"]?.toString()?.toIntOrNull() ?: 0),
            ),
        )
    }

    companion object {
        /** 斗鱼 STT 序列化格式解析 */
        fun sttToObject(str: String): Any? {
            if (str.contains("//")) {
                return str.split("//").filter { it.isNotEmpty() }.map { sttToObject(it) }
            }
            if (str.contains("@=")) {
                val result = LinkedHashMap<String, Any?>()
                for (field in str.split('/')) {
                    if (field.isEmpty()) continue
                    val idx = field.indexOf("@=")
                    if (idx < 0) continue
                    result[field.substring(0, idx)] = sttToObject(unescape(field.substring(idx + 2)))
                }
                return result
            }
            if (str.contains("@A=")) return sttToObject(unescape(str))
            return unescape(str)
        }

        private fun unescape(str: String) = str.replace("@S", "/").replace("@A", "@")

        fun color(type: Int): LiveMessageColor = when (type) {
            1 -> LiveMessageColor(255, 0, 0)
            2 -> LiveMessageColor(30, 135, 240)
            3 -> LiveMessageColor(122, 200, 75)
            4 -> LiveMessageColor(255, 127, 0)
            5 -> LiveMessageColor(155, 57, 244)
            6 -> LiveMessageColor(255, 105, 180)
            else -> LiveMessageColor.WHITE
        }
    }
}
