package com.xycz.simplelive.core.danmaku

import com.xycz.simplelive.core.common.CoreLog
import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.model.LiveMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * WebSocket 弹幕基类：连接、心跳、断线重连（间隔 5 秒，最多 5 次，第一次失败换备用地址）
 */
abstract class WebSocketDanmaku : LiveDanmaku {
    override var onMessage: ((LiveMessage) -> Unit)? = null
    override var onClose: ((String) -> Unit)? = null
    override var onReady: (() -> Unit)? = null

    /** 心跳间隔（毫秒） */
    protected abstract val heartbeatInterval: Long

    private val client = Http.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var stopped = false
    private var reconnectCount = 0
    private var url = ""
    private var backupUrl: String? = null
    private var headers: Map<String, String> = emptyMap()

    protected fun connect(url: String, headers: Map<String, String> = emptyMap(), backupUrl: String? = null) {
        this.url = url
        this.headers = headers
        this.backupUrl = backupUrl
        open(url)
    }

    private fun open(target: String) {
        if (stopped) return
        val builder = Request.Builder().url(target)
        headers.forEach { (k, v) -> builder.header(k, v) }
        socket = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket != socket) return
                reconnectCount = 0
                onSocketOpen()
                onReady?.invoke()
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket != socket) return
                try {
                    onBinary(bytes.toByteArray())
                } catch (e: Exception) {
                    CoreLog.e("弹幕解析失败", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket != socket) return
                try {
                    onBinary(text.toByteArray())
                } catch (e: Exception) {
                    CoreLog.e("弹幕解析失败", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != socket) return
                handleDisconnect("连接已关闭")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != socket) return
                CoreLog.e("弹幕连接失败 $target", t)
                handleDisconnect(t.message ?: "连接失败")
            }
        })
    }

    private fun handleDisconnect(reason: String) {
        heartbeatJob?.cancel()
        if (stopped) return
        if (reconnectCount >= 5) {
            onClose?.invoke("弹幕服务器重连超过最大次数：$reason")
            return
        }
        reconnectCount++
        onClose?.invoke("与弹幕服务器断开连接，正在尝试重连")
        scope.launch {
            delay(if (reconnectCount == 1) 0 else 5000)
            // 第一次重连用备用地址
            val target = if (reconnectCount == 1 && backupUrl != null) backupUrl!! else url
            open(target)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatInterval)
                heartbeat()
            }
        }
    }

    protected fun send(bytes: ByteArray) {
        socket?.send(bytes.toByteString())
    }

    /** 连接建立后（onReady 之前）发送加入房间的包 */
    protected open fun onSocketOpen() {}

    protected abstract fun heartbeat()

    protected abstract fun onBinary(data: ByteArray)

    protected fun emit(msg: LiveMessage) {
        onMessage?.invoke(msg)
    }

    override fun stop() {
        stopped = true
        onMessage = null
        onClose = null
        onReady = null
        heartbeatJob?.cancel()
        socket?.close(1000, null)
        socket = null
        scope.cancel()
    }
}
