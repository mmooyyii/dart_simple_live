package com.xycz.simplelive.core.common

/** 核心库日志，由 App 注入输出方式（Android 用 Log，单测用 println） */
object CoreLog {
    var enabled = true
    var printer: (level: Char, msg: String) -> Unit = { level, msg -> println("[$level] $msg") }

    fun d(msg: String) = log('D', msg)
    fun i(msg: String) = log('I', msg)
    fun w(msg: String) = log('W', msg)
    fun e(msg: String, t: Throwable? = null) =
        log('E', if (t == null) msg else "$msg: ${t.stackTraceToString()}")

    private fun log(level: Char, msg: String) {
        if (enabled) printer(level, msg)
    }
}
