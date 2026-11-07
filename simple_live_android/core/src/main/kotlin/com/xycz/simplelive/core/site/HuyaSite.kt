package com.xycz.simplelive.core.site

import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.common.arr
import com.xycz.simplelive.core.common.get
import com.xycz.simplelive.core.common.int
import com.xycz.simplelive.core.common.long
import com.xycz.simplelive.core.common.md5Hex
import com.xycz.simplelive.core.common.parseJson
import com.xycz.simplelive.core.common.str
import com.xycz.simplelive.core.common.strOrNull
import com.xycz.simplelive.core.danmaku.HuyaDanmaku
import com.xycz.simplelive.core.danmaku.HuyaDanmakuArgs
import com.xycz.simplelive.core.danmaku.LiveDanmaku
import com.xycz.simplelive.core.model.LiveAnchorItem
import com.xycz.simplelive.core.model.LiveCategory
import com.xycz.simplelive.core.model.LivePlayQuality
import com.xycz.simplelive.core.model.LivePlayUrl
import com.xycz.simplelive.core.model.LiveRoomDetail
import com.xycz.simplelive.core.model.LiveRoomItem
import com.xycz.simplelive.core.model.LiveSubCategory
import com.xycz.simplelive.core.model.PageResult
import com.xycz.simplelive.core.tars.TarsStruct
import com.xycz.simplelive.core.tars.TarsWriter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.random.Random

data class HuyaLine(
    val line: String,
    val flvAntiCode: String,
    val hlsAntiCode: String,
    val streamName: String,
    val cdnType: String,
    val presenterUid: Long,
)

data class HuyaBitRate(val name: String, val bitRate: Int)

data class HuyaUrlData(
    val url: String,
    val lines: List<HuyaLine>,
    val bitRates: List<HuyaBitRate>,
)

data class HuyaQualityData(val lines: List<HuyaLine>, val bitRate: Int)

class HuyaSite : LiveSite {
    override val id = "huya"
    override val name = "虎牙直播"

    override fun getDanmaku(): LiveDanmaku = HuyaDanmaku()

    override suspend fun getCategories(): List<LiveCategory> =
        listOf("1" to "网游", "2" to "单机", "8" to "娱乐", "3" to "手游").map { (id, name) ->
            LiveCategory(id = id, name = name, children = getSubCategories(id))
        }

    private suspend fun getSubCategories(id: String): List<LiveSubCategory> {
        val result = Http.getJson(
            "https://live.cdn.huya.com/liveconfig/game/bussLive",
            mapOf("bussType" to id),
        )
        return result["data"].arr.map { item ->
            val gidElement = item["gid"]
            val gid = when {
                gidElement is JsonObject -> gidElement["value"].str.split(",").first()
                gidElement is JsonPrimitive -> gidElement.long?.toString() ?: gidElement.str
                else -> gidElement.str
            }
            LiveSubCategory(
                id = gid,
                name = item["gameFullName"].str,
                parentId = id,
                pic = "https://huyaimg.msstatic.com/cdnimage/game/$gid-MS.jpg",
            )
        }
    }

    override suspend fun getCategoryRooms(category: LiveSubCategory, page: Int): PageResult<LiveRoomItem> =
        getLiveList(mapOf("gameId" to category.id), page)

    override suspend fun getRecommendRooms(page: Int): PageResult<LiveRoomItem> = getLiveList(emptyMap(), page)

    private suspend fun getLiveList(extra: Map<String, Any?>, page: Int): PageResult<LiveRoomItem> {
        val query = linkedMapOf<String, Any?>("m" to "LiveList", "do" to "getLiveListByPage", "tagAll" to 0)
        query.putAll(extra)
        query["page"] = page
        val result = parseJson(Http.getText("https://www.huya.com/cache.php", query))
        val items = result["data"]["datas"].arr.map { item ->
            LiveRoomItem(
                roomId = item["profileRoom"].str,
                title = item["introduction"].str.ifEmpty { item["roomName"].str },
                cover = coverUrl(item["screenshot"].str),
                userName = item["nick"].str,
                online = item["totalCount"].long ?: 0,
            )
        }
        val hasMore = (result["data"]["page"].int ?: 0) < (result["data"]["totalPage"].int ?: 0)
        return PageResult(hasMore, items)
    }

