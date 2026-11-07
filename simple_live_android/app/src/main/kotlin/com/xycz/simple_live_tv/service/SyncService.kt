package com.xycz.simple_live_tv.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xycz.simple_live_tv.App
import com.xycz.simple_live_tv.BuildConfig
import com.xycz.simple_live_tv.data.FollowUser
import com.xycz.simple_live_tv.data.HistoryItem
import com.xycz.simple_live_tv.data.Settings
import com.xycz.simple_live_tv.data.Store
import com.xycz.simple_live_tv.data.SyncRecord
import com.xycz.simple_live_tv.ui.Nav
import com.xycz.simplelive.core.common.JsonCodec
import com.xycz.simplelive.core.common.arr
import com.xycz.simplelive.core.common.get
import com.xycz.simplelive.core.common.long
import com.xycz.simplelive.core.common.parseJson
import com.xycz.simplelive.core.common.str
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
data class GossipPayload(val nodeId: String, val name: String, val records: List<SyncRecord>)

/** 局域网里发现的其他设备 */
data class Peer(val id: String, val name: String, val ip: String, var lastSeen: Long, var lastSync: Long = 0)

/**
 * 局域网同步：
 * - HTTP 23234：兼容旧版的 /info、/sync/xxx（手机端推送）、/open（手机发直播间链接），以及 gossip 交换接口
 * - UDP 23235：设备发现 + gossip 广播（摘要不同就全量交换记录，按 updatedAt 新者胜合并）
 */
object SyncService {
    private const val TAG = "SyncService"
    const val HTTP_PORT = 23234
    const val UDP_PORT = 23235
    private const val ANNOUNCE_INTERVAL = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var server: Server? = null
    private var udp: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    val peers = ConcurrentHashMap<String, Peer>()
    private val http = com.xycz.simplelive.core.common.Http.client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    var running = false
        private set

    private val announce = object : Runnable {
        override fun run() {
            scope.launch { broadcast(gossipMessage()) }
            main.postDelayed(this, ANNOUNCE_INTERVAL)
        }
    }

