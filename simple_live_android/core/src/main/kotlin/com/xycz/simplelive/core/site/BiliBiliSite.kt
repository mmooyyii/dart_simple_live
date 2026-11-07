package com.xycz.simplelive.core.site

import com.xycz.simplelive.core.common.CoreLog
import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.common.arr
import com.xycz.simplelive.core.common.get
import com.xycz.simplelive.core.common.int
import com.xycz.simplelive.core.common.long
import com.xycz.simplelive.core.common.md5Hex
import com.xycz.simplelive.core.common.str
import com.xycz.simplelive.core.danmaku.BiliBiliDanmaku
import com.xycz.simplelive.core.danmaku.BiliBiliDanmakuArgs
import com.xycz.simplelive.core.danmaku.LiveDanmaku
import com.xycz.simplelive.core.model.LiveAnchorItem
import com.xycz.simplelive.core.model.LiveCategory
import com.xycz.simplelive.core.model.LivePlayQuality
import com.xycz.simplelive.core.model.LivePlayUrl
import com.xycz.simplelive.core.model.LiveRoomDetail
import com.xycz.simplelive.core.model.LiveRoomItem
import com.xycz.simplelive.core.model.LiveSubCategory
import com.xycz.simplelive.core.model.LiveSuperChatMessage
import com.xycz.simplelive.core.model.PageResult
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder

class BiliBiliSite : LiveSite {
    override val id = "bilibili"
    override val name = "哔哩哔哩直播"

    /** 登录后的 cookie，由 App 设置 */
    @Volatile
    var cookie: String = ""

    @Volatile
    var userId: Long = 0

    private var buvid3 = ""
    private var buvid4 = ""
    private var accessId = ""

    override fun getDanmaku(): LiveDanmaku = BiliBiliDanmaku()

    suspend fun getHeader(): Map<String, String> {
        if (buvid3.isEmpty()) {
            val (b3, b4) = getBuvid()
            buvid3 = b3
            buvid4 = b4
        }
        val cookieValue = if (cookie.isEmpty()) {
            "buvid3=$buvid3;buvid4=$buvid4;"
        } else if (cookie.contains("buvid3")) {
            cookie
        } else {
            "$cookie;buvid3=$buvid3;buvid4=$buvid4;"
        }
        return mapOf(
            "user-agent" to USER_AGENT,
            "referer" to REFERER,
            "cookie" to cookieValue,
        )
    }

    override suspend fun getCategories(): List<LiveCategory> {
        val result = Http.getJson(
            "https://api.live.bilibili.com/room/v1/Area/getList",
            query = mapOf("need_entrance" to 1, "parent_id" to 0),
            headers = getHeader(),
        )
        return result["data"].arr.map { item ->
            LiveCategory(
                id = item["id"].str,
                name = item["name"].str,
                children = item["list"].arr.map { sub ->
                    LiveSubCategory(
                        id = sub["id"].str,
                        name = sub["name"].str,
                        parentId = sub["parent_id"].str,
                        pic = "${sub["pic"].str}@100w.png",
                    )
                },
            )
        }
    }

    override suspend fun getCategoryRooms(category: LiveSubCategory, page: Int): PageResult<LiveRoomItem> {
        val baseUrl = "https://api.live.bilibili.com/xlive/web-interface/v1/second/getList"
        val query = wbiSign(
            linkedMapOf(
                "platform" to "web",
                "parent_area_id" to category.parentId,
                "area_id" to category.id,
                "sort_type" to "",
                "page" to page.toString(),
                "w_webid" to getAccessId(),
            ),
        )
        val result = Http.getJson(baseUrl, query, getHeader())
        val items = result["data"]["list"].arr.map(::toRoomItem)
        return PageResult(result["data"]["has_more"].int == 1, items)
    }

    override suspend fun getRecommendRooms(page: Int): PageResult<LiveRoomItem> {
        val baseUrl = "https://api.live.bilibili.com/xlive/web-interface/v1/second/getListByArea"
        val query = wbiSign(
            linkedMapOf(
                "platform" to "web",
                "sort" to "online",
                "page_size" to "30",
                "page" to page.toString(),
            ),
        )
        val result = Http.getJson(baseUrl, query, getHeader())
        val items = result["data"]["list"].arr.map(::toRoomItem)
        return PageResult(items.isNotEmpty(), items)
    }

