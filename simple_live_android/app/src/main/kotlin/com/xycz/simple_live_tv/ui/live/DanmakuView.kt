package com.xycz.simple_live_tv.ui.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * 轻量滚动弹幕：所有弹幕同速从右往左移动；新弹幕找一条"上一条已完全进场"的轨道，
 * 没有空轨道就丢弃（不排队，避免弹幕多时越积越多）
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Item(val text: String, val color: Int, val width: Float, val track: Int, var x: Float)

    private val items = ArrayList<Item>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        isFakeBoldText = true
    }
    private var lastFrame = 0L
    private var running = true

    /** 字号（px） */
    var textSizePx = 40f
        set(value) {
            field = value
            fill.textSize = value
            stroke.textSize = value
        }

    /** 滚过整个屏幕宽度所需秒数 */
    var durationSeconds = 10f

    /** 显示区域占屏幕高度比例 */
    var area = 0.25f

    var opacity = 1f
        set(value) {
            field = value.coerceIn(0.1f, 1f)
            invalidate()
        }

    var strokeWidth = 4f
        set(value) {
            field = value
            stroke.strokeWidth = value
        }

    private val lineHeight get() = textSizePx * 1.25f
    private val trackCount get() = ((height * area) / lineHeight).toInt().coerceAtLeast(1)

    init {
        textSizePx = 40f
        strokeWidth = 4f
    }

    fun add(text: String, color: Int) {
        if (!running || width == 0 || visibility != VISIBLE) return
        val textWidth = fill.measureText(text)
        val gap = textSizePx
        // 找一条空闲轨道：该轨道最后一条弹幕已经完全进入屏幕
        val lastInTrack = arrayOfNulls<Item>(trackCount)
        for (item in items) {
            if (item.track < lastInTrack.size) {
                val last = lastInTrack[item.track]
                if (last == null || item.x + item.width > last.x + last.width) lastInTrack[item.track] = item
            }
        }
        var track = -1
        for (i in lastInTrack.indices) {
            val last = lastInTrack[i]
            if (last == null || last.x + last.width + gap < width) {
                track = i
                break
            }
        }
        if (track < 0) return
        items += Item(text, color or 0xFF000000.toInt(), textWidth, track, width.toFloat())
        if (items.size == 1) {
            lastFrame = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }
    }

    fun clear() {
        items.clear()
        invalidate()
    }

    fun setRunning(value: Boolean) {
        running = value
        if (!value) clear()
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame).coerceIn(0, 100)) / 1000f
        lastFrame = now
        val speed = width / durationSeconds
        val alpha = (opacity * 255).toInt()
        stroke.alpha = alpha
        val baselineOffset = -fill.fontMetrics.ascent
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            item.x -= speed * dt
            if (item.x + item.width < 0) {
                iterator.remove()
                continue
            }
            val y = item.track * lineHeight + baselineOffset + 4
            if (strokeWidth > 0) canvas.drawText(item.text, item.x, y, stroke)
            fill.color = item.color
            fill.alpha = alpha
            canvas.drawText(item.text, item.x, y, fill)
        }
        if (items.isNotEmpty()) postInvalidateOnAnimation()
    }
}
