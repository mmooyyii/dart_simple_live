package com.xycz.simple_live_tv.ui.search

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.xycz.simple_live_tv.databinding.ActivityListBinding
import com.xycz.simple_live_tv.ui.AnchorAdapter
import com.xycz.simple_live_tv.ui.AnchorCard
import com.xycz.simple_live_tv.ui.BaseActivity
import com.xycz.simple_live_tv.ui.ListPage
import com.xycz.simple_live_tv.ui.Nav
import com.xycz.simple_live_tv.ui.RoomAdapter
import com.xycz.simple_live_tv.ui.RoomCard
import com.xycz.simplelive.core.model.PageResult

class SearchActivity : BaseActivity() {
    private lateinit var b: ActivityListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setOnClickListener { finish() }
        val keyword = intent.getStringExtra(EXTRA_KEYWORD) ?: ""
        val anchorMode = intent.getBooleanExtra(EXTRA_ANCHOR, false)
        b.title.text = "${if (anchorMode) "搜索主播" else "搜索直播间"}：$keyword"
        if (anchorMode) setupAnchors(keyword) else setupRooms(keyword)
    }

    private fun setupRooms(keyword: String) {
        lateinit var page: ListPage<RoomCard>
        val adapter = RoomAdapter({ Nav.openRoom(this, it.siteId, it.roomId) }, { page.loadMore() })
        b.list.layoutManager = GridLayoutManager(this, 5)
        b.list.adapter = adapter
        b.list.itemAnimator = null
        page = ListPage(
            b,
            lifecycleScope,
            load = { site, p ->
                val r = site.site.searchRooms(keyword, p)
                PageResult(r.hasMore, r.items.map { RoomCard(site.id, it.roomId, it.title, it.cover, it.userName, it.online) })
            },
            onPage = { items, _, first -> if (first) adapter.submit(items) else adapter.append(items) },
            itemCount = { adapter.size },
        )
        page.showTabs()
        page.refresh()
        page.focusTab()
    }

    private fun setupAnchors(keyword: String) {
        lateinit var page: ListPage<AnchorCard>
        val adapter = AnchorAdapter({ Nav.openRoom(this, it.siteId, it.roomId) }, { page.loadMore() })
        b.list.layoutManager = GridLayoutManager(this, 3)
        b.list.adapter = adapter
        b.list.itemAnimator = null
        page = ListPage(
            b,
            lifecycleScope,
            load = { site, p ->
                val r = site.site.searchAnchors(keyword, p)
                PageResult(
                    r.hasMore,
                    r.items.map { AnchorCard(site.id, it.roomId, it.userName, it.avatar, if (it.liveStatus) 2 else 1) },
                )
            },
            onPage = { items, _, first -> if (first) adapter.submit(items) else adapter.append(items) },
            itemCount = { adapter.size },
        )
        page.showTabs()
        page.refresh()
        page.focusTab()
    }

    companion object {
        const val EXTRA_KEYWORD = "keyword"
        const val EXTRA_ANCHOR = "anchor"
    }
}
