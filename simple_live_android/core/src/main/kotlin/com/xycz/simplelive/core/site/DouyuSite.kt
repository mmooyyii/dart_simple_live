package com.xycz.simplelive.core.site

import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.common.Js
import com.xycz.simplelive.core.common.arr
import com.xycz.simplelive.core.common.get
import com.xycz.simplelive.core.common.int
import com.xycz.simplelive.core.common.long
import com.xycz.simplelive.core.common.parseJson
import com.xycz.simplelive.core.common.str
import com.xycz.simplelive.core.danmaku.DouyuDanmaku
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

data class DouyuPlayData(val rate: Int, val cdns: List<String>)

class DouyuSite : LiveSite {
    override val id = "douyu"
    override val name = "斗鱼直播"

    /** 登录后的 cookie（不登录时斗鱼网页端最高只给"高清"） */
    @Volatile
    var cookie: String = ""

    override fun getDanmaku(): LiveDanmaku = DouyuDanmaku()

    private fun headers(roomId: String): Map<String, String> {
        val h = mutableMapOf(
            "referer" to "https://www.douyu.com/$roomId",
            "user-agent" to USER_AGENT,
        )
        if (cookie.isNotEmpty()) h["cookie"] = cookie
        return h
    }

    override suspend fun getCategories(): List<LiveCategory> {
        val result = Http.getJson("https://m.douyu.com/api/cate/list")
        val subs = result["data"]["cate2Info"].arr
        return result["data"]["cate1Info"].arr.map { item ->
            val cate1Id = item["cate1Id"].str
            LiveCategory(
                id = cate1Id,
                name = item["cate1Name"].str,
                children = subs.filter { it["cate1Id"].str == cate1Id }.map {
                    LiveSubCategory(
                        id = it["cate2Id"].str,
                        name = it["cate2Name"].str,
                        parentId = cate1Id,
                        pic = it["icon"].str,
                    )
                },
            )
        }.sortedBy { it.id.toIntOrNull() ?: 0 }
    }

    override suspend fun getCategoryRooms(category: LiveSubCategory, page: Int): PageResult<LiveRoomItem> =
        parseRoomList(Http.getJson("https://www.douyu.com/gapi/rkc/directory/mixList/2_${category.id}/$page"), page)

    override suspend fun getRecommendRooms(page: Int): PageResult<LiveRoomItem> =
        parseRoomList(Http.getJson("https://www.douyu.com/japi/weblist/apinc/allpage/6/$page"), page)