    private fun coverUrl(cover: String) = if (cover.contains("?")) cover else "$cover?x-oss-process=style/w338_h190&"

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        val info = getRoomInfo(roomId)
        val json = info.json
        val liveInfo = json["roomInfo"]["tLiveInfo"]
        val profile = json["roomInfo"]["tProfileInfo"]
        val lines = liveInfo["tLiveStreamInfo"]["vStreamInfo"]["value"].arr
            .filter { it["sFlvUrl"].str.isNotEmpty() }
            .map {
                HuyaLine(
                    line = it["sFlvUrl"].str,
                    flvAntiCode = it["sFlvAntiCode"].str,
                    hlsAntiCode = it["sHlsAntiCode"].str,
                    streamName = it["sStreamName"].str,
                    cdnType = it["sCdnType"].str,
                    presenterUid = info.topSid,
                )
            }
        val bitRates = liveInfo["tLiveStreamInfo"]["vBitRateInfo"]["value"].arr
            .filterNot { it["sDisplayName"].str.contains("HDR") }
            .map { HuyaBitRate(it["sDisplayName"].str, it["iBitRate"].int ?: 0) }
        val liveLineUrl = json["roomProfile"]["liveLineUrl"].str
        return LiveRoomDetail(
            roomId = liveInfo["lProfileRoom"].str,
            title = liveInfo["sIntroduction"].str.ifEmpty { liveInfo["sRoomName"].str },
            cover = liveInfo["sScreenshot"].str,
            userName = profile["sNick"].str,
            userAvatar = profile["sAvatar180"].str,
            online = liveInfo["lTotalCount"].long ?: 0,
            introduction = liveInfo["sIntroduction"].str,
            notice = json["welcomeText"].str,
            status = json["roomInfo"]["eLiveStatus"].int == 2,
            data = HuyaUrlData(
                url = if (liveLineUrl.isEmpty()) "" else "https:" + String(Base64.getDecoder().decode(liveLineUrl)),
                lines = lines,
                bitRates = bitRates,
            ),
            danmakuData = HuyaDanmakuArgs(
                ayyuid = liveInfo["lYyid"].long ?: 0,
                topSid = info.topSid,
                subSid = info.subSid,
            ),
            url = "https://www.huya.com/$roomId",
        )
    }

    private class RoomInfo(val json: JsonElement, val topSid: Long, val subSid: Long)

    private suspend fun getRoomInfo(roomId: String): RoomInfo {
        val html = Http.getText("https://m.huya.com/$roomId", headers = mapOf("user-agent" to MOBILE_USER_AGENT))
        val text = Regex("window\\.HNF_GLOBAL_INIT.=.\\{[\\s\\S]*?\\}[\\s\\S]*?</script>").find(html)?.value
            ?: throw IllegalStateException("获取虎牙直播间信息失败")
        val jsonText = text
            .replace(Regex("window\\.HNF_GLOBAL_INIT.=."), "")
            .replace("</script>", "")
            .replace(Regex("function.*?\\(.*?\\).\\{[\\s\\S]*?\\}"), "\"\"")
        val topSid = Regex("lChannelId\":([0-9]+)").find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val subSid = Regex("lSubChannelId\":([0-9]+)").find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return RoomInfo(parseJson(jsonText), topSid, subSid)
    }

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val data = detail.data as HuyaUrlData
        val bitRates = data.bitRates.ifEmpty { listOf(HuyaBitRate("原画", 0), HuyaBitRate("高清", 2000)) }
        return bitRates.map { LivePlayQuality(quality = it.name, data = HuyaQualityData(data.lines, it.bitRate)) }
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val data = quality.data as HuyaQualityData
        val urls = data.lines.map { line ->
            val token = getCdnTokenInfoEx(line.streamName)
            val antiCode = buildAntiCode(line.streamName, line.presenterUid, token)
            var url = "${line.line}/${line.streamName}.flv?$antiCode&codec=264"
            if (data.bitRate > 0) url += "&ratio=${data.bitRate}"
            url
        }
        return LivePlayUrl(urls = urls, headers = mapOf("user-agent" to HYSDK_UA))
    }

    /** TUP 请求 liveui.getCdnTokenInfoEx，返回 sFlvToken */
    private suspend fun getCdnTokenInfoEx(stream: String): String {
        val req = TarsWriter().struct(0) {
            string("", 0) // sFlvUrl
            string(stream, 1) // sStreamName
            long(0, 2) // iLoopTime
            struct(3) { // tId: HuyaUserId
                long(0, 0)
                string("", 1)
                string("", 2)
                string("pc_exe&7060000&official", 3)
                string("", 4)
                long(0, 5)
                string("", 6)
                string("", 7)
            }
            long(66, 4) // iAppId
        }.toByteArray()
        val sBuffer = TarsWriter().bytesMap(mapOf("tReq" to req), 0).toByteArray()
        val body = TarsWriter()
            .long(3, 1) // iVersion = TUP3
            .long(0, 2)
            .long(0, 3)
            .long(0, 4)
            .string("liveui", 5)
            .string("getCdnTokenInfoEx", 6)
            .bytes(sBuffer, 7)
            .long(0, 8)
            .stringMap(emptyMap(), 9)
            .stringMap(emptyMap(), 10)
            .toByteArray()
        val packet = ByteBuffer.allocate(body.size + 4).putInt(body.size + 4).put(body).array()
        val resp = Http.postBytes(
            "http://wup.huya.com",
            packet,
            "application/x-wup",
            mapOf("Origin" to BASE_URL, "Referer" to BASE_URL, "User-Agent" to HYSDK_UA),
        )
        val respPacket = TarsStruct.decode(resp, 4)
        val data = TarsStruct.decode(respPacket.bytes(7)).map(0)
        val rsp = data["tRsp"] as? ByteArray ?: throw IllegalStateException("虎牙 CDN token 获取失败")
        return TarsStruct.decode(rsp).struct(0).string(0)
    }

    /** 构造播放地址的 anticode（wsSecret 等参数） */
    fun buildAntiCode(stream: String, presenterUid: Long, antiCode: String): String {
        val params = antiCode.split("&").filter { it.contains("=") }.associate {
            val idx = it.indexOf('=')
            it.substring(0, idx) to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }
        val fmRaw = params["fm"] ?: return antiCode
        val ctype = params["ctype"] ?: "huya_pc_exe"
        val platformId = params["t"]?.toIntOrNull() ?: 0
        val isWap = platformId == 103
        val seqId = presenterUid + System.currentTimeMillis()
        val secretHash = md5Hex("$seqId|$ctype|$platformId")
        val convertUid = rotl64(presenterUid)
        val calcUid = if (isWap) presenterUid else convertUid
        val fm = URLDecoder.decode(fmRaw.replace("+", "%2B"), "UTF-8")
        val secretPrefix = String(Base64.getDecoder().decode(fm)).split("_").first()
        val wsTime = params["wsTime"] ?: ""
        val wsSecret = md5Hex("${secretPrefix}_${calcUid}_${stream}_${secretHash}_$wsTime")
        val result = linkedMapOf<String, Any>(
            "wsSecret" to wsSecret,
            "wsTime" to wsTime,
            "seqid" to seqId,
            "ctype" to ctype,
            "ver" to "1",
            "fs" to (params["fs"] ?: ""),
            "fm" to encodeComponent(fmRaw),
            "t" to platformId,
        )
        if (isWap) {
            val ct = ((wsTime.toLong(16) + Random.nextDouble()) * 1000).toLong()
            val uuid = (((ct % 1e10) + Random.nextDouble()) * 1e3 % 0xffffffffL).toLong()
            result["uid"] = presenterUid
            result["uuid"] = uuid
        } else {
            result["u"] = convertUid
        }
        return result.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    private fun rotl64(t: Long): Long {
        val low = t and 0xFFFFFFFFL
        val rotatedLow = ((low shl 8) or (low ushr 24)) and 0xFFFFFFFFL
        val high = t and 0xFFFFFFFFL.inv()
        return high or rotatedLow
    }

    /** 等同 JS encodeURIComponent */
    private fun encodeComponent(text: String) = URLEncoder.encode(text, "UTF-8")
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~")

    override suspend fun searchRooms(keyword: String, page: Int): PageResult<LiveRoomItem> {
        val result = search(keyword, page, 4)
        val docs = result["response"]["3"]
        val items = docs["docs"].arr.map { item ->
            LiveRoomItem(
                roomId = item["room_id"].str,
                title = item["game_introduction"].str.ifEmpty { item["game_roomName"].str },
                cover = coverUrl(item["game_screenshot"].str),
                userName = item["game_nick"].str,
                online = item["game_total_count"].long ?: 0,
            )
        }
        return PageResult((docs["numFound"].int ?: 0) > page * 20, items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): PageResult<LiveAnchorItem> {
        val result = search(keyword, page, 1)
        val docs = result["response"]["1"]
        val items = docs["docs"].arr.map { item ->
            LiveAnchorItem(
                roomId = item["room_id"].str,
                avatar = item["game_avatarUrl180"].str,
                userName = item["game_nick"].str,
                liveStatus = item["gameLiveOn"].strOrNull == "true",
            )
        }
        return PageResult((docs["numFound"].int ?: 0) > page * 20, items)
    }

    private suspend fun search(keyword: String, page: Int, v: Int): JsonElement = parseJson(
        Http.getText(
            "https://search.cdn.huya.com/",
            linkedMapOf(
                "m" to "Search",
                "do" to "getSearchContent",
                "q" to keyword,
                "uid" to 0,
                "v" to v,
                "typ" to -5,
                "livestate" to 0,
                "rows" to 20,
                "start" to (page - 1) * 20,
            ),
        ),
    )

    override suspend fun getLiveStatus(roomId: String): Boolean =
        getRoomInfo(roomId).json["roomInfo"]["eLiveStatus"].int == 2

    companion object {
        private const val BASE_URL = "https://m.huya.com/"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36 Edg/117.0.0.0"
        const val HYSDK_UA = "HYSDK(Windows, 30000002)_APP(pc_exe&7060000&official)_SDK(trans&2.32.3.5646)"
    }
}
