package com.xycz.simplelive.core.site

import com.xycz.simplelive.core.common.CoreLog
import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.common.Js
import com.xycz.simplelive.core.common.arr
import com.xycz.simplelive.core.common.extractJsonObject
import com.xycz.simplelive.core.common.get
import com.xycz.simplelive.core.common.int
import com.xycz.simplelive.core.common.long
import com.xycz.simplelive.core.common.obj
import com.xycz.simplelive.core.common.parseJson
import com.xycz.simplelive.core.common.pickCookies
import com.xycz.simplelive.core.common.randomDigits
import com.xycz.simplelive.core.common.randomString
import com.xycz.simplelive.core.common.str
import com.xycz.simplelive.core.danmaku.DouyinDanmaku
import com.xycz.simplelive.core.danmaku.DouyinDanmakuArgs
import com.xycz.simplelive.core.danmaku.LiveDanmaku
import com.xycz.simplelive.core.model.LiveAnchorItem
import com.xycz.simplelive.core.model.LiveCategory
import com.xycz.simplelive.core.model.LivePlayQuality
import com.xycz.simplelive.core.model.LivePlayUrl
import com.xycz.simplelive.core.model.LiveRoomDetail
import com.xycz.simplelive.core.model.LiveRoomItem
import com.xycz.simplelive.core.model.LiveSubCategory
import com.xycz.simplelive.core.model.PageResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/** 抖音播放数据：stream_url 原始结构 + 解析后的 stream_data */
class DouyinPlayData(val streamUrl: JsonElement?, val streamData: JsonElement?)

class DouyinSite : LiveSite {
    override val id = "douyin"
    override val name = "抖音直播"

    /** 用户设置的 cookie，为空时用默认 ttwid */
    @Volatile
    var cookie: String = ""

    override fun getDanmaku(): LiveDanmaku = DouyinDanmaku()

    private fun requestHeaders(referer: String = REFERER): Map<String, String> = mapOf(
        "Authority" to AUTHORITY,
        "Referer" to referer,
        "User-Agent" to USER_AGENT,
        "cookie" to cookie.ifEmpty { DEFAULT_COOKIE },
    )

    /** 给 URL 加上 msToken 和 a_bogus 签名 */
    private fun signUrl(url: String): String {
        val msToken = randomString(107)
        val withToken = "$url&msToken=$msToken"
        val query = withToken.substringAfter('?')
        val aBogus = Js.engine.evaluate(
            listOf(Js.resource("douyin-abogus.js")),
            "getABogus(${Js.quote(query)}, ${Js.quote(USER_AGENT)})",
        )
        return "$url&msToken=${enc(msToken)}&a_bogus=${enc(aBogus)}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    override suspend fun getCategories(): List<LiveCategory> {
        val html = Http.getText("https://live.douyin.com/", headers = requestHeaders())
        val json = parseJson(
            extractRenderObject(html, "{\\\"pathname\\\":\\\"/\\\",\\\"categoryData")
                ?: throw IllegalStateException("获取抖音分类失败"),
        )
        return json["categoryData"].arr.map { item ->
            val id = "${item["partition"]["id_str"].str},${item["partition"]["type"].str}"
            val name = item["partition"]["title"].str
            val subs = mutableListOf(LiveSubCategory(id = id, name = name, parentId = id, pic = ""))
            item["sub_partition"].arr.mapTo(subs) { sub ->
                LiveSubCategory(
                    id = "${sub["partition"]["id_str"].str},${sub["partition"]["type"].str}",
                    name = sub["partition"]["title"].str,
                    parentId = id,
                    pic = "",
                )
            }
            LiveCategory(id = id, name = name, children = subs)
        }
    }

    /**
     * 页面里的数据是转义过的 JSON（\" 和 \\），从 [marker] 处开始反转义并截出完整对象
     */
    private fun extractRenderObject(html: String, marker: String): String? {
        val start = html.indexOf(marker)
        if (start < 0) return null
        val end = html.indexOf("\"])</script>", start).let { if (it < 0) html.length else it }
        val unescaped = html.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\")
        return extractJsonObject(unescaped, 0)
    }

    override suspend fun getCategoryRooms(category: LiveSubCategory, page: Int): PageResult<LiveRoomItem> {
        val (partition, type) = category.id.split(",").let { it[0] to it.getOrElse(1) { "1" } }
        return getPartitionRooms(partition, type, page)
    }