    private fun parseRoomList(result: JsonElement, page: Int): PageResult<LiveRoomItem> {
        val items = result["data"]["rl"].arr.filter { it["type"].int == 1 }.map { item ->
            LiveRoomItem(
                roomId = item["rid"].str,
                title = item["rn"].str,
                cover = item["rs16"].str,
                userName = item["nn"].str,
                online = item["ol"].long ?: 0,
            )
        }
        return PageResult(page < (result["data"]["pgcnt"].int ?: 0), items)
    }

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        val room = getRoomInfo(roomId)
        val realRoomId = room["room_id"].str
        val showTime = try {
            Http.getJson("https://www.douyu.com/swf_api/h5room/$roomId", headers = headers(roomId))["data"]["show_time"].str
        } catch (e: Exception) {
            ""
        }
        val encText = Http.getText("https://www.douyu.com/swf_api/homeH5Enc?rids=$roomId", headers = headers(roomId))
        val crptext = parseJson(encText)["data"]["room$roomId"].str
        val videoLoop = room["videoLoop"].int == 1
        return LiveRoomDetail(
            roomId = realRoomId,
            title = room["room_name"].str,
            cover = room["room_pic"].str,
            userName = room["owner_name"].str,
            userAvatar = room["owner_avatar"].str,
            online = room["room_biz_all"]["hot"].long ?: 0,
            introduction = room["show_details"].str,
            notice = "",
            status = room["show_status"].int == 1 && !videoLoop,
            data = getSign(crptext, realRoomId),
            danmakuData = realRoomId,
            url = "https://www.douyu.com/$roomId",
            isRecord = videoLoop,
            showTime = showTime.takeIf { it.isNotEmpty() },
        )
    }

    private suspend fun getRoomInfo(roomId: String): JsonElement {
        val text = Http.getText("https://www.douyu.com/betard/$roomId", headers = headers(roomId))
        return parseJson(text)["room"] ?: throw IllegalStateException("获取斗鱼直播间信息失败")
    }

    /** 执行斗鱼下发的签名脚本，得到 v/did/tt/sign 参数串 */
    private fun getSign(crptext: String, roomId: String): String {
        val time = System.currentTimeMillis() / 1000
        return Js.engine.evaluate(
            listOf(Js.resource("crypto-js.min.js"), crptext),
            "ub98484234('$roomId','$DID','$time')",
        )
    }

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val body = "${detail.data}&cdn=&rate=-1&ver=Douyu_223061205&iar=1&ive=1&hevc=${hevc()}&fa=0"
        val result = parseJson(
            Http.postForm("https://www.douyu.com/lapi/live/getH5Play/${detail.roomId}", body, headers(detail.roomId)),
        )
        if (result["error"].int != 0) throw IllegalStateException(result["msg"].str.ifEmpty { "获取斗鱼清晰度失败" })
        val cdns = result["data"]["cdnsWithName"].arr.map { it["cdn"].str }
            .sortedBy { if (it.startsWith("scdn")) 1 else 0 }
        return result["data"]["multirates"].arr.map {
            LivePlayQuality(quality = it["name"].str, data = DouyuPlayData(it["rate"].int ?: 0, cdns))
        }
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val data = quality.data as DouyuPlayData
        val urls = mutableListOf<String>()
        var actualRate: Int? = null
        for (cdn in data.cdns) {
            val body = "${detail.data}&cdn=$cdn&rate=${data.rate}&ver=Douyu_223061205&iar=1&ive=1&hevc=${hevc()}&fa=0"
            val result = parseJson(
                Http.postForm("https://www.douyu.com/lapi/live/getH5Play/${detail.roomId}", body, headers(detail.roomId)),
            )
            if (result["error"].int != 0) continue
            actualRate = result["data"]["rate"].int
            val live = htmlUnescape(result["data"]["rtmp_live"].str)
            if (live.isNotEmpty()) urls.add("${result["data"]["rtmp_url"].str}/$live")
        }
        // 未登录时斗鱼会把高码率悄悄降级，提示用户
        val notice = if (actualRate != null && actualRate != data.rate) {
            "该清晰度需要登录斗鱼，当前为较低画质"
        } else {
            null
        }
        return LivePlayUrl(urls = urls, notice = notice)
    }

    // 斗鱼只给 FLV，ExoPlayer 的 FLV 解析不支持 H.265，所以固定要 H.264
    private fun hevc() = 0

    override suspend fun searchRooms(keyword: String, page: Int): PageResult<LiveRoomItem> {
        val result = Http.getJson(
            "https://www.douyu.com/japi/search/api/searchShow",
            mapOf("kw" to keyword, "page" to page, "pageSize" to 20),
            searchHeaders(),
        )
        if (result["error"].int != 0) throw IllegalStateException(result["msg"].str)
        val items = result["data"]["relateShow"].arr.map { item ->
            LiveRoomItem(
                roomId = item["rid"].str,
                title = item["roomName"].str,
                cover = item["roomSrc"].str,
                userName = item["nickName"].str,
                online = parseHotNum(item["hot"].str),
            )
        }
        return PageResult(items.isNotEmpty(), items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): PageResult<LiveAnchorItem> {
        val result = Http.getJson(
            "https://www.douyu.com/japi/search/api/searchUser",
            mapOf("kw" to keyword, "page" to page, "pageSize" to 20, "filterType" to 1),
            searchHeaders(),
        )
        if (result["error"].int != 0) throw IllegalStateException(result["msg"].str)
        val items = result["data"]["relateUser"].arr.map { item ->
            val info = item["anchorInfo"]
            LiveAnchorItem(
                roomId = info["rid"].str,
                avatar = info["avatar"].str,
                userName = info["nickName"].str,
                liveStatus = info["isLive"].int == 1 && info["roomType"].int == 0,
            )
        }
        return PageResult(items.isNotEmpty(), items)
    }

    private fun searchHeaders(): Map<String, String> {
        val did = (1..32).joinToString("") { "0123456789abcdef".random().toString() }
        return mapOf(
            "User-Agent" to USER_AGENT,
            "referer" to "https://www.douyu.com/search/",
            "Cookie" to "dy_did=$did;acf_did=$did",
        )
    }

    override suspend fun getLiveStatus(roomId: String): Boolean {
        val room = getRoomInfo(roomId)
        return room["show_status"].int == 1 && room["videoLoop"].int != 1
    }

    private fun parseHotNum(text: String): Long = try {
        val num = text.replace("万", "").toDouble()
        (if (text.contains("万")) num * 10000 else num).toLong()
    } catch (e: Exception) {
        0
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.43"
        private const val DID = "10000000000000000000000000001501"

        fun htmlUnescape(text: String): String = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }
}
