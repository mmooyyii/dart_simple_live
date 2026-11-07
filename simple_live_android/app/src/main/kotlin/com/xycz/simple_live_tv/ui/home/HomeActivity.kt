package com.xycz.simple_live_tv.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.xycz.simple_live_tv.R
import com.xycz.simple_live_tv.data.Store
import com.xycz.simple_live_tv.databinding.ActivityHomeBinding
import com.xycz.simple_live_tv.databinding.DialogSearchBinding
import com.xycz.simple_live_tv.databinding.ViewBigButtonBinding
import com.xycz.simple_live_tv.service.FollowService
import com.xycz.simple_live_tv.service.SyncService
import com.xycz.simple_live_tv.ui.AnchorAdapter
import com.xycz.simple_live_tv.ui.AnchorCard
import com.xycz.simple_live_tv.ui.BaseActivity
import com.xycz.simple_live_tv.ui.Dialogs
import com.xycz.simple_live_tv.ui.Nav
import com.xycz.simple_live_tv.ui.category.CategoryActivity
import com.xycz.simple_live_tv.ui.history.HistoryActivity
import com.xycz.simple_live_tv.ui.rooms.RoomListActivity
import com.xycz.simple_live_tv.ui.search.SearchActivity
import com.xycz.simple_live_tv.ui.settings.SettingsActivity
import com.xycz.simple_live_tv.ui.sync.SyncActivity
import com.xycz.simple_live_tv.util.Qr
import com.xycz.simple_live_tv.util.toast
import com.xycz.simple_live_tv.util.tvFocus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : BaseActivity() {
    private lateinit var b: ActivityHomeBinding
    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clock = object : Runnable {
        override fun run() {
            b.clock.text = clockFormat.format(Date())
            handler.postDelayed(this, 10_000)
        }
    }
    private lateinit var adapter: AnchorAdapter
    private val unsubscribes = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.settings.setIcon(R.drawable.ic_settings)
        b.settings.onClick { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.refresh.setIcon(R.drawable.ic_refresh)
        b.refresh.onClick { FollowService.refresh() }
        b.manage.setIcon(R.drawable.ic_edit)
        b.manage.onClick { manageFollows() }

        addBigButton(R.drawable.ic_whatshot, "热门直播") {
            startActivity(Intent(this, RoomListActivity::class.java))
        }.requestFocus()
        addBigButton(R.drawable.ic_apps, "直播类目") { startActivity(Intent(this, CategoryActivity::class.java)) }
        addBigButton(R.drawable.ic_search, "搜索直播") { showSearch() }
        addBigButton(R.drawable.ic_history, "观看记录") { startActivity(Intent(this, HistoryActivity::class.java)) }
        addBigButton(R.drawable.ic_devices, "数据同步") { startActivity(Intent(this, SyncActivity::class.java)) }

        adapter = AnchorAdapter({ Nav.openRoom(this, it.siteId, it.roomId) })
        b.follows.layoutManager = GridLayoutManager(this, 3)
        b.follows.adapter = adapter
        b.follows.itemAnimator = null

        unsubscribes += FollowService.listen { renderFollows() }
        unsubscribes += Store.listen { if (it == Store.TYPE_FOLLOW) renderFollows() }
        renderFollows()
    }

    private fun addBigButton(icon: Int, text: String, action: () -> Unit): View {
        val bb = ViewBigButtonBinding.inflate(layoutInflater, b.bigButtons, false)
        bb.icon.setImageResource(icon)
        bb.text.text = text
        bb.root.tvFocus(1.06f)
        bb.root.setOnClickListener { action() }
        b.bigButtons.addView(bb.root)
        return bb.root
    }

    private fun renderFollows() {
        val list = FollowService.follows().map {
            AnchorCard(it.siteId, it.roomId, it.userName, it.face, FollowService.statusOf(it.id))
        }
        adapter.submit(list)
        b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.updating.visibility = if (FollowService.updating) View.VISIBLE else View.GONE
    }

    private fun manageFollows() {
        val list = FollowService.follows()
        if (list.isEmpty()) {
            toast("关注列表为空")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("取消关注（选择主播）")
            .setItems(list.map { it.userName }.toTypedArray()) { _, which ->
                val user = list[which]
                Dialogs.confirm(this, "取消关注", "确定要取消关注 ${user.userName} 吗？") { ok ->
                    if (ok) {
                        Store.unfollow(user.id)
                        manageFollows()
                    }
                }
            }
            .show()
    }

    private fun showSearch() {
        val db = DialogSearchBinding.inflate(LayoutInflater.from(this))
        var anchorMode = false
        fun renderMode() {
            db.modeRoom.isSelected = !anchorMode
            db.modeAnchor.isSelected = anchorMode
            db.keyword.hint = if (anchorMode) "输入主播昵称或抖音直播间链接" else "输入关键字或抖音直播间链接"
        }
        db.modeRoom.setIcon(R.drawable.ic_live_tv)
        db.modeAnchor.setIcon(R.drawable.ic_person)
        db.modeRoom.setOnClickListener { anchorMode = false; renderMode() }
        db.modeAnchor.setOnClickListener { anchorMode = true; renderMode() }
        renderMode()
        val url = SyncService.openPageUrl()
        if (url == null) {
            db.qrBox.visibility = View.GONE
        } else {
            db.qr.setImageBitmap(Qr.bitmap(url, 300))
        }
        val dialog = AlertDialog.Builder(this).setView(db.root).create()
        db.keyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                val text = db.keyword.text.toString().trim()
                dialog.dismiss()
                if (text.isNotEmpty()) search(text, anchorMode)
                true
            } else {
                false
            }
        }
        dialog.show()
        db.keyword.requestFocus()
    }

    private fun search(text: String, anchorMode: Boolean) {
        if (text.contains("douyin.com")) {
            lifecycleScope.launch {
                val loading = Dialogs.loading(this@HomeActivity)
                val error = Nav.openDouyinUrl(this@HomeActivity, text)
                loading.dismiss()
                error?.let { toast(it) }
            }
            return
        }
        startActivity(
            Intent(this, SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_KEYWORD, text)
                .putExtra(SearchActivity.EXTRA_ANCHOR, anchorMode),
        )
    }

    override fun onResume() {
        super.onResume()
        handler.post(clock)
        renderFollows()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clock)
    }

    override fun onDestroy() {
        unsubscribes.forEach { it() }
        super.onDestroy()
    }
}
