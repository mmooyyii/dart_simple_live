package com.xycz.simplelive.core.live

import com.xycz.simplelive.core.NodeJsEngine
import com.xycz.simplelive.core.common.Js
import com.xycz.simplelive.core.model.LiveMessageType
import com.xycz.simplelive.core.site.BiliBiliSite
import com.xycz.simplelive.core.site.DeviceCapability
import com.xycz.simplelive.core.site.DouyinSite
import com.xycz.simplelive.core.site.DouyuSite
import com.xycz.simplelive.core.site.HuyaSite
import com.xycz.simplelive.core.site.LiveSite
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 真实请求各平台接口的冒烟测试：./gradlew :core:test -PliveTest */
class SiteSmokeTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            // 斗鱼弹幕服务器只支持 TLS_RSA_* 套件，新版 JDK 默认禁用（安卓上可用）
            java.security.Security.setProperty("jdk.tls.disabledAlgorithms", "SSLv3, RC4, DES, MD5withRSA, NULL, anon")
            Js.engine = NodeJsEngine()
            DeviceCapability.supportHevc = System.getProperty("hevc") == "1"
        }
    }

    private fun smoke(site: LiveSite) = runBlocking {
        println("==== ${site.name} ====")
        val categories = site.getCategories()
        println("分类 ${categories.size} 个，第一个 ${categories.firstOrNull()?.name}，子分类 ${categories.firstOrNull()?.children?.size}")
        assertTrue(categories.isNotEmpty())
        val sub = categories.first().children.first()
        val catRooms = site.getCategoryRooms(sub, 1)
        println("分类 ${sub.name} 房间 ${catRooms.items.size} 个 hasMore=${catRooms.hasMore}")

        val rooms = site.getRecommendRooms(1)
        println("推荐 ${rooms.items.size} 个 hasMore=${rooms.hasMore}")
        assertTrue(rooms.items.isNotEmpty())
        val item = rooms.items.first()
        println("第一个：${item.roomId} ${item.userName} ${item.title} 在线 ${item.online}")

        val detail = site.getRoomDetail(item.roomId)
        println("详情：${detail.roomId} ${detail.userName} status=${detail.status} record=${detail.isRecord}")
        assertTrue(detail.status || detail.isRecord)
        println("在线状态：${site.getLiveStatus(item.roomId)}")

        val qualities = site.getPlayQualities(detail)
        println("清晰度：${qualities.map { it.quality }}")
        assertTrue(qualities.isNotEmpty())
        val urls = site.getPlayUrls(detail, qualities.first())
        println("最高清晰度地址 ${urls.urls.size} 条，notice=${urls.notice}")
        urls.urls.take(2).forEach { println("  ${it.take(160)}") }
        assertTrue(urls.urls.isNotEmpty())

        // 弹幕：20 秒内收到聊天或在线人数
        val latch = CountDownLatch(1)
        val danmaku = site.getDanmaku()
        danmaku.onReady = { println("弹幕已连接") }
        danmaku.onClose = { println("弹幕关闭：$it") }
        danmaku.onMessage = {
            if (it.type == LiveMessageType.CHAT) println("  [弹幕] ${it.userName}: ${it.message}")
            if (it.type == LiveMessageType.ONLINE) println("  [在线] ${it.data}")
            latch.countDown()
        }
        danmaku.start(detail.danmakuData)
        val got = latch.await(20, TimeUnit.SECONDS)
        danmaku.stop()
        println("弹幕收到消息：$got")
        assertTrue("没有收到弹幕", got)
    }

    @Test
    fun bilibili() = smoke(BiliBiliSite())

    @Test
    fun douyu() = smoke(DouyuSite())

    @Test
    fun huya() = smoke(HuyaSite())

    @Test
    fun douyin() = smoke(DouyinSite())

    @Test
    fun douyinSearchByUrl() = runBlocking {
        val site = DouyinSite()
        val rooms = site.getRecommendRooms(1)
        val rid = rooms.items.first().roomId
        val result = site.searchRooms("https://www.douyin.com/follow/live/$rid?anchor_id=1")
        println("链接搜索：${result.items}")
        assertTrue(result.items.first().roomId == rid)
    }

    @Test
    fun searches() = runBlocking {
        for (site in listOf(BiliBiliSite(), DouyuSite(), HuyaSite())) {
            val r = site.searchRooms("英雄联盟")
            val a = site.searchAnchors("英雄联盟")
            println("${site.name} 搜索直播间 ${r.items.size} 个，主播 ${a.items.size} 个：${a.items.take(2)}")
            assertTrue(r.items.isNotEmpty())
        }
    }
}
