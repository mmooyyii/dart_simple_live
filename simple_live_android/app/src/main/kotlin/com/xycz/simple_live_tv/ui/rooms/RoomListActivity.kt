package com.xycz.simple_live_tv.ui.rooms

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.xycz.simple_live_tv.data.Sites
import com.xycz.simple_live_tv.databinding.ActivityListBinding
import com.xycz.simple_live_tv.ui.BaseActivity
import com.xycz.simple_live_tv.ui.ListPage
import com.xycz.simple_live_tv.ui.Nav
import com.xycz.simple_live_tv.ui.RoomAdapter
import com.xycz.simple_live_tv.ui.RoomCard
import com.xycz.simplelive.core.model.LiveSubCategory

/** 热门直播；传入分类参数时为分类详情 */
class RoomListActivity : BaseActivity() {
    private lateinit var b: ActivityListBinding
    private lateinit var page: ListPage<RoomCard>
    private val adapter = RoomAdapter({ Nav.openRoom(this, it.siteId, it.roomId) }, { page.loadMore() })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setOnClickListener { finish() }

        val siteId = intent.getStringExtra(EXTRA_SITE)
        val category = intent.getStringExtra(EXTRA_CATEGORY_ID)?.let {
            LiveSubCategory(
                id = it,
                name = intent.getStringExtra(EXTRA_CATEGORY_NAME) ?: "",
                parentId = intent.getStringExtra(EXTRA_CATEGORY_PARENT) ?: "",
            )
        }
        b.title.text = category?.name ?: "热门直播"

        b.list.layoutManager = GridLayoutManager(this, 5)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        page = ListPage(
            b,
            lifecycleScope,
            load = { site, p ->
                val r = if (category != null) {
                    site.site.getCategoryRooms(category, p)
                } else {
                    site.site.getRecommendRooms(p)
                }
                com.xycz.simplelive.core.model.PageResult(
                    r.hasMore,
                    r.items.map { RoomCard(site.id, it.roomId, it.title, it.cover, it.userName, it.online) },
                )
            },
            onPage = { items, _, first -> if (first) adapter.submit(items) else adapter.append(items) },
            itemCount = { adapter.size },
        )
        if (category != null && siteId != null) {
            page.selectSite(Sites[siteId])
        } else {
            page.showTabs()
        }
        page.refresh()
        if (category == null) page.focusTab() else b.list.requestFocus()
    }

    companion object {
        const val EXTRA_SITE = "site"
        const val EXTRA_CATEGORY_ID = "category_id"
        const val EXTRA_CATEGORY_NAME = "category_name"
        const val EXTRA_CATEGORY_PARENT = "category_parent"
    }
}
