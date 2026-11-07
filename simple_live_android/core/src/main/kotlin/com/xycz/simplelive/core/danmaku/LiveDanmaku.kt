package com.xycz.simplelive.core.danmaku

import com.xycz.simplelive.core.model.LiveMessage

/** 弹幕客户端。回调在后台线程触发，UI 需自行切回主线程 */
interface LiveDanmaku {
    var onMessage: ((LiveMessage) -> Unit)?
    var onClose: ((String) -> Unit)?
    var onReady: (() -> Unit)?

    /** [args] 为 LiveRoomDetail.danmakuData */
    fun start(args: Any?)

    fun stop()
}