    override suspend fun getRecommendRooms(page: Int): PageResult<LiveRoomItem> = getPartitionRooms("720", "1", page)

    private suspend fun getPartitionRooms(partition: String, type: String, page: Int): PageResult<LiveRoomItem> {
        val query = linkedMapOf(
            "aid" to "6383",
            "app_name" to "douyin_web",
            "live_id" to "1",
            "device_platform" to "web",
            "language" to "zh-CN",
            "enter_from" to "link_share",
            "cookie_enabled" to "true",
            "screen_width" to "1980",
            "screen_height" to "1080",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Edge",
            "browser_version" to "125.0.0.0",
            "browser_online" to "true",
            "count" to "15",
            "offset" to ((page - 1) * 15).toString(),
            "partition" to partition,
            "partition_type" to type,
            "req_from" to "2",
        )
        val url = Http.buildUrl("https://live.douyin.com/webcast/web/partition/detail/room/v2/", query)
        val result = parseJson(Http.getText(signUrl(url), headers = requestHeaders()))
        val list = result["data"]["data"].arr
        val items = list.map { item ->
            LiveRoomItem(
                roomId = item["web_rid"].str,
                title = item["room"]["title"].str,
                cover = item["room"]["cover"]["url_list"][0].str,
                userName = item["room"]["owner"]["nickname"].str,
                online = item["room"]["room_view_stats"]["display_value"].long ?: 0,
            )
        }
        return PageResult(list.size >= 15, items)
    }

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        // 两种房间号：webRid 固定（11-12 位）；roomId 每次开播都变（19 位）
        if (roomId.length <= 16) return getRoomDetailByWebRid(roomId)
        return getRoomDetailByRoomId(roomId)
    }

    private suspend fun getRoomDetailByRoomId(roomId: String): LiveRoomDetail {
        val result = Http.getJson(
            "https://webcast.amemv.com/webcast/room/reflow/info/",
            mapOf(
                "type_id" to 0,
                "live_id" to 1,
                "room_id" to roomId,
                "sec_user_id" to "",
                "version_code" to "99.99.99",
                "app_id" to 6383,
            ),
            requestHeaders(),
        )
        val room = result["data"]["room"]
        val webRid = room["owner"]["web_rid"].str
        val status = room["status"].int ?: 0
        // roomId 对应的场次已结束，改用 webRid 查当前状态
        if (status == 4 && webRid.isNotEmpty()) return getRoomDetailByWebRid(webRid)
        val live = status == 2
        val owner = room["owner"]
        return LiveRoomDetail(
            roomId = webRid,
            title = room["title"].str,
            cover = if (live) room["cover"]["url_list"][0].str else "",
            userName = owner["nickname"].str,
            userAvatar = owner["avatar_thumb"]["url_list"][0].str,
            online = if (live) room["room_view_stats"]["display_value"].long ?: 0 else 0,
            status = live,
            url = "https://live.douyin.com/$webRid",
            introduction = owner["signature"].str,
            notice = "",
            danmakuData = DouyinDanmakuArgs(webRid, roomId, randomDigits(12), cookie.ifEmpty { DEFAULT_COOKIE }),
            data = DouyinPlayData(room["stream_url"], parseStreamData(room["stream_url"], null)),
        )
    }

    private suspend fun getRoomDetailByWebRid(webRid: String): LiveRoomDetail = try {
        getRoomDetailByWebRidApi(webRid)
    } catch (e: Exception) {
        CoreLog.e("抖音直播间接口失败，改用网页解析", e)
        getRoomDetailByWebRidHtml(webRid)
    }

    private suspend fun getRoomDetailByWebRidApi(webRid: String): LiveRoomDetail {
        val query = linkedMapOf(
            "aid" to "6383",
            "app_name" to "douyin_web",
            "live_id" to "1",
            "device_platform" to "web",
            "language" to "zh-CN",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Chrome",
            "browser_version" to "125.0.0.0",
            "web_rid" to webRid,
        )
        // 原版这里先放了空的 msToken 再签名追加一个，重复参数会导致接口返回空，退回网页解析后丢失"原画"
        val url = Http.buildUrl("https://live.douyin.com/webcast/room/web/enter/", query)
        val text = Http.getText(signUrl(url), headers = requestHeaders("https://live.douyin.com/$webRid"))
        if (text.isBlank()) throw IllegalStateException("抖音接口返回为空")
        val data = parseJson(text)["data"]
        val room = data["data"][0] ?: throw IllegalStateException("抖音接口没有直播间数据")
        val user = data["user"]
        val live = room["status"].int == 2
        val owner = room["owner"]
        return LiveRoomDetail(
            roomId = webRid,
            title = room["title"].str,
            cover = if (live) room["cover"]["url_list"][0].str else "",
            userName = if (live) owner["nickname"].str else user["nickname"].str,
            userAvatar = if (live) owner["avatar_thumb"]["url_list"][0].str else user["avatar_thumb"]["url_list"][0].str,
            online = if (live) room["room_view_stats"]["display_value"].long ?: 0 else 0,
            status = live,
            url = "https://live.douyin.com/$webRid",
            introduction = owner["signature"].str,
            notice = "",
            danmakuData = DouyinDanmakuArgs(webRid, room["id_str"].str, randomDigits(12), cookie.ifEmpty { DEFAULT_COOKIE }),
            data = if (live) DouyinPlayData(room["stream_url"], parseStreamData(room["stream_url"], null)) else null,
        )
    }

    private suspend fun getRoomDetailByWebRidHtml(webRid: String): LiveRoomDetail {
        val head = Http.head("https://live.douyin.com/$webRid", headers = requestHeaders())
        val dyCookie = head.headers.pickCookies("ttwid", "__ac_nonce", "msToken")
        val html = Http.getText(
            "https://live.douyin.com/$webRid",
            headers = mapOf(
                "Authority" to AUTHORITY,
                "Referer" to REFERER,
                "Cookie" to dyCookie,
                "User-Agent" to USER_AGENT,
            ),
        )
        val state = parseJson(
            extractRenderObject(html, "{\\\"state\\\":{\\\"appStore")
                ?: throw IllegalStateException("解析抖音直播间页面失败"),
        )["state"]
        val roomInfo = state["roomStore"]["roomInfo"]
        val room = roomInfo["room"]
        val anchor = roomInfo["anchor"]
        val live = room["status"].int == 2
        val owner = room["owner"]
        return LiveRoomDetail(
            roomId = webRid,
            title = room["title"].str,
            cover = if (live) room["cover"]["url_list"][0].str else "",
            userName = if (live) owner["nickname"].str else anchor["nickname"].str,
            userAvatar = if (live) owner["avatar_thumb"]["url_list"][0].str else anchor["avatar_thumb"]["url_list"][0].str,
            online = if (live) room["room_view_stats"]["display_value"].long ?: 0 else 0,
            status = live,
            url = "https://live.douyin.com/$webRid",
            introduction = owner["signature"].str,
            notice = "",
            danmakuData = DouyinDanmakuArgs(
                webRid,
                room["id_str"].str,
                state["userStore"]["odin"]["user_unique_id"].str.ifEmpty { randomDigits(12) },
                cookie.ifEmpty { DEFAULT_COOKIE },
            ),
            data = if (live) DouyinPlayData(room["stream_url"], parseStreamData(room["stream_url"], html)) else null,
        )
    }

    /**
     * 取出 stream_data（各档位的播放地址）。
     * 网页里的 stream_data 可能是 "$13" 这样的引用，真正内容在页面后面的 `13:T长度,` 分块里
     */
    private fun parseStreamData(streamUrl: JsonElement?, html: String?): JsonElement? {
        val raw = streamUrl["live_core_sdk_data"]["pull_data"]["stream_data"].str
        try {
            if (raw.startsWith("{")) return parseJson(raw)["data"]
            if (raw.startsWith("$") && html != null) {
                val ref = Regex.escape(raw.substring(1))
                val match = Regex(
                    """\\n$ref:T[0-9a-f]+,"\]\)</script><script[^>]*>self\.__pace_f\.push\(\[1,"(.*?)"\]\)</script>""",
                ).find(html) ?: return null
                val text = (parseJson("\"${match.groupValues[1]}\"") as JsonPrimitive).content
                return parseJson(text)["data"]
            }
        } catch (e: Exception) {
            CoreLog.e("解析抖音 stream_data 失败", e)
        }
        return null
    }

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val data = detail.data as? DouyinPlayData ?: return emptyList()
        val qualities = data.streamUrl["live_core_sdk_data"]["pull_data"]["options"]["qualities"].arr
        val result = mutableListOf<LivePlayQuality>()
        val streamData = data.streamData
        if (streamData != null) {
            for (q in qualities) {
                val main = streamData[q["sdk_key"].str]["main"]
                // H.265 的流 FLV 播不了（ExoPlayer 的 FLV 解析只支持 H.264），把 HLS 放前面
                val vCodec = runCatching { parseJson(main["sdk_params"].str)["VCodec"].str }.getOrDefault("")
                val urls = (if (vCodec.contains("265")) listOf(main["hls"].str, main["flv"].str)
                else listOf(main["flv"].str, main["hls"].str)).filter { it.isNotEmpty() }
                if (urls.isNotEmpty()) result += LivePlayQuality(q["name"].str, urls, q["level"].int ?: 0)
            }
        }
        if (result.isEmpty()) {
            // 没有 stream_data 时退回旧字段：flv_pull_url 的值按清晰度从高到低排列
            val flv = data.streamUrl["flv_pull_url"].obj?.values?.map { it.str } ?: emptyList()
            val hls = data.streamUrl["hls_pull_url_map"].obj?.values?.map { it.str } ?: emptyList()
            for (q in qualities) {
                val level = q["level"].int ?: 0
                val urls = listOfNotNull(flv.getOrNull(flv.size - level), hls.getOrNull(hls.size - level))
                if (urls.isNotEmpty()) result += LivePlayQuality(q["name"].str, urls, level)
            }
        }
        return result.sortedByDescending { it.sort }
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        @Suppress("UNCHECKED_CAST")
        return LivePlayUrl(urls = (quality.data as List<String>).toList())
    }

    // ---------------- 搜索：Web 搜索接口必须登录，改为按链接/房间号直接查 ----------------

    override suspend fun searchRooms(keyword: String, page: Int): PageResult<LiveRoomItem> {
        if (page > 1) return PageResult(false, emptyList())
        val detail = getRoomDetail(parseKeywordRoomId(keyword))
        return PageResult(
            false,
            listOf(LiveRoomItem(detail.roomId, detail.title, detail.cover, detail.userName, detail.online)),
        )
    }

    override suspend fun searchAnchors(keyword: String, page: Int): PageResult<LiveAnchorItem> {
        if (page > 1) return PageResult(false, emptyList())
        val detail = getRoomDetail(parseKeywordRoomId(keyword))
        return PageResult(false, listOf(LiveAnchorItem(detail.roomId, detail.userAvatar, detail.userName, detail.status)))
    }

    private suspend fun parseKeywordRoomId(keyword: String): String {
        val text = keyword.trim()
        if (text.matches(Regex("^\\d+$"))) return text
        return parseRoomIdFromUrl(text) ?: throw IllegalStateException("抖音搜索需要登录，请输入直播间链接或房间号")
    }

    /** 从直播间链接或 App 分享文案中解析房间号，解析不到返回 null */
    suspend fun parseRoomIdFromUrl(input: String): String? {
        ROOM_URL_REGEXES.firstNotNullOfOrNull { it.find(input)?.groupValues?.get(1) }?.let { return it }
        val shortUrl = SHORT_URL_REGEX.find(input)?.value ?: return null
        val resp = Http.head(shortUrl, headers = mapOf("User-Agent" to USER_AGENT), followRedirects = false)
        val location = resp.headers["location"] ?: return null
        return ROOM_URL_REGEXES.firstNotNullOfOrNull { it.find(location)?.groupValues?.get(1) }
    }

    override suspend fun getLiveStatus(roomId: String): Boolean = getRoomDetail(roomId).status

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400"
        private const val REFERER = "https://live.douyin.com"
        private const val AUTHORITY = "live.douyin.com"

        /** 只需要 ttwid 即可获取所有画质 */
        const val DEFAULT_COOKIE =
            "ttwid=1%7CB1qls3GdnZhUov9o2NxOMxxYS2ff6OSvEWbv0ytbES4%7C1680522049%7C280d802d6d478e3e78d0c807f7c487e7ffec0ae4e5fdd6a0fe74c3c6af149511"

        private val ROOM_URL_REGEXES = listOf(
            Regex("""live\.douyin\.com/(\d+)"""),
            Regex("""douyin\.com/(?:follow|root)/live/(\d+)"""),
            Regex("""webcast/reflow/(\d+)"""),
        )
        private val SHORT_URL_REGEX = Regex("""https?://v\.douyin\.com/[A-Za-z0-9_\-]+/?""")
    }
}
