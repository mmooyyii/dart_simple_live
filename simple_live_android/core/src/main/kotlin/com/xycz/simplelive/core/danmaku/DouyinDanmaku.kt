package com.xycz.simplelive.core.danmaku

import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.common.Js
import com.xycz.simplelive.core.common.md5Hex
import com.xycz.simplelive.core.model.LiveMessage
import com.xycz.simplelive.core.model.LiveMessageType
import com.xycz.simplelive.core.site.DouyinSite
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

data class DouyinDanmakuArgs(
    val webRid: String,
    val roomId: String,
    val userId: String,
    val cookie: String,
)

class DouyinDanmaku : WebSocketDanmaku() {
    override val heartbeatInterval = 10_000L

    override fun start(args: Any?) {
        val a = args as DouyinDanmakuArgs
        // 签名要执行 600KB 的 JS，放到后台线程
        scope.launch {
            val url = buildUrl(a)
            connect(
                url,
                headers = mapOf(
                    "User-Agent" to DouyinSite.USER_AGENT,
                    "Cookie" to a.cookie,
                    "Origin" to "https://live.douyin.com",
                ),
                backupUrl = url.replace("webcast3-ws-web-lq", "webcast5-ws-web-lf"),
            )
        }
    }

    private fun buildUrl(a: DouyinDanmakuArgs): String {
        val ua = DouyinSite.USER_AGENT
        val query = linkedMapOf(
            "app_name" to "douyin_web",
            "version_code" to "180800",
            "webcast_sdk_version" to "1.3.0",
            "update_version_code" to "1.3.0",
            "compress" to "gzip",
            "cursor" to "h-1_t-${System.currentTimeMillis()}_r-1_d-1_u-1",
            "host" to "https://live.douyin.com",
            "aid" to "6383",
            "live_id" to "1",
            "did_rule" to "3",
            "debug" to "false",
            "maxCacheMessageNumber" to "20",
            "endpoint" to "live_pc",
            "support_wrds" to "1",
            "im_path" to "/webcast/im/fetch/",
            "user_unique_id" to a.userId,
            "device_platform" to "web",
            "cookie_enabled" to "true",
            "screen_width" to "1920",
            "screen_height" to "1080",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Mozilla",
            "browser_version" to ua.replace("Mozilla/", ""),
            "browser_online" to "true",
            "tz_name" to "Asia/Shanghai",
            "identity" to "audience",
            "room_id" to a.roomId,
            "heartbeatDuration" to "0",
        )
        val base = Http.buildUrl("https://webcast3-ws-web-lq.douyin.com/webcast/im/push/v2/", query)
        return "$base&signature=${signature(a.roomId, a.userId)}"
    }

    private fun signature(roomId: String, userId: String): String {
        val stub = md5Hex(
            "live_id=1,aid=6383,version_code=180800,webcast_sdk_version=1.3.0,room_id=$roomId," +
                "sub_room_id=,sub_channel_id=,did_rule=3,user_unique_id=$userId,device_platform=web," +
                "device_type=,ac=,identity=audience",
        )
        val script = Js.resource("douyin-webmssdk.js")
        repeat(5) {
            val sign = Js.engine.evaluate(listOf(script), "getMSSDKSignature('$stub', ${Js.quote(DouyinSite.USER_AGENT)})")
            // 含 - 或 = 的签名会被拒绝，重新生成
            if (!sign.contains('-') && !sign.contains('=')) return sign
        }
        return Js.engine.evaluate(listOf(script), "getMSSDKSignature('$stub', ${Js.quote(DouyinSite.USER_AGENT)})")
    }

    override fun onSocketOpen() {
        heartbeat()
    }

    override fun heartbeat() {
        send(ProtoWriter().string(7, "hb").toByteArray())
    }

    override fun onBinary(data: ByteArray) {
        val frame = ProtoMessage.decode(data)
        val payload = GZIPInputStream(ByteArrayInputStream(frame.bytes(8))).readBytes()
        val response = ProtoMessage.decode(payload)
        if (response.bool(9)) {
            // needAck：回 ack 帧
            send(
                ProtoWriter()
                    .long(2, frame.long(2))
                    .string(7, "ack")
                    .bytes(8, response.bytes(5))
                    .toByteArray(),
            )
        }
        for (item in response.repeatedBytes(1)) {
            val message = ProtoMessage.decode(item)
            when (message.string(1)) {
                "WebcastChatMessage" -> {
                    val chat = message.message(2)
                    emit(
                        LiveMessage(
                            type = LiveMessageType.CHAT,
                            userName = chat.message(2).string(3),
                            message = chat.string(3),
                        ),
                    )
                }
                "WebcastRoomUserSeqMessage" -> {
                    val seq = message.message(2)
                    emit(LiveMessage(LiveMessageType.ONLINE, data = seq.long(7)))
                }
            }
        }
    }
}
