package com.xycz.simplelive.core.model

enum class LiveMessageType { CHAT, GIFT, ONLINE, SUPER_CHAT }

data class LiveMessageColor(val r: Int, val g: Int, val b: Int) {
    /** ARGB 颜色值 */
    val argb: Int get() = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    companion object {
        val WHITE = LiveMessageColor(255, 255, 255)

        /** RGB 整数转颜色（原 Dart 版按十六进制字符串长度判断，长度不是 6/8 时会错成白色） */
        fun fromRgb(value: Long): LiveMessageColor {
            if (value <= 0) return WHITE
            val v = value.toInt()
            return LiveMessageColor((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
        }
    }
}

data class LiveMessage(
    val type: LiveMessageType,
    val userName: String = "",
    val message: String = "",
    val color: LiveMessageColor = LiveMessageColor.WHITE,
    /** ONLINE 时为人数(Long)，SUPER_CHAT 时为 [LiveSuperChatMessage] */
    val data: Any? = null,
)

data class LiveSuperChatMessage(
    val userName: String,
    val face: String,
    val message: String,
    val price: Int,
    val startTime: Long,
    val endTime: Long,
    val backgroundColor: String,
    val backgroundBottomColor: String,
)
