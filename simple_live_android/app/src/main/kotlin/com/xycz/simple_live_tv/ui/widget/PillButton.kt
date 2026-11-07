package com.xycz.simple_live_tv.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import com.xycz.simple_live_tv.R
import com.xycz.simple_live_tv.util.tvFocus

/** 胶囊按钮：聚焦/选中时白底黑字，可带 drawableStart 图标 */
class PillButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {
    init {
        setBackgroundResource(R.drawable.bg_focus_pill)
        val h = (16 * resources.displayMetrics.density).toInt()
        val v = (8 * resources.displayMetrics.density).toInt()
        setPadding(h, v, h, v)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_button))
        setTextColor(context.getColorStateList(R.color.text_focus))
        compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
        TextViewCompat.setCompoundDrawableTintList(this, context.getColorStateList(R.color.text_focus))
        isClickable = true
        isFocusable = true
        maxLines = 1
        tvFocus(1.06f)
    }

    fun setIcon(res: Int) {
        val size = (18 * resources.displayMetrics.density).toInt()
        val d = context.getDrawable(res)?.mutate()?.apply { setBounds(0, 0, size, size) }
        setCompoundDrawablesRelative(d, null, null, null)
        TextViewCompat.setCompoundDrawableTintList(this, context.getColorStateList(R.color.text_focus))
    }
}