    private fun toRoomItem(item: JsonElement) = LiveRoomItem(
        roomId = item["roomid"].str,
        title = item["title"].str,
        cover = "${item["cover"].str}@400w.jpg",
        userName = item["uname"].str,
        online = item["online"].long ?: 0,
    )

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val result = Http.getJson(
            PLAY_INFO_URL,
            mapOf(
                "room_id" to detail.roomId,
                "protocol" to "0,1",
                "format" to "0,1,2",
                "codec" to "0,1",
                "platform" to "web",
            ),
            getHeader(),
        )
        val playurl = result["data"]["playurl_info"]["playurl"]
        val names = playurl["g_qn_desc"].arr.associate { (it["qn"].int ?: 0) to it["desc"].str }
        // 各编码可选清晰度取并集（原版只看第一个编码，H.265 独有的高画质会丢）
        val qns = linkedSetOf<Int>()
        for (stream in playurl["stream"].arr) {
            for (format in stream["format"].arr) {
                for (codec in format["codec"].arr) {
                    if (codec["codec_name"].str == "hevc" && !DeviceCapability.supportHevc) continue
                    codec["accept_qn"].arr.mapNotNullTo(qns) { it.int }
                }
            }
        }
        return qns.sortedDescending().map { qn ->
            LivePlayQuality(quality = names[qn] ?: "未知清晰度", data = qn, sort = qn)
        }
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val qn = quality.data as Int
        val result = Http.getJson(
            PLAY_INFO_URL,
            mapOf(
                "room_id" to detail.roomId,
                "protocol" to "0,1",
                "format" to "0,1,2",
                "codec" to if (DeviceCapability.supportHevc) "0,1" else "0",
                "platform" to "web",
                "qn" to qn,
            ),
            getHeader(),
        )
        // 优先取清晰度与请求一致的地址；H.264 在前（兼容性更好）
        val exact = mutableListOf<Pair<Int, String>>()
        val others = mutableListOf<String>()
        for (stream in result["data"]["playurl_info"]["playurl"]["stream"].arr) {
            for (format in stream["format"].arr) {
                for (codec in format["codec"].arr) {
                    val isHevc = codec["codec_name"].str == "hevc"
                    if (isHevc && !DeviceCapability.supportHevc) continue
                    // ExoPlayer 的 FLV 解析只支持 H.264，H.265 只能走 fmp4/ts
                    if (isHevc && format["format_name"].str == "flv") continue
                    val baseUrl = codec["base_url"].str
                    val urls = codec["url_info"].arr.map { "${it["host"].str}$baseUrl${it["extra"].str}" }
                    if (codec["current_qn"].int == qn) {
                        val formatRank = when (format["format_name"].str) {
                            "flv" -> 0
                            "fmp4" -> 1
                            else -> 2
                        }
                        val rank = (if (isHevc) 10 else 0) + formatRank
                        urls.forEach { exact.add(rank to it) }
                    } else {
                        others.addAll(urls)
                    }
                }
            }
        }
        val urls = exact.sortedBy { it.first }.map { it.second }.ifEmpty { others }
        // mcdn 线路放后面
        val sorted = urls.filterNot { it.contains("mcdn") } + urls.filter { it.contains("mcdn") }
        return LivePlayUrl(
            urls = sorted,
            headers = mapOf(
                "referer" to "https://live.bilibili.com",
                "user-agent" to PLAY_USER_AGENT,
            ),
        )
    }

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        val roomInfo = getRoomInfo(roomId)
        val realRoomId = roomInfo["room_info"]["room_id"].str
        val danmuInfo = Http.getJson(
            "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo",
            wbiSign(linkedMapOf("id" to realRoomId)),
            getHeader(),
        )
        val hosts = danmuInfo["data"]["host_list"].arr.map { it["host"].str }
        val liveStartTime = roomInfo["room_info"]["live_start_time"].str
        return LiveRoomDetail(
            roomId = realRoomId,
            title = roomInfo["room_info"]["title"].str,
            cover = roomInfo["room_info"]["cover"].str,
            userName = roomInfo["anchor_info"]["base_info"]["uname"].str,
            userAvatar = "${roomInfo["anchor_info"]["base_info"]["face"].str}@100w.jpg",
            online = roomInfo["room_info"]["online"].long ?: 0,
            status = roomInfo["room_info"]["live_status"].int == 1,
            url = "https://live.bilibili.com/$roomId",
            introduction = roomInfo["room_info"]["description"].str,
            notice = "",
            danmakuData = BiliBiliDanmakuArgs(
                roomId = realRoomId.toLongOrNull() ?: 0,
                uid = userId,
                token = danmuInfo["data"]["token"].str,
                serverHost = hosts.firstOrNull() ?: "broadcastlv.chat.bilibili.com",
                buvid = buvid3,
                cookie = cookie,
            ),
            showTime = liveStartTime.takeIf { it.isNotEmpty() && it != "0" },
        )
    }

    private suspend fun getRoomInfo(roomId: String): JsonElement {
        val result = Http.getJson(
            "https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom",
            wbiSign(linkedMapOf("room_id" to roomId)),
            getHeader(),
        )
        return result["data"] ?: throw IllegalStateException("获取直播间信息失败：${result["message"].str}")
    }

    override suspend fun searchRooms(keyword: String, page: Int): PageResult<LiveRoomItem> {
        val result = search(keyword, page, "live")
        val items = result["data"]["result"]["live_room"].arr.map { item ->
            LiveRoomItem(
                roomId = item["roomid"].str,
                title = item["title"].str.replace(EM_TAG, ""),
                cover = "https:${item["cover"].str}@400w.jpg",
                userName = item["uname"].str,
                online = item["online"].long ?: 0,
            )
        }
        return PageResult(items.size >= 40, items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): PageResult<LiveAnchorItem> {
        val result = search(keyword, page, "live_user")
        val items = result["data"]["result"].arr.map { item ->
            LiveAnchorItem(
                roomId = item["roomid"].str,
                avatar = "https:${item["uface"].str}@400w.jpg",
                userName = item["uname"].str.replace(EM_TAG, ""),
                liveStatus = item["is_live"].str == "true" || item["is_live"].int == 1,
            )
        }
        return PageResult(items.size >= 40, items)
    }

    private suspend fun search(keyword: String, page: Int, type: String): JsonElement = Http.getJson(
        "https://api.bilibili.com/x/web-interface/search/type",
        linkedMapOf(
            "context" to "",
            "search_type" to type,
            "cover_type" to "user_cover",
            "order" to "",
            "keyword" to keyword,
            "category_id" to "",
            "__refresh__" to "",
            "_extra" to "",
            "highlight" to 0,
            "single_column" to 0,
            "page" to page,
        ),
        getHeader(),
    )

    override suspend fun getLiveStatus(roomId: String): Boolean {
        val result = Http.getJson(
            "https://api.live.bilibili.com/room/v1/Room/get_info",
            mapOf("room_id" to roomId),
            getHeader(),
        )
        return result["data"]["live_status"].int == 1
    }

    override suspend fun getSuperChatMessages(roomId: String): List<LiveSuperChatMessage> {
        val result = Http.getJson(
            "https://api.live.bilibili.com/av/v1/SuperChat/getMessageList",
            mapOf("room_id" to roomId),
            getHeader(),
        )
        return result["data"]["list"].arr.map { item ->
            LiveSuperChatMessage(
                userName = item["user_info"]["uname"].str,
                face = "${item["user_info"]["face"].str}@200w.jpg",
                message = item["message"].str,
                price = item["price"].int ?: 0,
                startTime = (item["start_time"].long ?: 0) * 1000,
                endTime = (item["end_time"].long ?: 0) * 1000,
                backgroundColor = item["background_color"].str,
                backgroundBottomColor = item["background_bottom_color"].str,
            )
        }
    }

    private suspend fun getBuvid(): Pair<String, String> {
        if (cookie.contains("buvid3")) {
            return (Regex("buvid3=(.*?);").find(cookie)?.groupValues?.get(1) ?: "") to
                (Regex("buvid4=(.*?);").find(cookie)?.groupValues?.get(1) ?: "")
        }
        return try {
            val result = Http.getJson(
                "https://api.bilibili.com/x/frontend/finger/spi",
                headers = mapOf("user-agent" to USER_AGENT, "referer" to REFERER, "cookie" to cookie),
            )
            result["data"]["b_3"].str to result["data"]["b_4"].str
        } catch (e: Exception) {
            CoreLog.e("获取 buvid 失败", e)
            "" to ""
        }
    }

    private suspend fun getAccessId(): String {
        if (accessId.isNotEmpty()) return accessId
        val html = Http.getText("https://live.bilibili.com/lol", headers = getHeader())
        accessId = Regex("\"access_id\":\"(.*?)\"").find(html)?.groupValues?.get(1)?.replace("\\", "") ?: ""
        return accessId
    }

    // ---------------- WBI 签名 ----------------

    private suspend fun getWbiKeys(): Pair<String, String> {
        if (imgKey.isNotEmpty() && subKey.isNotEmpty()) return imgKey to subKey
        val resp = Http.getJson("https://api.bilibili.com/x/web-interface/nav", headers = getHeader())
        fun keyOf(url: String) = url.substringAfterLast('/').substringBefore('.')
        imgKey = keyOf(resp["data"]["wbi_img"]["img_url"].str)
        subKey = keyOf(resp["data"]["wbi_img"]["sub_url"].str)
        return imgKey to subKey
    }

    /** 为参数做 WBI 签名，返回原始参数 + wts + w_rid */
    suspend fun wbiSign(params: Map<String, String>): Map<String, String> {
        val (img, sub) = getWbiKeys()
        val mixinKey = mixinKey(img + sub)
        val result = LinkedHashMap(params)
        result["wts"] = (System.currentTimeMillis() / 1000).toString()
        val query = result.keys.sorted().joinToString("&") { key ->
            val value = result[key]!!.filterNot { it in "!'()*" }
            "$key=${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}"
        }
        result["w_rid"] = md5Hex(query + mixinKey)
        return result
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"
        const val REFERER = "https://live.bilibili.com/"
        private const val PLAY_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36 Edg/115.0.1901.188"
        private const val PLAY_INFO_URL = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
        private val EM_TAG = Regex("<.*?em.*?>")

        @Volatile
        private var imgKey = ""

        @Volatile
        private var subKey = ""

        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52,
        )

        fun mixinKey(origin: String): String =
            MIXIN_KEY_ENC_TAB.joinToString("") { origin[it].toString() }.substring(0, 32)
    }
}
