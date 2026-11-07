package com.xycz.simple_live_tv.util

import android.content.Context
import android.view.View
import android.widget.Toast
import com.xycz.simple_live_tv.App
import java.util.Locale

/** 焦点放大效果，同时保留原来的焦点监听 */
fun View.tvFocus(scale: Float = 1.08f, onChange: ((Boolean) -> Unit)? = null) {
    isFocusable = true
    setOnFocusChangeListener { v, hasFocus ->
        v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
            .setDuration(150).start()
        v.translationZ = if (hasFocus) 8f else 0f
        onChange?.invoke(hasFocus)
    }
}

fun toast(msg: String) {
    Toast.makeText(App.instance, msg, Toast.LENGTH_SHORT).show()
}

val Int.dp: Int get() = (this * App.instance.resources.displayMetrics.density + 0.5f).toInt()

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

/** 在线人数格式化：12345 -> 1.2万 */
fun formatOnline(count: Long): String = when {
    count >= 100_000_000 -> String.format(Locale.US, "%.1f亿", count / 100_000_000.0)
    count >= 10_000 -> String.format(Locale.US, "%.1f万", count / 10_000.0)
    else -> count.toString()
}

fun errorMessage(e: Throwable): String = e.message ?: e.javaClass.simpleName
