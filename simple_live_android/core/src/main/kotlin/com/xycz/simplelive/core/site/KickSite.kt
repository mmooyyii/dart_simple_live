package com.xycz.simplelive.core.site

import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.common.arr
import com.xycz.simplelive.core.common.bool
import com.xycz.simplelive.core.common.get
import com.xycz.simplelive.core.common.int
import com.xycz.simplelive.core.common.long
import com.xycz.simplelive.core.common.obj
import com.xycz.simplelive.core.common.str
import com.xycz.simplelive.core.danmaku.KickDanmaku
import com.xycz.simplelive.core.danmaku.KickDanmakuArgs
import com.xycz.simplelive.core.danmaku.LiveDanmaku
import com.xycz.simplelive.core.model.LiveAnchorItem
import com.xycz.simplelive.core.model.LiveCategory
import com.xycz.simplelive.core.model.LivePlayQuality
import com.xycz.simplelive.core.model.LivePlayUrl
import com.xycz.simplelive.core.model.LiveRoomDetail
import com.xycz.simplelive.core.model.LiveRoomItem
import com.xycz.simplelive.core.model.LiveSubCategory
import com.xycz.simplelive.core.model.PageResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/**
 * Kick 直播。房间号用频道 slug（kick.com/<slug>），播放地址是 AWS IVS 的 HLS（H.264），不需要签名。
 * 国内需要代理才能访问。
 */
class KickSite : LiveSite {
    override val id = "kick"
    override val name = "Kick"

    override fun getDanmaku(): LiveDanmaku = KickDanmaku()

    override suspend fun getCategories(): List<LiveCategory> = coroutineScope {
        val parents = Http.getJson("https://kick.com/api/v1/categories", headers = HEADERS).arr
        parents.map { parent ->
            async {
                val parentId = parent["id"].str
                // 按观看人数排序，接口单页最多 32 个
                val subs = try {
                    Http.getJson(
                        "https://kick.com/api/v1/subcategories",
                        mapOf("category" to parent["slug"].str, "limit" to 32),
                        HEADERS,
                    )["data"].arr
                } catch (e: Exception) {
                    emptyList()
                }
                LiveCategory(
                    id = parentId,
                    name = parent["name"].str,
                    children = subs.map {
                        LiveSubCategory(
                            id = it["id"].str,
                            name = it["name"].str,
                            parentId = parentId,
                            pic = it["banner"]["url"].str.ifEmpty { null },
                        )
                    },
                )
            }
        }.awaitAll().filter { it.children.isNotEmpty() }
    }

    override suspend fun getCategoryRooms(category: LiveSubCategory, page: Int): PageResult<LiveRoomItem> =
        getLivestreams(page, mapOf("category_id" to category.id))

    override suspend fun getRecommendRooms(page: Int): PageResult<LiveRoomItem> = getLivestreams(page)

    private suspend fun getLivestreams(page: Int, extra: Map<String, Any?> = emptyMap()): PageResult<LiveRoomItem> {
        val query = mutableMapOf<String, Any?>("limit" to PAGE_SIZE, "sort" to "viewer_count_desc")
        query.putAll(extra)
        // 游标是 base64({"o":偏移})，直接按页码算
        if (page > 1) query["after"] = offsetCursor((page - 1) * PAGE_SIZE)
        val result = Http.getJson("https://web.kick.com/api/v1/livestreams", query, HEADERS)
        val items = result["data"]["livestreams"].arr.map { item ->
            LiveRoomItem(
                roomId = item["channel"]["slug"].str,
                title = item["title"].str,
                cover = item["thumbnail"]["src"].str,
                userName = item["channel"]["username"].str,
                online = item["viewer_count"].long ?: 0,
            )
        }
        val hasMore = items.size >= PAGE_SIZE && result["data"]["pagination"]["next_cursor"].str.isNotEmpty()
        return PageResult(hasMore, items)
    }

