package com.xycz.simplelive.core.model

data class LiveCategory(
    val id: String,
    val name: String,
    val children: List<LiveSubCategory>,
)

data class LiveSubCategory(
    val id: String,
    val name: String,
    val parentId: String,
    val pic: String? = null,
)

data class LiveRoomItem(
    val roomId: String,
    val title: String,
    val cover: String,
    val userName: String,
    val online: Long = 0,
)

data class LiveAnchorItem(
    val roomId: String,
    val avatar: String,
    val userName: String,
    val liveStatus: Boolean,
)

/** 分页结果（分类房间、推荐、搜索共用） */
data class PageResult<T>(
    val hasMore: Boolean,
    val items: List<T>,
)

class LiveRoomDetail(
    val roomId: String,
    val title: String,
    val cover: String,
    val userName: String,
    val userAvatar: String,
    val online: Long,
    val introduction: String? = null,
    val notice: String? = null,
    /** 是否直播中 */
    val status: Boolean,
    /** 各平台自己的播放数据 */
    val data: Any? = null,
    /** 各平台自己的弹幕连接参数 */
    val danmakuData: Any? = null,
    val url: String,
    /** 是否在轮播录像 */
    val isRecord: Boolean = false,
    /** 开播时间（秒级时间戳字符串） */
    val showTime: String? = null,
)

data class LivePlayQuality(
    val quality: String,
    val data: Any?,
    val sort: Int = 0,
)

data class LivePlayUrl(
    val urls: List<String>,
    val headers: Map<String, String>? = null,
    /** 平台实际给的清晰度与请求不一致时的提示，如"该清晰度需要登录斗鱼" */
    val notice: String? = null,
)
