package com.xycz.simple_live_tv.data

import android.content.Context
import android.content.SharedPreferences

/** 设置项，键名和默认值沿用 Flutter 版 */
object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    private fun int(key: String, def: Int) = prefs.getInt(key, def)
    private fun float(key: String, def: Float) = prefs.getFloat(key, def)
    private fun string(key: String, def: String) = prefs.getString(key, def) ?: def

    private fun set(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
            }
        }.apply()
    }

    var firstRun: Boolean
        get() = bool("FirstRun", true)
        set(v) = set("FirstRun", v)

    // 弹幕
    var danmuEnable: Boolean
        get() = bool("DanmuEnable", true)
        set(v) = set("DanmuEnable", v)
    var danmuSize: Float
        get() = float("DanmuSize", 40f)
        set(v) = set("DanmuSize", v)
    var danmuSpeed: Float
        get() = float("DanmuSpeed", 10f)
        set(v) = set("DanmuSpeed", v)
    var danmuArea: Float
        get() = float("DanmuArea", 0.25f)
        set(v) = set("DanmuArea", v)
    var danmuOpacity: Float
        get() = float("DanmuOpacity", 1f)
        set(v) = set("DanmuOpacity", v)
    var danmuStrokeWidth: Float
        get() = float("DanmuStrokeWidth", 4f)
        set(v) = set("DanmuStrokeWidth", v)

    /** 每秒最多显示的弹幕条数 */
    var danmuMaxPerSecond: Int
        get() = int("DanmuMaxPerSecond", 25)
        set(v) = set("DanmuMaxPerSecond", v)

    // 播放
    var hardwareDecode: Boolean
        get() = bool("HardwareDecode", true)
        set(v) = set("HardwareDecode", v)

    /** 画面比例 0=适应 1=拉伸 2=铺满 3=16:9 4=4:3 */
    var scaleMode: Int
        get() = int("ScaleMode", 0)
        set(v) = set("ScaleMode", v)

    /** 默认清晰度 0=最低 1=中等 2=最高 */
    var qualityLevel: Int
        get() = int("QualityLevel", 2)
        set(v) = set("QualityLevel", v)

    // 关注
    var autoUpdateFollowEnable: Boolean
        get() = bool("AutoUpdateFollowEnable", true)
        set(v) = set("AutoUpdateFollowEnable", v)
    var autoUpdateFollowDuration: Int
        get() = int("AutoUpdateFollowDuration", 10)
        set(v) = set("AutoUpdateFollowDuration", v)
    var updateFollowThreadCount: Int
        get() = int("UpdateFollowThreadCount", 4)
        set(v) = set("UpdateFollowThreadCount", v)

    // 账号
    var bilibiliLoginTip: Boolean
        get() = bool("BilibiliLoginTip", true)
        set(v) = set("BilibiliLoginTip", v)

    // 局域网同步
    var syncEnable: Boolean
        get() = bool("SyncEnable", true)
        set(v) = set("SyncEnable", v)

    /** 设备在 gossip 同步里的固定 id */
    val nodeId: String
        get() {
            var id = string("NodeId", "")
            if (id.isEmpty()) {
                id = java.util.UUID.randomUUID().toString().substringBefore('-')
                set("NodeId", id)
            }
            return id
        }

    /** 旧版 Hive 数据是否已迁移 */
    var hiveMigrated: Boolean
        get() = bool("HiveMigrated", false)
        set(v) = set("HiveMigrated", v)
}
