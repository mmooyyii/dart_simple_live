package com.xycz.simple_live_tv.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.xycz.simple_live_tv.R
import com.xycz.simple_live_tv.util.dp

object Dialogs {
    fun confirm(
        context: Context,
        title: String,
        message: String,
        ok: String = "确定",
        cancel: String = "取消",
        onResult: (Boolean) -> Unit,
    ) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(ok) { _, _ -> onResult(true) }
            .setNegativeButton(cancel) { _, _ -> onResult(false) }
            .create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
        dialog.show()
    }

    fun message(context: Context, title: String, message: String) {
        AlertDialog.Builder(context).setTitle(title).setMessage(message).setPositiveButton("确定", null).show()
    }

    /** 单选列表，默认焦点在当前选中项 */
    fun <T> options(
        context: Context,
        title: String,
        items: List<Pair<T, String>>,
        selected: T?,
        onSelect: (T) -> Unit,
    ) {
        val index = items.indexOfFirst { it.first == selected }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(items.map { it.second }.toTypedArray(), index) { d, which ->
                d.dismiss()
                onSelect(items[which].first)
            }
            .show()
    }

    fun input(context: Context, title: String, hint: String, onInput: (String) -> Unit) {
        val edit = EditText(context).apply {
            this.hint = hint
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val container = FrameLayout(context).apply {
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(edit)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ -> onInput(edit.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .create()
        edit.setOnEditorActionListener { _, _, _ ->
            dialog.dismiss()
            onInput(edit.text.toString().trim())
            true
        }
        dialog.show()
    }

    /** 加载中遮罩 */
    fun loading(context: Context, text: String = ""): Dialog {
        val dialog = Dialog(context, R.style.AppDialog)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 16.dp, 24.dp, 16.dp)
            addView(ProgressBar(context), LinearLayout.LayoutParams(32.dp, 32.dp))
            if (text.isNotEmpty()) {
                addView(
                    TextView(context).apply {
                        this.text = text
                        setPadding(16.dp, 0, 0, 0)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        }
        dialog.setContentView(layout)
        dialog.setCancelable(true)
        dialog.show()
        return dialog
    }
}
