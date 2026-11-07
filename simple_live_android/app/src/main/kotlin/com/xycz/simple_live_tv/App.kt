package com.xycz.simple_live_tv

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.xycz.simple_live_tv.data.HiveMigration
import com.xycz.simple_live_tv.data.Settings
import com.xycz.simple_live_tv.data.ShieldFilter
import com.xycz.simple_live_tv.data.Sites
import com.xycz.simple_live_tv.data.Store
import com.xycz.simple_live_tv.service.FollowService
import com.xycz.simple_live_tv.service.SyncService
import com.xycz.simple_live_tv.util.QuickJsEngine
import com.xycz.simplelive.core.common.CoreLog
import com.xycz.simplelive.core.common.Js
import com.xycz.simplelive.core.site.DeviceCapability

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        CoreLog.enabled = BuildConfig.DEBUG
        CoreLog.printer = { level, msg ->
            val priority = when (level) {
                'E' -> Log.ERROR
                'W' -> Log.WARN
                'I' -> Log.INFO
                else -> Log.DEBUG
            }
            Log.println(priority, "SimpleLive", msg)
        }
        Js.engine = QuickJsEngine()
        DeviceCapability.supportHevc = hasHevcDecoder()
        Settings.init(this)
        Store.init(this)
        HiveMigration.migrateIfNeeded(this)
        Sites.applyAccounts()
        Store.listen { type -> if (type == Store.TYPE_ACCOUNT) Sites.applyAccounts() }
        ShieldFilter.init()
        FollowService.init()
        SyncService.start(this)
    }

    /** 是否有 H.265 硬件解码器（软解在低配设备上跑不动高码率） */
    private fun hasHevcDecoder(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals("video/hevc", true) } &&
                if (Build.VERSION.SDK_INT >= 29) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("OMX.google") && !info.name.startsWith("c2.android")
                }
        }
    } catch (e: Exception) {
        false
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .allowHardware(false)
        .crossfade(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(64L * 1024 * 1024).build() }
        .build()

    companion object {
        lateinit var instance: App
            private set
    }
}
