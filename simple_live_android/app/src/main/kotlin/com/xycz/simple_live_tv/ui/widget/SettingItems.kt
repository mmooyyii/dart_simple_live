package com.xycz.simple_live_tv.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xycz.simple_live_tv.databinding.ItemSettingBinding
import com.xycz.simple_live_tv.ui.Dialogs

/** 设置项 */
sealed class SettingItem(val title: String)

/** 选项型：左右键切换，OK 弹出全部选项 */
class OptionItem<T>(
    title: String,
    val options: () -> List<Pair<T, String>>,
    val get: () -> T,
    val set: (T) -> Unit,
) : SettingItem(title) {
    fun label(): String = options().firstOrNull { it.first == get() }?.second ?: get().toString()
}

/** 动作型：显示一个值，OK 执行动作 */
class ActionItem(
    title: String,
    val value: () -> String,
    val action: () -> Unit,
) : SettingItem(title)

/** 分组标题 */
class HeaderItem(title: String) : SettingItem(title)

object SettingViews {
    /** 生成设置项视图并加到 [parent] */
    fun add(context: Context, parent: ViewGroup, items: List<SettingItem>): List<View> =
        items.map { item -> create(context, parent, item).also { parent.addView(it) } }

    @SuppressLint("SetTextI18n")
    fun create(context: Context, parent: ViewGroup, item: SettingItem): View {
        if (item is HeaderItem) {
            return TextView(context).apply {
                text = item.title
                textSize = 13f
                alpha = 0.7f
                setPadding(8, 24, 8, 8)
            }
        }
        val b = ItemSettingBinding.inflate(LayoutInflater.from(context), parent, false)
        b.label.text = item.title
        fun refresh() {
            b.value.text = when (item) {
                is OptionItem<*> -> "‹  ${item.label()}  ›"
                is ActionItem -> item.value()
                else -> ""
            }
        }
        refresh()
        b.root.tag = Runnable { refresh() }
        when (item) {
            is OptionItem<*> -> {
                @Suppress("UNCHECKED_CAST")
                val opt = item as OptionItem<Any?>
                b.root.setOnClickListener {
                    Dialogs.options(context, item.title, opt.options(), opt.get()) {
                        opt.set(it)
                        refresh()
                    }
                }
                b.root.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val step = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        else -> return@setOnKeyListener false
                    }
                    val options = opt.options()
                    if (options.isEmpty()) return@setOnKeyListener true
                    val i = options.indexOfFirst { it.first == opt.get() }
                    val next = options[((if (i < 0) 0 else i) + step + options.size) % options.size]
                    opt.set(next.first)
                    refresh()
                    true
                }
            }
            is ActionItem -> b.root.setOnClickListener {
                item.action()
                refresh()
            }
            else -> {}
        }
        return b.root
    }

    /** 刷新 [parent] 下所有设置项的显示值 */
    fun refreshAll(parent: ViewGroup) {
        for (i in 0 until parent.childCount) (parent.getChildAt(i).tag as? Runnable)?.run()
    }
}
