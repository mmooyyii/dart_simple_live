package com.xycz.simple_live_tv.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 覆盖安装 Flutter 版后，把旧数据（关注、观看记录、屏蔽词、B站登录、部分设置）导入新存储。
 * 只读不删旧文件，迁移失败下次启动还会重试。
 */
object HiveMigration {
    private const val TAG = "HiveMigration"

    fun migrateIfNeeded(context: Context) {
        if (Settings.hiveMigrated) return
        val dir = context.getDir("flutter", Context.MODE_PRIVATE)
        if (!File(dir, "tvfollowuser.hive").exists() && !File(dir, "tvlocalstorage.hive").exists()) {
            Settings.hiveMigrated = true
            return
        }
        try {
            migrate(dir)
            Settings.hiveMigrated = true
        } catch (e: Exception) {
            Log.e(TAG, "迁移旧数据失败", e)
        }
    }

    fun migrate(dir: File): String {
        val records = mutableListOf<SyncRecord>()

        val follows = HiveReader.readBox(File(dir, "tvfollowuser.hive")).values.filterIsInstance<HiveObject>()
        for (obj in follows) {
            val f = obj.fields
            val user = FollowUser(
                id = f[0] as String,
                roomId = f[1] as String,
                siteId = f[2] as String,
                userName = f[3] as? String ?: "",
                face = f[4] as? String ?: "",
                addTime = (f[5] as? HiveDateTime)?.millis ?: System.currentTimeMillis(),
            )
            records += Store.followRecord(user)
        }

        val histories = HiveReader.readBox(File(dir, "tvhostiry.hive")).values.filterIsInstance<HiveObject>()
        for (obj in histories) {
            val f = obj.fields
            records += Store.historyRecord(
                HistoryItem(
                    id = f[0] as String,
                    roomId = f[1] as String,
                    siteId = f[2] as String,
                    userName = f[3] as? String ?: "",
                    face = f[4] as? String ?: "",
                    updateTime = (f[5] as? HiveDateTime)?.millis ?: 0,
                ),
            )
        }

        val now = System.currentTimeMillis()
        val shields = HiveReader.readBox(File(dir, "tvdanmushield.hive")).values.filterIsInstance<String>()
        shields.forEach { records += Store.shieldRecord(it, now) }

        val local = HiveReader.readBox(File(dir, "tvlocalstorage.hive"))
        (local["BilibiliCookie"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            records += Store.accountRecord("bilibili", it, now)
        }
        Store.importRecords(records)

        // 设置项
        (local["DanmuEnable"] as? Boolean)?.let { Settings.danmuEnable = it }
        (local["DanmuSize"] as? Number)?.let { Settings.danmuSize = it.toFloat() }
        (local["DanmuSpeed"] as? Number)?.let { Settings.danmuSpeed = it.toFloat() }
        (local["DanmuArea"] as? Number)?.let { Settings.danmuArea = it.toFloat() }
        (local["DanmuOpacity"] as? Number)?.let { Settings.danmuOpacity = it.toFloat() }
        (local["DanmuStrokeWidth"] as? Number)?.let { Settings.danmuStrokeWidth = it.toFloat() }
        (local["HardwareDecode"] as? Boolean)?.let { Settings.hardwareDecode = it }
        (local["ScaleMode"] as? Number)?.let { Settings.scaleMode = it.toInt() }
        (local["AutoUpdateFollowEnable"] as? Boolean)?.let { Settings.autoUpdateFollowEnable = it }
        (local["AutoUpdateFollowDuration"] as? Number)?.let { Settings.autoUpdateFollowDuration = it.toInt() }
        (local["UpdateFollowThreadCount"] as? Number)?.let { Settings.updateFollowThreadCount = it.toInt() }
        (local["BilibiliLoginTip"] as? Boolean)?.let { Settings.bilibiliLoginTip = it }
        (local["FirstRun"] as? Boolean)?.let { Settings.firstRun = it }

        val summary = "迁移完成：关注 ${follows.size}，记录 ${histories.size}，屏蔽词 ${shields.size}，" +
            "B站登录 ${if (local["BilibiliCookie"] is String) "有" else "无"}"
        Log.i(TAG, summary)
        return summary
    }
}
