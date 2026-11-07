package com.xycz.simple_live_tv.ui

import android.content.Context
import android.content.Intent
import com.xycz.simple_live_tv.data.Settings
import com.xycz.simple_live_tv.data.Sites
import com.xycz.simple_live_tv.data.Store
import com.xycz.simple_live_tv.ui.live.LiveRoomActivity
import com.xycz.simple_live_tv.ui.login.BiliLoginActivity
import com.xycz.simplelive.core.common.CoreLog

object Nav {
    /** 打开直播间；B站未登录且开启了提示时先引导登录（未登录画质受限） */
    fun openRoom(context: Context, siteId: String, roomId: String) {
        if (siteId == "bilibili" && Settings.bilibiliLoginTip && Store.accountCookie("bilibili").isEmpty()) {
            Dialogs.confirm(
                context,
                "哔哩哔哩未登录",
                "未登录时B站会限制画质，是否先扫码登录？",
                ok = "去登录",
                cancel = "直接观看",
            ) { login ->
                if (login) {
                    context.startActivity(Intent(context, BiliLoginActivity::class.java))
                } else {
                    startRoom(context, siteId, roomId)
                }
            }
            return
        }
        startRoom(context, siteId, roomId)
    }

    fun startRoom(context: Context, siteId: String, roomId: String) {
        val intent = Intent(context, LiveRoomActivity::class.java)
            .putExtra(LiveRoomActivity.EXTRA_SITE, siteId)
            .putExtra(LiveRoomActivity.EXTRA_ROOM, roomId)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 已在直播间时复用（singleTop），直接切台
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    /** 打开抖音直播间链接/分享文案，失败返回错误信息 */
    suspend fun openDouyinUrl(context: Context, text: String): String? {
        val roomId = try {
            Sites.douyin.parseRoomIdFromUrl(text)
        } catch (e: Exception) {
            CoreLog.e("解析抖音链接失败", e)
            null
        } ?: return "无法识别的抖音直播间链接"
        startRoom(context, "douyin", roomId)
        return null
    }
}
