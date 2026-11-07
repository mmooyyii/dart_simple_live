package com.xycz.simplelive.core.danmaku

import com.xycz.simplelive.core.model.LiveMessage
import com.xycz.simplelive.core.model.LiveMessageColor
import com.xycz.simplelive.core.model.LiveMessageType
import com.xycz.simplelive.core.tars.TarsStruct
import com.xycz.simplelive.core.tars.TarsWriter
import java.util.Base64

data class HuyaDanmakuArgs(val ayyuid: Long, val topSid: Long, val subSid: Long)

class HuyaDanmaku : WebSocketDanmaku() {
    override val heartbeatInterval = 60_000L
    private lateinit var args: HuyaDanmakuArgs

    override fun start(args: Any?) {
        this.args = args as HuyaDanmakuArgs
        connect("wss://cdnws.api.huya.com")
    }

    override fun onSocketOpen() {
        // WSUserInfo（平铺写入，不带 STRUCT_BEGIN）
        val userInfo = TarsWriter()
            .long(args.ayyuid, 0) // lUid
            .bool(true, 1) // bAnonymous
            .string("", 2) // sGuid
            .string("", 3) // sToken
            .long(args.topSid, 4) // lTid
            .long(args.subSid, 5) // lSid（原版误传 topSid）
            .long(0, 6) // lGroupId
            .long(0, 7) // lGroupType
            .toByteArray()
        val command = TarsWriter()
            .long(1, 0) // iCmdType = EWSCmd_RegisterReq
            .bytes(userInfo, 1)
            .toByteArray()
        send(command)
    }

    override fun heartbeat() {
        send(HEARTBEAT)
    }

    override fun onBinary(data: ByteArray) {
        val command = TarsStruct.decode(data)
        if (command.int(0) != 7) return // 只处理推送消息
        val push = TarsStruct.decode(command.bytes(1))
        val uri = push.int(1)
        val msg = push.bytes(2)
        when (uri) {
            1400 -> {
                val message = TarsStruct.decode(msg)
                val sender = message.struct(0)
                val format = message.struct(6)
                val fontColor = format.long(0, -1)
                emit(
                    LiveMessage(
                        type = LiveMessageType.CHAT,
                        userName = sender.string(2),
                        message = message.string(3),
                        color = if (fontColor <= 0) LiveMessageColor.WHITE else LiveMessageColor.fromRgb(fontColor),
                    ),
                )
            }
            8006 -> {
                val online = TarsStruct.decode(msg).long(0)
                emit(LiveMessage(LiveMessageType.ONLINE, data = online))
            }
        }
    }

    companion object {
        private val HEARTBEAT = Base64.getDecoder().decode("ABQdAAwsNgBM")
    }
}
