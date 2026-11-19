package com.xycz.simplelive.core.danmaku

import com.xycz.simplelive.core.common.CoreLog
import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.common.get
import com.xycz.simplelive.core.common.parseJson
import com.xycz.simplelive.core.common.str
import com.xycz.simplelive.core.model.LiveMessage
import com.xycz.simplelive.core.model.LiveMessageColor
import com.xycz.simplelive.core.model.LiveMessageType
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class KickDanmakuArgs(val channelId: String, val chatroomId: String)

/**
 * Kick 弹幕走 Pusher WebSocket：订阅 chatrooms.<chatroomId>.v2，消息是 JSON 文本。
 * Pusher 的 app key 由 /v1/realtime/channels/<channelId>/chat/connection 下发，取不到时用内置的。
 */
class KickDanmaku : WebSocketDanmaku() {
    override val heartbeatInterval = 60_000L
    private var args: KickDanmakuArgs? = null

    override fun start(args: Any?) {
        val a = args as? KickDanmakuArgs ?: return
        this.args = a
        scope.launch {
            val (key, cluster) = fetchPusherApp(a.channelId)
            connect("wss://ws-$cluster.pusher.com/app/$key?protocol=7&client=js&version=8.4.0&flash=false")
        }
    }

    private suspend fun fetchPusherApp(channelId: String): Pair<String, String> {
        try {
            val body = """{"client":{"id":"${java.util.UUID.randomUUID()}","type":"web"},""" +
                """"capabilities":{"accepted_providers":[{"provider":"pusher"}]}}"""
            val text = Http.request(
                "https://web.kick.com/api/v1/realtime/channels/$channelId/chat/connection",
                method = "POST",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                body = body.toRequestBody("application/json".toMediaType()),
            ).text
            val conns = parseJson(text)["data"]["connections"]
            val cred = (conns as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull { it["provider"].str == "pusher" }?.get("credentials")
            val key = cred["app_key"].str
            val cluster = cred["cluster"].str
            if (key.isNotEmpty() && cluster.isNotEmpty()) return key to cluster
        } catch (e: Exception) {
            CoreLog.e("获取 Kick 弹幕配置失败，使用内置配置", e)
        }
        return DEFAULT_KEY to DEFAULT_CLUSTER
    }

    override fun onSocketOpen() {
        val chatroomId = args?.chatroomId ?: return
        sendText("""{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroomId.v2"}}""")
    }

    override fun heartbeat() {
        sendText("""{"event":"pusher:ping","data":{}}""")
    }

    override fun onBinary(data: ByteArray) {
        val msg = parseJson(String(data, Charsets.UTF_8))
        if (msg["event"].str != "App\\Events\\ChatMessageEvent") return
        // data 是再套一层的 JSON 字符串
        val chat = parseJson(msg["data"].str)
        val content = cleanEmotes(chat["content"].str)
        if (content.isEmpty()) return
        emit(
            LiveMessage(
                type = LiveMessageType.CHAT,
                userName = chat["sender"]["username"].str,
                message = content,
                color = parseColor(chat["sender"]["identity"]["color"].str),
            ),
        )
    }

    companion object {
        private const val DEFAULT_KEY = "34bf7a0ff419a2a775b9"
        private const val DEFAULT_CLUSTER = "us2"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val EMOTE = Regex("""\[emote:\d+:([^\]]*)]""")

        /** 表情 [emote:37226:KEKW] 显示成名字 */
        fun cleanEmotes(text: String): String = EMOTE.replace(text) { it.groupValues[1] }.trim()

        private fun parseColor(hex: String): LiveMessageColor {
            val v = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16) ?: return LiveMessageColor.WHITE
            return LiveMessageColor.fromRgb(v)
        }
    }
}
