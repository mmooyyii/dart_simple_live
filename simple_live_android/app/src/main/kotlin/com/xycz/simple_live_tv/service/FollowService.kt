package com.xycz.simple_live_tv.service

import android.os.Handler
import android.os.Looper
import com.xycz.simple_live_tv.data.FollowUser
import com.xycz.simple_live_tv.data.Settings
import com.xycz.simple_live_tv.data.Sites
import com.xycz.simple_live_tv.data.Store
import com.xycz.simplelive.core.common.CoreLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** 关注列表的直播状态：0=未知 1=未开播 2=直播中 */
object FollowService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val status = ConcurrentHashMap<String, Int>()
    private val listeners = mutableListOf<() -> Unit>()

    @Volatile
    var updating = false
        private set

    @Volatile
    private var batch = 0

    private val timer = object : Runnable {
        override fun run() {
            refresh()
            schedule()
        }
    }

    fun init() {
        // 关注/取关/同步后，只查还没有状态的用户
        Store.listen { type -> if (type == Store.TYPE_FOLLOW) refresh(onlyUnknown = true) }
        refresh()
        schedule()
    }

    /** 自动刷新设置变化后重新计时 */
    fun schedule() {
        main.removeCallbacks(timer)
        if (Settings.autoUpdateFollowEnable) {
            main.postDelayed(timer, Settings.autoUpdateFollowDuration * 60_000L)
        }
    }

    /** 状态变化监听（主线程），返回取消函数 */
    fun listen(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

    @Volatile
    private var notifyPending = false

    /** 逐条更新时合并通知，最多 0.5 秒刷新一次界面 */
    private fun notifyThrottled() {
        if (notifyPending) return
        notifyPending = true
        main.postDelayed({
            notifyPending = false
            listeners.toList().forEach { it() }
        }, 500)
    }

    fun statusOf(id: String): Int = status[id] ?: 0

    /** 关注列表：直播中在前 */
    fun follows(): List<FollowUser> {
        val list = Store.follows()
        return list.sortedByDescending { statusOf(it.id) }
    }

    fun livingList(): List<FollowUser> = follows().filter { statusOf(it.id) == 2 }

    fun refresh(onlyUnknown: Boolean = false) {
        val list = Store.follows().filter { !onlyUnknown || !status.containsKey(it.id) }
        if (list.isEmpty()) {
            if (!onlyUnknown) {
                updating = false
                notifyChanged()
            }
            return
        }
        val current = ++batch
        updating = true
        notifyChanged()
        val queue = ConcurrentLinkedQueue(list)
        scope.launch {
            val workers = List(Settings.updateFollowThreadCount.coerceAtLeast(1)) {
                launch {
                    while (current == batch) {
                        val item = queue.poll() ?: break
                        try {
                            val live = Sites[item.siteId].site.getLiveStatus(item.roomId)
                            status[item.id] = if (live) 2 else 1
                            notifyThrottled()
                        } catch (e: Exception) {
                            CoreLog.e("获取直播状态失败 ${item.id}", e)
                        }
                    }
                }
            }
            workers.joinAll()
            if (current == batch) {
                updating = false
                notifyChanged()
            }
        }
    }
}