    override suspend fun searchRooms(keyword: String, page: Int): PageResult<LiveRoomItem> {
        val channels = searchChannels(keyword, page)
        val items = channels.filter { it["isLive"].bool }.map { ch ->
            val name = ch["user"]["username"].str
            LiveRoomItem(
                roomId = ch["slug"].str,
                title = name,
                cover = ch["user"]["profilePic"].str,
                userName = name,
            )
        }
        return PageResult(channels.isNotEmpty(), items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): PageResult<LiveAnchorItem> {
        val channels = searchChannels(keyword, page)
        val items = channels.map { ch ->
            LiveAnchorItem(
                roomId = ch["slug"].str,
                avatar = ch["user"]["profilePic"].str,
                userName = ch["user"]["username"].str,
                liveStatus = ch["isLive"].bool,
            )
        }
        return PageResult(channels.isNotEmpty(), items)
    }

    private suspend fun searchChannels(keyword: String, page: Int): List<JsonElement> =
        Http.getJson(
            "https://kick.com/api/search",
            mapOf("searched_word" to keyword, "page" to page),
            HEADERS,
        )["channels"].arr

    override suspend fun getRoomDetail(roomId: String): LiveRoomDetail {
        val ch = getChannel(roomId)
        val slug = ch["slug"].str
        val live = ch["livestream"].obj
        return LiveRoomDetail(
            roomId = slug,
            title = live["session_title"].str,
            cover = live["thumbnail"]["url"].str.ifEmpty { ch["banner_image"]["url"].str },
            userName = ch["user"]["username"].str,
            userAvatar = ch["user"]["profile_pic"].str,
            online = live["viewer_count"].long ?: 0,
            introduction = ch["user"]["bio"].str,
            status = live != null && live["is_live"].bool,
            data = ch["playback_url"].str,
            danmakuData = KickDanmakuArgs(channelId = ch["id"].str, chatroomId = ch["chatroom"]["id"].str),
            url = "https://kick.com/$slug",
            showTime = parseUtcTime(live["start_time"].str)?.toString(),
        )
    }

    private suspend fun getChannel(roomId: String): JsonElement {
        val slug = roomId.trim().lowercase()
        val ch = Http.getJson("https://kick.com/api/v2/channels/$slug", headers = HEADERS)
        if (ch["slug"].str.isEmpty()) throw IllegalStateException("Kick 频道不存在：$roomId")
        return ch
    }

    override suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val master = detail.data as? String
        if (master.isNullOrEmpty()) return emptyList()
        val variants = parseMaster(Http.getText(master, headers = HEADERS))
        if (variants.isEmpty()) return listOf(LivePlayQuality("原画", null))
        return variants.map { LivePlayQuality(quality = it.name, data = it.name, sort = it.bandwidth) }
    }

    override suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        // 每次重新取 playback_url（带 token），再按名字挑子流；挑不到就播主列表（自适应码率）
        val master = try {
            getChannel(detail.roomId)["playback_url"].str
        } catch (e: Exception) {
            ""
        }.ifEmpty { detail.data as? String ?: "" }
        if (master.isEmpty()) return LivePlayUrl(emptyList())
        val name = quality.data as? String
        val variant = if (name == null) {
            null
        } else {
            try {
                parseMaster(Http.getText(master, headers = HEADERS)).firstOrNull { it.name == name }
            } catch (e: Exception) {
                null
            }
        }
        return LivePlayUrl(listOfNotNull(variant?.url, master).distinct())
    }

    override suspend fun getLiveStatus(roomId: String): Boolean {
        val live = getChannel(roomId)["livestream"].obj
        return live != null && live["is_live"].bool
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val HEADERS = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")

        data class Variant(val name: String, val bandwidth: Int, val url: String)

        /** 解析 HLS 主列表，按码率从高到低 */
        fun parseMaster(text: String): List<Variant> {
            val lines = text.lines().map { it.trim() }
            val names = mutableMapOf<String, String>()
            for (line in lines) {
                if (!line.startsWith("#EXT-X-MEDIA:") || !line.contains("TYPE=VIDEO")) continue
                val group = attr(line, "GROUP-ID") ?: continue
                attr(line, "NAME")?.let { names[group] = it }
            }
            val result = mutableListOf<Variant>()
            for ((i, line) in lines.withIndex()) {
                if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
                val url = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                val bandwidth = attr(line, "BANDWIDTH")?.toIntOrNull() ?: 0
                val name = attr(line, "VIDEO")?.let { names[it] }
                    ?: attr(line, "RESOLUTION")?.substringAfter('x')?.let { "${it}p" }
                    ?: "${bandwidth / 1000}k"
                result.add(Variant(name, bandwidth, url))
            }
            return result.distinctBy { it.name }.sortedByDescending { it.bandwidth }
        }

        private fun attr(line: String, key: String): String? =
            Regex("""(?:^|[:,])$key=("([^"]*)"|[^,]*)""").find(line)?.let { m ->
                m.groupValues[2].ifEmpty { m.groupValues[1] }
            }

        private fun offsetCursor(offset: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"o":$offset}""".toByteArray())

        /** "2026-11-19 00:35:23"（UTC）→ 秒级时间戳 */
        private fun parseUtcTime(text: String): Long? = try {
            if (text.isEmpty()) {
                null
            } else {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(text.replace('T', ' ').take(19))!!.time / 1000
            }
        } catch (e: Exception) {
            null
        }

        /** 从 kick.com/<slug> 链接取频道 slug，不是链接时原样返回 */
        fun parseRoomIdFromUrl(text: String): String? {
            val m = Regex("""kick\.com/(?:popout/)?([A-Za-z0-9_-]+)""").find(text) ?: return null
            return m.groupValues[1].lowercase().takeIf { it !in RESERVED }
        }

        private val RESERVED = setOf("category", "categories", "browse", "search", "video", "clips", "following")
    }
}