    fun start(context: Context) {
        try {
            server = Server().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            running = true
        } catch (e: Exception) {
            Log.e(TAG, "HTTP 服务启动失败", e)
        }
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("simple_live_sync").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取 MulticastLock 失败", e)
        }
        scope.launch { listenUdp() }
        main.postDelayed(announce, 3000)
        // 本地数据变化后尽快通知其他设备（合并 2 秒内的多次变化）
        Store.listen {
            main.removeCallbacks(announceSoon)
            main.postDelayed(announceSoon, 2000)
        }
    }

    private val announceSoon = Runnable { scope.launch { broadcast(gossipMessage()) } }

    /** 手机扫码打开的发送链接页面地址 */
    fun openPageUrl(): String? = if (running) localIp()?.let { "http://$it:$HTTP_PORT/open" } else null

    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    // ---------------- UDP：发现 + gossip ----------------

    private fun gossipMessage(): String = buildJsonObject {
        put("type", "gossip")
        put("id", Settings.nodeId)
        put("name", deviceName())
        put("port", HTTP_PORT)
        put("digest", Store.digest())
    }.toString()

    private fun infoMessage(): String = buildJsonObject {
        put("id", Settings.nodeId)
        put("type", "tv")
        put("name", deviceName())
    }.toString()

    private fun broadcast(message: String) {
        if (!Settings.syncEnable && message.contains("\"gossip\"")) return
        try {
            val bytes = message.toByteArray()
            udp?.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), UDP_PORT))
        } catch (e: Exception) {
            Log.w(TAG, "UDP 广播失败: ${e.message}")
        }
    }

    private fun listenUdp() {
        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(java.net.InetSocketAddress(UDP_PORT))
            }
            udp = socket
            val buf = ByteArray(4096)
            while (true) {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length)
                val ip = packet.address.hostAddress ?: continue
                handleUdp(text, ip)
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP 监听失败", e)
        }
    }

    private fun handleUdp(text: String, ip: String) {
        if (text == "Who is SimpleLive?") {
            broadcast(infoMessage())
            return
        }
        if (!text.startsWith("{")) return
        val json = runCatching { parseJson(text) }.getOrNull() ?: return
        when (json["type"].str) {
            "hello" -> broadcast(infoMessage())
            "gossip" -> {
                val id = json["id"].str
                if (id.isEmpty() || id == Settings.nodeId) return
                val peer = peers.getOrPut(id) { Peer(id, json["name"].str, ip, 0) }
                peer.lastSeen = System.currentTimeMillis()
                if (Settings.syncEnable && json["digest"].str != Store.digest()) {
                    scope.launch { exchange(ip, json["port"].long?.toInt() ?: HTTP_PORT, peer) }
                }
            }
        }
    }

    /** 与对端交换全部记录 */
    private fun exchange(ip: String, port: Int, peer: Peer?) {
        try {
            val payload = GossipPayload(Settings.nodeId, deviceName(), Store.allRecords())
            val body = JsonCodec.encodeToString(GossipPayload.serializer(), payload)
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("http://$ip:$port/gossip/exchange").post(body).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val remote = JsonCodec.decodeFromString(GossipPayload.serializer(), resp.body!!.string())
                Store.merge(remote.records)
                peer?.lastSync = System.currentTimeMillis()
                Log.i(TAG, "与 ${remote.name}($ip) 同步完成")
            }
        } catch (e: Exception) {
            Log.w(TAG, "与 $ip 同步失败: ${e.message}")
        }
    }

    /** 立即向所有已知设备同步 */
    fun syncNow() {
        scope.launch {
            broadcast(gossipMessage())
            peers.values.forEach { exchange(it.ip, HTTP_PORT, it) }
        }
    }

    // ---------------- HTTP ----------------

    private class Server : NanoHTTPD(HTTP_PORT) {
        override fun serve(session: IHTTPSession): Response = try {
            route(session)
        } catch (e: Exception) {
            Log.e(TAG, "处理请求失败 ${session.uri}", e)
            json(false, e.message ?: "error")
        }

        private fun body(session: IHTTPSession): String {
            val files = HashMap<String, String>()
            session.parseBody(files)
            return files["postData"] ?: ""
        }

        private fun json(status: Boolean, message: String, extra: Map<String, Any> = emptyMap()): Response {
            val obj = buildJsonObject {
                put("status", status)
                put("message", message)
                extra.forEach { (k, v) ->
                    when (v) {
                        is Number -> put(k, JsonPrimitive(v))
                        else -> put(k, v.toString())
                    }
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", obj.toString())
        }

        private fun route(session: IHTTPSession): Response {
            val overlay = session.parameters["overlay"]?.firstOrNull() == "1"
            return when (session.uri) {
                "/" -> json(true, "http server is running...", mapOf("version" to "SimpleLive android v${BuildConfig.VERSION_NAME}"))
                "/info" -> json(
                    true,
                    "ok",
                    mapOf(
                        "id" to Settings.nodeId,
                        "type" to "tv",
                        "name" to deviceName(),
                        "version" to BuildConfig.VERSION_NAME,
                        "address" to (localIp() ?: ""),
                        "port" to HTTP_PORT,
                    ),
                )
                "/gossip/exchange" -> {
                    val payload = JsonCodec.decodeFromString(GossipPayload.serializer(), body(session))
                    // 先取本地快照再合并，返回给对端的是合并前的本地记录（对端会再合并）
                    val local = Store.allRecords()
                    Store.merge(payload.records)
                    peers[payload.nodeId]?.lastSync = System.currentTimeMillis()
                    val resp = GossipPayload(Settings.nodeId, deviceName(), local)
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json; charset=utf-8",
                        JsonCodec.encodeToString(GossipPayload.serializer(), resp),
                    )
                }
                "/sync/follow" -> {
                    val list = parseJson(body(session)).arr
                    val now = System.currentTimeMillis()
                    val records = mutableListOf<SyncRecord>()
                    if (overlay) Store.follows().forEach { records += SyncRecord(Store.TYPE_FOLLOW, it.id, null, now, true) }
                    list.forEach {
                        val user = FollowUser(
                            id = it["id"].str,
                            roomId = it["roomId"].str,
                            siteId = it["siteId"].str,
                            userName = it["userName"].str,
                            face = it["face"].str,
                            addTime = parseDartTime(it["addTime"].str) ?: now,
                        )
                        records += Store.followRecord(user, now + 1)
                    }
                    Store.merge(records)
                    json(true, "已同步 ${list.size} 个关注")
                }
                "/sync/history" -> {
                    val list = parseJson(body(session)).arr
                    if (overlay) Store.clearHistory()
                    val records = list.map {
                        Store.historyRecord(
                            HistoryItem(
                                id = it["id"].str,
                                roomId = it["roomId"].str,
                                siteId = it["siteId"].str,
                                userName = it["userName"].str,
                                face = it["face"].str,
                                updateTime = parseDartTime(it["updateTime"].str) ?: 0,
                            ),
                        )
                    }
                    Store.merge(records)
                    json(true, "已同步 ${list.size} 条观看记录")
                }
                "/sync/blocked_word" -> {
                    val list = parseJson(body(session)).arr.map { it.str.trim() }.filter { it.isNotEmpty() }
                    val now = System.currentTimeMillis()
                    val records = mutableListOf<SyncRecord>()
                    if (overlay) Store.shieldWords().forEach { records += SyncRecord(Store.TYPE_SHIELD, it, null, now, true) }
                    list.forEach { records += Store.shieldRecord(it, now + 1) }
                    Store.merge(records)
                    json(true, "已同步 ${list.size} 个屏蔽词")
                }
                "/sync/account/bilibili" -> {
                    val cookie = parseJson(body(session))["cookie"].str
                    Store.setAccountCookie("bilibili", cookie)
                    json(true, "已同步哔哩哔哩账号")
                }
                "/open" -> if (session.method == Method.POST) {
                    val text = body(session).let { raw ->
                        raw.split("&").firstOrNull { it.startsWith("url=") }
                            ?.substringAfter("url=")
                            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                            ?.trim()
                    } ?: session.parameters["url"]?.firstOrNull()?.trim() ?: ""
                    if (text.isEmpty()) {
                        openPage("请粘贴直播间链接")
                    } else {
                        val error = runBlocking { withContext(Dispatchers.IO) { Nav.openDouyinUrl(App.instance, text) } }
                        openPage(error ?: "已在电视上打开")
                    }
                } else {
                    openPage("")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        }

        private fun openPage(message: String): Response {
            val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val html = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Simple Live TV</title>
<style>
body{font-family:sans-serif;margin:24px;background:#111;color:#eee}
textarea{width:100%;height:120px;font-size:16px;box-sizing:border-box;padding:8px}
button{width:100%;margin-top:12px;padding:14px;font-size:18px;border:0;border-radius:8px;background:#fe2c55;color:#fff}
p.msg{margin-top:16px;font-size:16px}
</style>
</head>
<body>
<h3>发送直播间到电视</h3>
<form method="post" action="/open">
<textarea name="url" placeholder="粘贴抖音直播间链接或 App 分享的文案"></textarea>
<button type="submit">在电视上打开</button>
</form>
<p class="msg">$escaped</p>
</body>
</html>"""
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }

        /** 解析 Dart DateTime.toString()：2024-01-02 03:04:05.678 */
        private fun parseDartTime(text: String): Long? = try {
            val m = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})""").find(text) ?: return null
            val v = m.groupValues.drop(1).map { it.toInt() }
            java.util.Calendar.getInstance().apply {
                clear()
                set(v[0], v[1] - 1, v[2], v[3], v[4], v[5])
            }.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("unused")
    private fun JsonElement.asText() = str
}
