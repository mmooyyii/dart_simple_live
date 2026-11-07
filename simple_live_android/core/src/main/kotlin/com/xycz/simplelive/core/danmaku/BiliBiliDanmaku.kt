package com.xycz.simplelive.core.danmaku

import com.xycz.simplelive.core.common.CoreLog
import com.xycz.simplelive.core.common.get
import com.xycz.simplelive.core.common.int
import com.xycz.simplelive.core.common.long
import com.xycz.simplelive.core.common.parseJson
import com.xycz.simplelive.core.common.str
import com.xycz.simplelive.core.model.LiveMessage
import com.xycz.simplelive.core.model.LiveMessageColor
import com.xycz.simplelive.core.model.LiveMessageType
import com.xycz.simplelive.core.model.LiveSuperChatMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.InflaterInputStream

data class BiliBiliDanmakuArgs(
    val roomId: Long,
    val token: String,
    val buvid: String,
    val serverHost: String,
    val uid: Long,
    val cookie: String,
)

class BiliBiliDanmaku : WebSocketDanmaku() {
    override val heartbeatInterval = 60_000L
    private lateinit var args: BiliBiliDanmakuArgs

    override fun start(args: Any?) {
        this.args = args as BiliBiliDanmakuArgs
        val headers = if (args.cookie.isNotEmpty()) mapOf("cookie" to args.cookie) else emptyMap()
        connect("wss://${args.serverHost}/sub", headers)
    }

    override fun onSocketOpen() {
        val body = buildJsonObject {
            put("uid", args.uid)
            put("roomid", args.roomId)
            put("protover", 3)
            put("buvid", args.buvid)
            put("platform", "web")
            put("type", 2)
            put("key", args.token)
        }.toString()
        send(encode(body.toByteArray(), OP_AUTH))
    }

    override fun heartbeat() {
        send(encode(ByteArray(0), OP_HEARTBEAT))
    }

    private fun encode(body: ByteArray, op: Int): ByteArray {
        val buf = ByteBuffer.allocate(16 + body.size)
        buf.putInt(16 + body.size)
        buf.putShort(16)
        buf.putShort(0)
        buf.putInt(op)
        buf.putInt(1)
        buf.put(body)
        return buf.array()
    }

    override fun onBinary(data: ByteArray) {
        decodePackets(data)
    }

    /** 一个 WebSocket 帧可能包含多个包；压缩包解压后又是一串包 */
    private fun decodePackets(data: ByteArray) {
        var offset = 0
        while (offset + 16 <= data.size) {
            val buf = ByteBuffer.wrap(data, offset, 16)
            val packetLen = buf.int
            val headerLen = buf.short.toInt()
            val protover = buf.short.toInt()
            val op = buf.int
            if (packetLen < 16 || offset + packetLen > data.size) break
            val body = data.copyOfRange(offset + headerLen, offset + packetLen)
            when (op) {
                OP_HEARTBEAT_REPLY -> if (body.size >= 4) {
                    val online = ByteBuffer.wrap(body).int.toLong()
                    emit(LiveMessage(LiveMessageType.ONLINE, data = online))
                }
                OP_MESSAGE -> when (protover) {
                    2 -> decodePackets(InflaterInputStream(ByteArrayInputStream(body)).readBytes())
                    3 -> decodePackets(BrotliInputStream(ByteArrayInputStream(body)).readBytes())
                    else -> parseMessage(body.toString(Charsets.UTF_8))
                }
            }
            offset += packetLen
        }
    }

    private fun parseMessage(text: String) {
        // 只处理弹幕和 SC，其余消息（进场、礼物等量很大）跳过 json 解析
        if (!text.contains("\"DANMU_MSG") && !text.contains("\"SUPER_CHAT_MESSAGE\"")) return
        try {
            val obj = parseJson(text)
            val cmd = obj["cmd"].str
            if (cmd.startsWith("DANMU_MSG")) {
                val info = obj["info"]
                val message = info[1].str
                val userName = info[2][1].str
                val color = info[0][3].long ?: 0
                emit(
                    LiveMessage(
                        type = LiveMessageType.CHAT,
                        userName = userName,
                        message = message,
                        color = LiveMessageColor.fromRgb(color),
                    ),
                )
            } else if (cmd == "SUPER_CHAT_MESSAGE") {
                val data = obj["data"] ?: return
                val sc = LiveSuperChatMessage(
                    userName = data["user_info"]["uname"].str,
                    face = "${data["user_info"]["face"].str}@200w.jpg",
                    message = data["message"].str,
                    price = data["price"].int ?: 0,
                    startTime = (data["start_time"].long ?: 0) * 1000,
                    endTime = (data["end_time"].long ?: 0) * 1000,
                    backgroundColor = data["background_color"].str,
                    backgroundBottomColor = data["background_bottom_color"].str,
                )
                emit(LiveMessage(LiveMessageType.SUPER_CHAT, sc.userName, sc.message, data = sc))
            }
        } catch (e: Exception) {
            CoreLog.e("B站弹幕解析失败", e)
        }
    }

    companion object {
        private const val OP_HEARTBEAT = 2
        private const val OP_HEARTBEAT_REPLY = 3
        private const val OP_MESSAGE = 5
        private const val OP_AUTH = 7
    }
}
