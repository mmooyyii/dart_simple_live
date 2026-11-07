package com.xycz.simple_live_tv.data

import com.xycz.simple_live_tv.R
import com.xycz.simplelive.core.site.BiliBiliSite
import com.xycz.simplelive.core.site.DouyinSite
import com.xycz.simplelive.core.site.DouyuSite
import com.xycz.simplelive.core.site.HuyaSite
import com.xycz.simplelive.core.site.LiveSite

class SiteInfo(val id: String, val name: String, val logo: Int, val site: LiveSite)

object Sites {
    val bilibili = BiliBiliSite()
    val douyu = DouyuSite()
    val huya = HuyaSite()
    val douyin = DouyinSite()

    val all = listOf(
        SiteInfo("bilibili", "哔哩哔哩", R.drawable.logo_bilibili, bilibili),
        SiteInfo("douyu", "斗鱼直播", R.drawable.logo_douyu, douyu),
        SiteInfo("huya", "虎牙直播", R.drawable.logo_huya, huya),
        SiteInfo("douyin", "抖音直播", R.drawable.logo_douyin, douyin),
    )

    operator fun get(id: String): SiteInfo = all.firstOrNull { it.id == id } ?: all.first()

    /** 把账号 cookie 应用到各站点（登录、同步后调用） */
    fun applyAccounts() {
        bilibili.cookie = Store.accountCookie("bilibili")
        bilibili.userId = Regex("DedeUserID=(\\d+)").find(bilibili.cookie)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        douyu.cookie = Store.accountCookie("douyu")
    }
}
