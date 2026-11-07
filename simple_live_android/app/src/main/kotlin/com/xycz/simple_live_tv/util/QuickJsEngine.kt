package com.xycz.simple_live_tv.util

import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import com.xycz.simplelive.core.common.JsEngine

/** QuickJS 实现的 JS 引擎；每次执行新建上下文，执行完销毁（QuickJS 不是线程安全的） */
class QuickJsEngine : JsEngine {
    init {
        QuickJSLoader.init()
    }

    @Synchronized
    override fun evaluate(scripts: List<String>, expression: String): String {
        val context = QuickJSContext.create()
        try {
            context.setMaxStackSize(512 * 1024)
            for (script in scripts) context.evaluate(script)
            return context.evaluate(expression)?.toString() ?: ""
        } finally {
            context.destroy()
        }
    }
}
