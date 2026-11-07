package com.xycz.simple_live_tv.ui.category

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.xycz.simple_live_tv.data.SiteInfo
import com.xycz.simple_live_tv.databinding.ActivityListBinding
import com.xycz.simple_live_tv.databinding.ItemCategoryBinding
import com.xycz.simple_live_tv.ui.BaseActivity
import com.xycz.simple_live_tv.ui.ListPage
import com.xycz.simple_live_tv.ui.rooms.RoomListActivity
import com.xycz.simple_live_tv.util.dp
import com.xycz.simple_live_tv.util.tvFocus
import com.xycz.simplelive.core.model.LiveCategory
import com.xycz.simplelive.core.model.LiveSubCategory
import com.xycz.simplelive.core.model.PageResult

class CategoryActivity : BaseActivity() {
    private lateinit var b: ActivityListBinding
    private val adapter = CategoryAdapter()
    private var categories: List<LiveCategory> = emptyList()
    private val expanded = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setOnClickListener { finish() }
        b.title.text = "直播类目"

        val layoutManager = GridLayoutManager(this, SPAN)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.items[position] is Row.Header) SPAN else 1
        }
        b.list.layoutManager = layoutManager
        b.list.adapter = adapter
        b.list.itemAnimator = null

        val page = ListPage(
            b,
            lifecycleScope,
            load = { site, p ->
                if (p > 1) PageResult(false, emptyList()) else PageResult(false, site.site.getCategories())
            },
            onPage = { items, site, first ->
                if (first) {
                    categories = items
                    expanded.clear()
                    render(site)
                }
            },
            itemCount = { adapter.itemCount },
        )
        page.showTabs()
        page.refresh()
        page.focusTab()
        adapter.onSub = { sub, site ->
            startActivity(
                Intent(this, RoomListActivity::class.java)
                    .putExtra(RoomListActivity.EXTRA_SITE, site.id)
                    .putExtra(RoomListActivity.EXTRA_CATEGORY_ID, sub.id)
                    .putExtra(RoomListActivity.EXTRA_CATEGORY_NAME, sub.name)
                    .putExtra(RoomListActivity.EXTRA_CATEGORY_PARENT, sub.parentId),
            )
        }
        adapter.onShowAll = { category, site ->
            expanded += category.id
            render(site)
        }
        this.page = page
    }

    private lateinit var page: ListPage<LiveCategory>

    private fun render(site: SiteInfo) {
        val rows = mutableListOf<Row>()
        for (c in categories) {
            if (c.children.isEmpty()) continue
            rows += Row.Header(c.name)
            val collapse = c.children.size >= 19 && c.id !in expanded
            val subs = if (collapse) c.children.take(15) else c.children
            subs.forEach { rows += Row.Sub(it, site) }
            if (collapse) rows += Row.ShowAll(c, site)
        }
        adapter.submit(rows)
    }

    sealed class Row {
        data class Header(val name: String) : Row()
        data class Sub(val sub: LiveSubCategory, val site: SiteInfo) : Row()
        data class ShowAll(val category: LiveCategory, val site: SiteInfo) : Row()
    }

    class CategoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Row> = emptyList()
        var onSub: (LiveSubCategory, SiteInfo) -> Unit = { _, _ -> }
        var onShowAll: (LiveCategory, SiteInfo) -> Unit = { _, _ -> }

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Row>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = if (items[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val tv = TextView(parent.context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 18.dp.toFloat())
                    setPadding(8.dp, 16.dp, 8.dp, 6.dp)
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            val b = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.root.tvFocus(1.1f)
            return CatHolder(b)
        }

        class CatHolder(val b: ItemCategoryBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.name
                is Row.Sub -> {
                    val b = (holder as CatHolder).b
                    b.name.text = row.sub.name
                    val pic = row.sub.pic
                    if (pic.isNullOrEmpty()) {
                        b.icon.setImageResource(row.site.logo)
                    } else {
                        b.icon.load(pic) {
                            size(64, 64)
                            error(row.site.logo)
                        }
                    }
                    b.root.setOnClickListener { onSub(row.sub, row.site) }
                }
                is Row.ShowAll -> {
                    val b = (holder as CatHolder).b
                    b.name.text = "显示全部"
                    b.icon.setImageResource(com.xycz.simple_live_tv.R.drawable.ic_apps)
                    b.root.setOnClickListener { onShowAll(row.category, row.site) }
                }
            }
        }
    }

    companion object {
        private const val SPAN = 8
    }
}
