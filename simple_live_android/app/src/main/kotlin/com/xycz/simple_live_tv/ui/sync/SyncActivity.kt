package com.xycz.simple_live_tv.ui.sync

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.xycz.simple_live_tv.R
import com.xycz.simple_live_tv.data.Settings
import com.xycz.simple_live_tv.databinding.ActivitySettingsBinding
import com.xycz.simple_live_tv.service.SyncService
import com.xycz.simple_live_tv.ui.BaseActivity
import com.xycz.simple_live_tv.ui.widget.ActionItem
import com.xycz.simple_live_tv.ui.widget.HeaderItem
import com.xycz.simple_live_tv.ui.widget.OptionItem
import com.xycz.simple_live_tv.ui.widget.SettingItem
import com.xycz.simple_live_tv.ui.widget.SettingViews
import com.xycz.simple_live_tv.util.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 局域网同步：同一局域网内的电视自动互相同步关注、记录、屏蔽词、B站登录 */
class SyncActivity : BaseActivity() {
    private lateinit var b: ActivitySettingsBinding
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setIcon(R.drawable.ic_arrow_back)
        b.back.setOnClickListener { finish() }
        b.title.text = "数据同步"
        b.tabs.visibility = View.GONE
        render()
        b.content.getChildAt(0)?.requestFocus()
    }

    private fun items(): List<SettingItem> {
        val list = mutableListOf<SettingItem>(
            OptionItem("局域网自动同步", { listOf(true to "开", false to "关") }, { Settings.syncEnable }, {
                Settings.syncEnable = it
                if (it) SyncService.syncNow()
            }),
            ActionItem("立即同步", { "向已发现的设备同步" }) {
                SyncService.syncNow()
                toast("已开始同步")
            },
            ActionItem("本机地址", { SyncService.localIp()?.let { "$it:${SyncService.HTTP_PORT}" } ?: "未连接网络" }) {},
            HeaderItem("同一局域网内的其他 Simple Live TV 会自动发现并同步关注、观看记录、屏蔽词和B站登录"),
            HeaderItem("已发现的设备"),
        )
        val peers = SyncService.peers.values.sortedByDescending { it.lastSeen }
        if (peers.isEmpty()) {
            list += ActionItem("暂无", { "" }) {}
        } else {
            peers.forEach { p ->
                val sync = if (p.lastSync > 0) "上次同步 ${timeFormat.format(Date(p.lastSync))}" else "未同步"
                list += ActionItem(p.name, { "${p.ip}  $sync" }) {}
            }
        }
        return list
    }

    private fun render() {
        val focusedIndex = (0 until b.content.childCount).firstOrNull { b.content.getChildAt(it).hasFocus() }
        b.content.removeAllViews()
        SettingViews.add(this, b.content, items())
        focusedIndex?.let { b.content.getChildAt(it)?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(refresh, 3000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }
}
