package com.xycz.simplelive.core.site

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

interface LiveSite {
    val id: String
    val name: String

    fun getDanmaku(): LiveDanmaku

    suspend fun getCategories(): List<LiveCategory>

    suspend fun getCategoryRooms(category: LiveSubCategory, page: Int = 1): PageResult<LiveRoomItem>

    suspend fun getRecommendRooms(page: Int = 1): PageResult<LiveRoomItem>

    suspend fun searchRooms(keyword: String, page: Int = 1): PageResult<LiveRoomItem>

    suspend fun searchAnchors(keyword: String, page: Int = 1): PageResult<LiveAnchorItem>

    suspend fun getRoomDetail(roomId: String): LiveRoomDetail

    suspend fun getPlayQualities(detail: LiveRoomDetail): List<LivePlayQuality>

    suspend fun getPlayUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl

    suspend fun getLiveStatus(roomId: String): Boolean

    suspend fun getSuperChatMessages(roomId: String): List<LiveSuperChatMessage> = emptyList()
}

/**
 * 设备能力，用于选择清晰度/编码（由 App 按 MediaCodecList 注入）
 */
object DeviceCapability {
    /** 是否支持 H.265 硬解 */
    @Volatile
    var supportHevc: Boolean = false
}
