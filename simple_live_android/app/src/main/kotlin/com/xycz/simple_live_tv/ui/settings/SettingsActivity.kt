package com.xycz.simple_live_tv.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import com.xycz.simple_live_tv.BuildConfig
import com.xycz.simple_live_tv.R
import com.xycz.simple_live_tv.data.Settings
import com.xycz.simple_live_tv.data.Store
import com.xycz.simple_live_tv.databinding.ActivitySettingsBinding
import com.xycz.simple_live_tv.service.FollowService
import com.xycz.simple_live_tv.service.OtaService
import com.xycz.simple_live_tv.ui.BaseActivity
import com.xycz.simple_live_tv.ui.Dialogs
import com.xycz.simple_live_tv.ui.live.LiveRoomActivity
import com.xycz.simple_live_tv.ui.login.BiliLoginActivity
import com.xycz.simple_live_tv.ui.widget.ActionItem
import com.xycz.simple_live_tv.ui.widget.HeaderItem
import com.xycz.simple_live_tv.ui.widget.OptionItem
import com.xycz.simple_live_tv.ui.widget.PillButton
import com.xycz.simple_live_tv.ui.widget.SettingItem
import com.xycz.simple_live_tv.ui.widget.SettingViews
import com.xycz.simple_live_tv.util.toast

class SettingsActivity : BaseActivity() {
    private lateinit var b: ActivitySettingsBinding
    private val tabs = mutableListOf<PillButton>()
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setIcon(R.drawable.ic_arrow_back)
        b.back.setOnClickListener { finish() }
        val pages = listOf("播放", "弹幕", "关注", "账号", "关于")
        pages.forEachIndexed { i, name ->
            val btn = PillButton(this).apply {
                text = name
                setOnClickListener { show(i) }
                setOnFocusChangeListener { v, focused ->
                    v.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(150).start()
                    if (focused) show(i)
                }
            }
            b.tabs.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() })
            tabs += btn
        }
        show(0)
        tabs[0].requestFocus()
    }

    override fun onResume() {
        super.onResume()
        SettingViews.refreshAll(b.content)
    }

    private fun show(index: Int) {
        if (b.content.childCount > 0 && index == current) return
        current = index
        tabs.forEachIndexed { i, t -> t.isSelected = i == index }
        b.content.removeAllViews()
        SettingViews.add(this, b.content, page(index))
    }

    private fun page(index: Int): List<SettingItem> = when (index) {
        0 -> listOf(
            OptionItem(
                "默认清晰度",
                { listOf(2 to "最高画质", 1 to "中等画质", 0 to "最低画质") },
                { Settings.qualityLevel },
                { Settings.qualityLevel = it },
            ),
            OptionItem(
                "画面比例",
                { listOf(0 to "适应", 1 to "拉伸", 2 to "铺满", 3 to "16:9", 4 to "4:3") },
                { Settings.scaleMode },
                { Settings.scaleMode = it },
            ),
        )
        1 -> LiveRoomActivity.danmakuSettingItems {} + listOf(
            ActionItem("屏蔽词", { "${Store.shieldWords().size} 个" }) { manageShieldWords() },
        )
        2 -> listOf(
            OptionItem("自动更新关注", { listOf(true to "开", false to "关") }, { Settings.autoUpdateFollowEnable }, {
                Settings.autoUpdateFollowEnable = it
                FollowService.schedule()
            }),
            OptionItem(
                "自动更新间隔",
                { listOf(5, 10, 15, 20, 25, 30, 60).map { it to if (it == 60) "1小时" else "${it}分钟" } },
                { Settings.autoUpdateFollowDuration },
                {
                    Settings.autoUpdateFollowDuration = it
                    FollowService.schedule()
                },
            ),
            OptionItem(
                "更新线程数",
                { listOf(1, 2, 3, 4, 6, 8, 10, 12).map { it to it.toString() } },
                { Settings.updateFollowThreadCount },
                { Settings.updateFollowThreadCount = it },
            ),
        )
        3 -> listOf(
            ActionItem("哔哩哔哩", {
                if (Store.accountCookie("bilibili").isEmpty()) "未登录，点击扫码登录" else "已登录，点击退出"
            }) {
                if (Store.accountCookie("bilibili").isEmpty()) {
                    startActivity(Intent(this, BiliLoginActivity::class.java))
                } else {
                    Dialogs.confirm(this, "退出登录", "确定要退出哔哩哔哩账号吗？") {
                        if (it) {
                            Store.setAccountCookie("bilibili", "")
                            SettingViews.refreshAll(b.content)
                        }
                    }
                }
            },
            OptionItem("B站未登录时提醒", { listOf(true to "开", false to "关") }, { Settings.bilibiliLoginTip }, {
                Settings.bilibiliLoginTip = it
            }),
            ActionItem("斗鱼", { "无需登录（不登录最高为高清）" }) { toast("斗鱼登录暂未支持") },
            ActionItem("虎牙", { "无需登录" }) {},
            ActionItem("抖音", { "无需登录" }) {},
        )
        else -> listOf(
            ActionItem("版本", { "v${BuildConfig.VERSION_NAME}，点击检查更新" }) { OtaService.checkUpdate(this) },
            HeaderItem("Simple Live TV 原生版，项目地址 github.com/mmooyyii/dart_simple_live"),
        )
    }

    private fun manageShieldWords() {
        val words = Store.shieldWords()
        val items = listOf("➕ 添加屏蔽词") + words
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("屏蔽词（选择已有的词可删除，/正则/ 形式按正则匹配）")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    Dialogs.input(this, "添加屏蔽词", "关键词，或 /正则/") { word ->
                        if (word.isNotEmpty()) Store.addShieldWord(word)
                        SettingViews.refreshAll(b.content)
                    }
                } else {
                    val word = words[which - 1]
                    Dialogs.confirm(this, "删除屏蔽词", "删除「$word」？") {
                        if (it) Store.removeShieldWord(word)
                        SettingViews.refreshAll(b.content)
                    }
                }
            }
            .show()
    }
}
