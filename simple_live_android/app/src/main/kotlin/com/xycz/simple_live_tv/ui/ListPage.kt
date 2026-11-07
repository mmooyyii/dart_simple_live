package com.xycz.simple_live_tv.ui

import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import com.xycz.simple_live_tv.R
import com.xycz.simple_live_tv.data.SiteInfo
import com.xycz.simple_live_tv.data.Sites
import com.xycz.simple_live_tv.databinding.ActivityListBinding
import com.xycz.simple_live_tv.ui.widget.PillButton
import com.xycz.simple_live_tv.util.errorMessage
import com.xycz.simplelive.core.common.CoreLog
import com.xycz.simplelive.core.model.PageResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 列表页通用逻辑：平台标签、分页加载、加载中/空/错误状态
 */
class ListPage<T>(
    private val b: ActivityListBinding,
    private val scope: LifecycleCoroutineScope,
    private val load: suspend (site: SiteInfo, page: Int) -> PageResult<T>,
    private val onPage: (items: List<T>, site: SiteInfo, first: Boolean) -> Unit,
    private val itemCount: () -> Int,
) {
    var site: SiteInfo = Sites.all.first()
        private set
    private var page = 1
    private var hasMore = true
    private var job: Job? = null
    private val tabButtons = mutableListOf<Pair<SiteInfo, PillButton>>()

    init {
        b.back.setIcon(R.drawable.ic_arrow_back)
        b.retry.setOnClickListener { refresh() }
    }

    fun showTabs(sites: List<SiteInfo> = Sites.all, onChange: ((SiteInfo) -> Unit)? = null) {
        b.tabs.visibility = View.VISIBLE
        for (s in sites) {
            val btn = PillButton(b.root.context).apply {
                text = s.name
                val size = (20 * resources.displayMetrics.density).toInt()
                val logo = context.getDrawable(s.logo)?.apply { setBounds(0, 0, size, size) }
                setCompoundDrawablesRelative(logo, null, null, null)
                // PillButton 会给图标着色，平台 logo 要保留原色
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, null)
                setOnClickListener {
                    if (site != s) {
                        site = s
                        renderTabs()
                        onChange?.invoke(s)
                        refresh()
                    }
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = (12 * btn.resources.displayMetrics.density).toInt()
                marginEnd = marginStart
            }
            b.tabs.addView(btn, lp)
            tabButtons += s to btn
        }
        renderTabs()
    }

    fun selectSite(s: SiteInfo) {
        site = s
        renderTabs()
    }

    fun focusTab() {
        tabButtons.firstOrNull { it.first == site }?.second?.requestFocus()
    }

    private fun renderTabs() {
        tabButtons.forEach { (s, btn) -> btn.isSelected = s == site }
    }

    fun refresh() {
        job?.cancel()
        page = 1
        hasMore = true
        onPage(emptyList(), site, true)
        loadPage()
    }

    /** 列表滚动到接近末尾时调用 */
    fun loadMore() {
        if (job?.isActive == true || !hasMore) return
        loadPage()
    }

    private fun loadPage() {
        val current = site
        val first = page == 1
        if (first) showStatus(loading = true, text = "加载中...")
        job = scope.launch {
            try {
                val result = load(current, page)
                if (current != site) return@launch
                page++
                hasMore = result.hasMore && result.items.isNotEmpty()
                onPage(result.items, current, first)
                if (itemCount() == 0) showStatus(loading = false, text = "这里什么都没有", retry = true) else hideStatus()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CoreLog.e("加载列表失败", e)
                if (first) {
                    showStatus(loading = false, text = "加载失败：${errorMessage(e)}", retry = true)
                } else {
                    hasMore = false
                }
            }
        }
    }

    private fun showStatus(loading: Boolean, text: String, retry: Boolean = false) {
        b.statusBox.visibility = View.VISIBLE
        b.progress.visibility = if (loading) View.VISIBLE else View.GONE
        b.status.text = text
        b.retry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun hideStatus() {
        b.statusBox.visibility = View.GONE
    }
}
