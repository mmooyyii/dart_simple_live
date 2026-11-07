package com.xycz.simple_live_tv.ui.live

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.xycz.simple_live_tv.data.FollowUser
import com.xycz.simple_live_tv.data.HistoryItem
import com.xycz.simple_live_tv.data.Settings
import com.xycz.simple_live_tv.data.ShieldFilter
import com.xycz.simple_live_tv.data.SiteInfo
import com.xycz.simple_live_tv.data.Sites
import com.xycz.simple_live_tv.data.Store
import com.xycz.simple_live_tv.databinding.ActivityLiveBinding
import com.xycz.simple_live_tv.service.FollowService
import com.xycz.simple_live_tv.ui.AnchorAdapter
import com.xycz.simple_live_tv.ui.AnchorCard
import com.xycz.simple_live_tv.ui.BaseActivity
import com.xycz.simple_live_tv.ui.widget.ActionItem
import com.xycz.simple_live_tv.ui.widget.HeaderItem
import com.xycz.simple_live_tv.ui.widget.OptionItem
import com.xycz.simple_live_tv.ui.widget.SettingViews
import com.xycz.simple_live_tv.util.errorMessage
import com.xycz.simple_live_tv.util.formatOnline
import com.xycz.simple_live_tv.util.toast
import com.xycz.simplelive.core.common.CoreLog
import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.danmaku.LiveDanmaku
import com.xycz.simplelive.core.model.LiveMessageType
import com.xycz.simplelive.core.model.LivePlayQuality
import com.xycz.simplelive.core.model.LiveRoomDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class LiveRoomActivity : BaseActivity() {
    private lateinit var b: ActivityLiveBinding
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var site: SiteInfo
    private var roomId = ""
    private var detail: LiveRoomDetail? = null
    private var qualities: List<LivePlayQuality> = emptyList()
    private var qualityIndex = 0
    private var playUrls: List<String> = emptyList()
    private var playHeaders: Map<String, String> = emptyMap()
    private var lineIndex = 0
    private var retryCount = 0
    private var danmaku: LiveDanmaku? = null
    private var online = 0L

    /** 加载批次号：快速切台时丢弃旧房间迟到的结果 */
    private var loadBatch = 0
    private var stopped = false
    private var lastBackTime = 0L

    // 弹幕限流
    private var danmakuWindowStart = 0L
    private var danmakuWindowCount = 0

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val hideControls = Runnable { setControlsVisible(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(b.root)
        initPlayer()
        applyDanmakuSettings()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        b.root.setOnClickListener { openSettingsPanel() }
        openRoom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRoom(intent)
    }

    private fun openRoom(intent: Intent) {
        val newSite = Sites[intent.getStringExtra(EXTRA_SITE) ?: "bilibili"]
        val newRoom = intent.getStringExtra(EXTRA_ROOM) ?: return
        if (this::site.isInitialized && newSite == site && newRoom == roomId && detail != null) return
        switchRoom(newSite, newRoom)
    }

    // ---------------- 播放器 ----------------

    private fun initPlayer() {
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        // 直播不需要大缓冲，减小内存占用
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 20_000, 1_000, 2_000)
            .build()
        player = ExoPlayer.Builder(this, renderers).setLoadControl(loadControl).build()
        player.setVideoSurfaceView(b.surface)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                b.loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    retryCount = 0
                    b.message.visibility = View.GONE
                    renderInfo()
                }
                if (state == Player.STATE_ENDED) onPlayEnd(error = null)
            }

            override fun onPlayerError(error: PlaybackException) {
                CoreLog.e("播放失败 ${error.errorCodeName}", error)
                onPlayEnd(error)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                applyScaleMode()
                renderInfo()
            }
        })
        b.videoBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyScaleMode() }
    }

    private fun play() {
        if (playUrls.isEmpty()) return
        val url = playUrls[lineIndex]
        CoreLog.d("播放 线路${lineIndex + 1} $url")
        val dataSource = OkHttpDataSource.Factory(Http.client).setDefaultRequestProperties(playHeaders)
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(item))
        player.prepare()
        player.playWhenReady = true
        renderInfo()
    }

    /** 播放出错或结束：同一线路重试 2 次（第 2 次延迟 1 秒），再换下一条线路 */
    private fun onPlayEnd(error: PlaybackException?) {
        if (playUrls.isEmpty()) return
        val batch = loadBatch
        if (retryCount < 2) {
            retryCount++
            lifecycleScope.launch {
                if (retryCount == 2) delay(1000)
                if (batch == loadBatch) play()
            }
            return
        }
        if (lineIndex < playUrls.size - 1) {
            lineIndex++
            retryCount = 0
            play()
            return
        }
        if (error != null) {
            showMessage("播放失败：${error.errorCodeName}")
        } else {
            showMessage("直播已结束")
        }
    }

    /** 画面比例：0=适应 1=拉伸 2=铺满 3=16:9 4=4:3 */
    private fun applyScaleMode() {
        val boxW = b.videoBox.width
        val boxH = b.videoBox.height
        if (boxW == 0 || boxH == 0) return
        val size = player.videoSize
        val videoRatio = when (Settings.scaleMode) {
            3 -> 16f / 9f
            4 -> 4f / 3f
            else -> if (size.width > 0 && size.height > 0) {
                size.width * size.pixelWidthHeightRatio / size.height
            } else {
                16f / 9f
            }
        }
        val boxRatio = boxW.toFloat() / boxH
        val (w, h) = when (Settings.scaleMode) {
            1 -> boxW to boxH
            2 -> if (videoRatio > boxRatio) (boxH * videoRatio).toInt() to boxH else boxW to (boxW / videoRatio).toInt()
            else -> if (videoRatio > boxRatio) boxW to (boxW / videoRatio).toInt() else (boxH * videoRatio).toInt() to boxH
        }
        val lp = b.surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            b.surface.layoutParams = lp
        }
    }

    // ---------------- 加载 ----------------

    private fun switchRoom(newSite: SiteInfo, newRoomId: String) {
        stopDanmaku()
        player.stop()
        b.danmaku.clear()
        site = newSite
        roomId = newRoomId
        detail = null
        qualities = emptyList()
        playUrls = emptyList()
        online = 0
        load()
    }

    private fun load() {
        val batch = ++loadBatch
        b.loading.visibility = View.VISIBLE
        b.message.visibility = View.GONE
        renderInfo()
        lifecycleScope.launch {
            try {
                val d = site.site.getRoomDetail(roomId)
                if (batch != loadBatch) return@launch
                detail = d
                online = d.online
                addHistory(d)
                renderInfo()
                startDanmaku(d)
                if (!d.status && !d.isRecord) {
                    b.loading.visibility = View.GONE
                    showMessage("未开播")
                    return@launch
                }
                if (d.isRecord) toast("当前主播未开播，正在轮播录像")
                qualities = site.site.getPlayQualities(d)
                if (batch != loadBatch) return@launch
                if (qualities.isEmpty()) {
                    b.loading.visibility = View.GONE
                    showMessage("无法读取播放清晰度")
                    return@launch
                }
                qualityIndex = when (Settings.qualityLevel) {
                    2 -> 0
                    0 -> qualities.size - 1
                    else -> qualities.size / 2
                }
                loadPlayUrls(batch)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CoreLog.e("加载直播间失败", e)
                if (batch == loadBatch) {
                    b.loading.visibility = View.GONE
                    showMessage("加载失败：${errorMessage(e)}")
                }
            }
        }
    }

    private suspend fun loadPlayUrls(batch: Int = loadBatch) {
        val d = detail ?: return
        try {
            val result = site.site.getPlayUrls(d, qualities[qualityIndex])
            if (batch != loadBatch) return
            if (result.urls.isEmpty()) {
                b.loading.visibility = View.GONE
                showMessage("无法读取播放地址")
                return
            }
            result.notice?.let { toast(it) }
            playUrls = result.urls
            playHeaders = result.headers ?: emptyMap()
            lineIndex = 0
            retryCount = 0
            play()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CoreLog.e("获取播放地址失败", e)
            if (batch == loadBatch) {
                b.loading.visibility = View.GONE
                showMessage("无法读取播放地址：${errorMessage(e)}")
            }
        }
    }

    private fun addHistory(d: LiveRoomDetail) {
        Store.addHistory(
            HistoryItem(
                id = "${site.id}_$roomId",
                roomId = roomId,
                siteId = site.id,
                userName = d.userName,
                face = d.userAvatar,
                updateTime = System.currentTimeMillis(),
            ),
        )
    }

    private fun showMessage(text: String) {
        b.message.text = text
        b.message.visibility = View.VISIBLE
    }

    // ---------------- 弹幕 ----------------

    private fun startDanmaku(d: LiveRoomDetail) {
        stopDanmaku()
        val client = site.site.getDanmaku()
        client.onMessage = { msg ->
            when (msg.type) {
                LiveMessageType.CHAT -> if (acceptDanmaku(msg.message)) {
                    runOnUiThread { b.danmaku.add(msg.message, msg.color.argb) }
                }
                LiveMessageType.ONLINE -> runOnUiThread {
                    online = msg.data as? Long ?: online
                    renderInfo()
                }
                else -> {}
            }
        }
        client.onClose = { CoreLog.w("弹幕：$it") }
        danmaku = client
        try {
            client.start(d.danmakuData)
        } catch (e: Exception) {
            CoreLog.e("弹幕连接失败", e)
        }
    }

    /** 屏蔽词和每秒上限（超出部分屏幕上也放不下） */
    private fun acceptDanmaku(text: String): Boolean {
        if (!Settings.danmuEnable || stopped) return false
        if (ShieldFilter.isShielded(text)) return false
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - danmakuWindowStart >= 1000) {
                danmakuWindowStart = now
                danmakuWindowCount = 0
            }
            if (danmakuWindowCount >= Settings.danmuMaxPerSecond) return false
            danmakuWindowCount++
        }
        return true
    }

    private fun stopDanmaku() {
        danmaku?.stop()
        danmaku = null
    }

    private fun applyDanmakuSettings() {
        val density = resources.displayMetrics.density
        // 设置值沿用 Flutter 版的设计稿像素（1920 宽），换算成 dp 再转 px
        b.danmaku.textSizePx = Settings.danmuSize / 2 * density
        b.danmaku.durationSeconds = Settings.danmuSpeed
        b.danmaku.area = Settings.danmuArea
        b.danmaku.opacity = Settings.danmuOpacity
        b.danmaku.strokeWidth = Settings.danmuStrokeWidth / 2 * density
        b.danmaku.visibility = if (Settings.danmuEnable) View.VISIBLE else View.GONE
        if (!Settings.danmuEnable) b.danmaku.clear()
    }

    // ---------------- 信息栏 ----------------

    private fun renderInfo() {
        val d = detail
        b.roomTitle.text = d?.title ?: "加载中..."
        val followed = Store.isFollowed("${site.id}_$roomId")
        b.roomInfo.text = listOfNotNull(
            site.name,
            d?.userName,
            if (followed) "已关注" else "未关注",
            if (online > 0) "人气 ${formatOnline(online)}" else null,
        ).joinToString(" · ")
        val size = player.videoSize
        b.playInfo.text = listOfNotNull(
            qualities.getOrNull(qualityIndex)?.let { "清晰度：${it.quality}" },
            if (playUrls.isNotEmpty()) "线路：${lineIndex + 1}/${playUrls.size}" else null,
            "弹幕：${if (Settings.danmuEnable) "开" else "关"}",
            if (size.width > 0) "分辨率：${size.width}x${size.height}" else null,
        ).joinToString("    ")
        b.clock.text = clockFormat.format(Date())
    }

    private fun setControlsVisible(visible: Boolean) {
        handler.removeCallbacks(hideControls)
        b.topBar.visibility = if (visible) View.VISIBLE else View.GONE
        b.bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            renderInfo()
            handler.postDelayed(hideControls, 5000)
        }
    }

    // ---------------- 按键 ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (b.panel.visibility == View.VISIBLE || event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> {
                setControlsVisible(b.topBar.visibility != View.VISIBLE)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M -> {
                openSettingsPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                openFollowPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.repeatCount == 0) switchChannel(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.repeatCount == 0) switchChannel(1)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onBack() {
        if (b.panel.visibility == View.VISIBLE) {
            closePanel()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackTime < 2000) {
            finish()
        } else {
            lastBackTime = now
            toast("再按一次退出播放器")
        }
    }

    /** 在正在直播的关注里切台 */
    private fun switchChannel(step: Int) {
        val living = FollowService.livingList()
        if (living.isEmpty()) {
            toast("没有正在直播的关注")
            return
        }
        val current = living.indexOfFirst { it.id == "${site.id}_$roomId" }
        val next = if (current < 0) {
            if (step > 0) 0 else living.size - 1
        } else {
            (current + step + living.size) % living.size
        }
        val target = living[next]
        toast(target.userName)
        switchRoom(Sites[target.siteId], target.roomId)
    }

    // ---------------- 右侧面板 ----------------

    private fun closePanel() {
        b.panel.visibility = View.GONE
        b.root.requestFocus()
    }

    private fun openSettingsPanel() {
        setControlsVisible(false)
        b.panelTitle.text = "设置"
        b.panelScroll.visibility = View.VISIBLE
        b.panelList.visibility = View.GONE
        b.panelContent.removeAllViews()
        val items = listOf(
            ActionItem("关注用户", { if (Store.isFollowed("${site.id}_$roomId")) "已关注" else "未关注" }) { toggleFollow() },
            HeaderItem("清晰度与线路"),
            OptionItem(
                "清晰度",
                { qualities.mapIndexed { i, q -> i to q.quality } },
                { qualityIndex },
                { i ->
                    if (i != qualityIndex) {
                        qualityIndex = i
                        lifecycleScope.launch { loadPlayUrls() }
                    }
                },
            ),
            OptionItem(
                "线路",
                { playUrls.indices.map { it to "线路${it + 1}" } },
                { lineIndex },
                { i ->
                    if (i != lineIndex) {
                        lineIndex = i
                        retryCount = 0
                        play()
                    }
                },
            ),
            OptionItem(
                "画面比例",
                { listOf(0 to "适应", 1 to "拉伸", 2 to "铺满", 3 to "16:9", 4 to "4:3") },
                { Settings.scaleMode },
                { Settings.scaleMode = it; applyScaleMode() },
            ),
        ) + danmakuSettingItems { applyDanmakuSettings() }
        SettingViews.add(this, b.panelContent, items)
        b.panel.visibility = View.VISIBLE
        b.panelContent.getChildAt(0)?.requestFocus()
    }

    private fun toggleFollow() {
        val d = detail ?: return
        val id = "${site.id}_$roomId"
        if (Store.isFollowed(id)) {
            Store.unfollow(id)
            toast("已取消关注")
        } else {
            Store.follow(FollowUser(id, roomId, site.id, d.userName, d.userAvatar, System.currentTimeMillis()))
            toast("已关注")
        }
        renderInfo()
    }

    private fun openFollowPanel() {
        setControlsVisible(false)
        val living = FollowService.livingList()
        b.panelTitle.text = "正在直播的关注"
        b.panelScroll.visibility = View.GONE
        b.panelList.visibility = View.VISIBLE
        val adapter = AnchorAdapter({
            closePanel()
            switchRoom(Sites[it.siteId], it.roomId)
        })
        b.panelList.layoutManager = LinearLayoutManager(this)
        b.panelList.adapter = adapter
        adapter.submit(living.map { AnchorCard(it.siteId, it.roomId, it.userName, it.face, 2) })
        b.panel.visibility = View.VISIBLE
        if (living.isEmpty()) {
            b.panelTitle.text = "没有正在直播的关注"
            b.panel.requestFocus()
            return
        }
        val current = living.indexOfFirst { it.id == "${site.id}_$roomId" }.coerceAtLeast(0)
        b.panelList.scrollToPosition(current)
        b.panelList.post { b.panelList.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus() }
    }

    // ---------------- 生命周期 ----------------

    override fun onStart() {
        super.onStart()
        if (stopped) {
            stopped = false
            if (this::site.isInitialized) switchRoom(site, roomId)
        }
    }

    override fun onStop() {
        super.onStop()
        // 退到后台时停止播放和弹幕，回来后重新加载
        stopped = true
        loadBatch++
        player.stop()
        stopDanmaku()
        b.danmaku.clear()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopDanmaku()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SITE = "site"
        const val EXTRA_ROOM = "room"

        /** 弹幕相关设置项（直播间面板和设置页共用） */
        fun danmakuSettingItems(onChange: () -> Unit) = listOf(
            HeaderItem("弹幕"),
            OptionItem("弹幕开关", { listOf(true to "开", false to "关") }, { Settings.danmuEnable }, {
                Settings.danmuEnable = it
                onChange()
            }),
            OptionItem(
                "弹幕大小",
                { listOf(24f, 32f, 40f, 48f, 56f, 64f, 72f).map { it to it.toInt().toString() } },
                { Settings.danmuSize },
                { Settings.danmuSize = it; onChange() },
            ),
            OptionItem(
                "弹幕速度",
                {
                    listOf(18f to "很慢", 14f to "较慢", 12f to "慢", 10f to "正常", 8f to "快", 6f to "较快", 4f to "很快")
                },
                { Settings.danmuSpeed },
                { Settings.danmuSpeed = it; onChange() },
            ),
            OptionItem(
                "显示区域",
                { listOf(0.25f to "1/4", 0.5f to "1/2", 0.75f to "3/4", 1f to "全屏") },
                { Settings.danmuArea },
                { Settings.danmuArea = it; onChange() },
            ),
            OptionItem(
                "不透明度",
                { (1..10).map { it / 10f to "${it * 10}%" } },
                { (Settings.danmuOpacity * 10).toInt() / 10f },
                { Settings.danmuOpacity = it; onChange() },
            ),
            OptionItem(
                "描边宽度",
                { listOf(0f, 2f, 4f, 6f, 8f).map { it to if (it == 0f) "无" else it.toInt().toString() } },
                { Settings.danmuStrokeWidth },
                { Settings.danmuStrokeWidth = it; onChange() },
            ),
            OptionItem(
                "每秒最多弹幕",
                { listOf(10, 15, 25, 40, 60).map { it to "$it 条" } },
                { Settings.danmuMaxPerSecond },
                { Settings.danmuMaxPerSecond = it },
            ),
        )
    }
}
