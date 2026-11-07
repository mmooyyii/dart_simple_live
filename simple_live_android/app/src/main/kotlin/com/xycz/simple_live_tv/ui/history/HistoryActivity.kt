package com.xycz.simple_live_tv.ui.history

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import com.xycz.simple_live_tv.R
import com.xycz.simple_live_tv.data.Store
import com.xycz.simple_live_tv.databinding.ActivityListBinding
import com.xycz.simple_live_tv.ui.AnchorAdapter
import com.xycz.simple_live_tv.ui.AnchorCard
import com.xycz.simple_live_tv.ui.BaseActivity
import com.xycz.simple_live_tv.ui.Dialogs
import com.xycz.simple_live_tv.ui.Nav

class HistoryActivity : BaseActivity() {
    private lateinit var b: ActivityListBinding
    private val adapter = AnchorAdapter({ Nav.openRoom(this, it.siteId, it.roomId) })
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setIcon(R.drawable.ic_arrow_back)
        b.back.setOnClickListener { finish() }
        b.title.text = "观看记录"
        b.action.visibility = View.VISIBLE
        b.action.text = "清空"
        b.action.setIcon(R.drawable.ic_delete)
        b.action.setOnClickListener {
            Dialogs.confirm(this, "清空观看记录", "确定要清空观看记录吗？") { if (it) Store.clearHistory() }
        }
        b.list.layoutManager = GridLayoutManager(this, 3)
        b.list.adapter = adapter
        b.list.itemAnimator = null
        unsubscribe = Store.listen { if (it == Store.TYPE_HISTORY) render() }
        render()
        b.list.post { b.list.getChildAt(0)?.requestFocus() ?: b.back.requestFocus() }
    }

    private fun render() {
        val list = Store.histories().map { AnchorCard(it.siteId, it.roomId, it.userName, it.face, null) }
        adapter.submit(list)
        b.statusBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.progress.visibility = View.GONE
        b.retry.visibility = View.GONE
        b.status.text = "暂无观看记录"
    }

    override fun onDestroy() {
        unsubscribe?.invoke()
        super.onDestroy()
    }
}
