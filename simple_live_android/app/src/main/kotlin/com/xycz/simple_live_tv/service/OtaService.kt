package com.xycz.simple_live_tv.service

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.xycz.simple_live_tv.BuildConfig
import com.xycz.simple_live_tv.ui.Dialogs
import com.xycz.simple_live_tv.util.dp
import com.xycz.simple_live_tv.util.errorMessage
import com.xycz.simple_live_tv.util.toast
import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.common.arr
import com.xycz.simplelive.core.common.get
import com.xycz.simplelive.core.common.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** 应用内更新：从 GitHub Release 下载对应 ABI 的 APK 并调起系统安装（只在设置里手动触发） */
object OtaService {
    private const val REPO = "mmooyyii/dart_simple_live"

    /** TV 版发版 tag 前缀，见 .github/workflows/publish_tv_app_release.yaml */
    private const val TAG_PREFIX = "tv_"

    private class Release(val version: String, val desc: String, val apks: Map<String, String>)

    fun <T> checkUpdate(activity: T) where T : Activity, T : LifecycleOwner {
        activity.lifecycleScope.launch {
            val loading = Dialogs.loading(activity, "正在检查更新")
            val release = try {
                latestRelease()
            } catch (e: Exception) {
                loading.dismiss()
                toast("检查更新失败：${errorMessage(e)}")
                return@launch
            }
            loading.dismiss()
            val current = BuildConfig.VERSION_NAME.substringBefore("-")
            if (release == null || compareVersion(release.version, current) <= 0) {
                toast("当前已是最新版本 v$current")
                return@launch
            }
            Dialogs.confirm(
                activity,
                "发现新版本 v${release.version}（当前 v$current）",
                release.desc.ifEmpty { "发现新版本" },
                ok = "立即更新",
                cancel = "以后再说",
            ) { if (it) download(activity, release) }
        }
    }

    private suspend fun latestRelease(): Release? {
        val list = Http.getJson(
            "https://api.github.com/repos/$REPO/releases",
            mapOf("per_page" to 30),
            mapOf("Accept" to "application/vnd.github+json"),
        )
        val item = list.arr.firstOrNull { it["draft"].str != "true" && it["tag_name"].str.startsWith(TAG_PREFIX) }
            ?: return null
        val apkReg = Regex("^app-(.+)-release\\.apk$")
        val apks = item["assets"].arr.mapNotNull { asset ->
            apkReg.find(asset["name"].str)?.let { it.groupValues[1] to asset["browser_download_url"].str }
        }.toMap()
        val version = Regex("\\d+(\\.\\d+)*").find(item["tag_name"].str)?.value ?: item["tag_name"].str
        return Release(version, item["body"].str, apks)
    }

    private fun compareVersion(a: String, b: String): Int {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    private fun <T> download(activity: T, release: Release) where T : Activity, T : LifecycleOwner {
        val url = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { release.apks[it] }
        if (url == null) {
            toast("没有找到适合本设备的安装包")
            return
        }
        val dir = File(activity.cacheDir, "ota").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, "simple_live_tv_${release.version}.apk")
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
        }
        var job: Job? = null
        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载 v${release.version}")
            .setView(progress)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> job?.cancel() }
            .show()
        job = activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val body = resp.body!!
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            file.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var read = 0L
                                while (isActive) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    read += n
                                    if (total > 0) {
                                        val p = (read * 100 / total).toInt()
                                        withContext(Dispatchers.Main) { progress.progress = p }
                                    }
                                }
                            }
                        }
                    }
                }
                dialog.dismiss()
                install(activity, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                dialog.dismiss()
                toast("已取消更新")
            } catch (e: Exception) {
                dialog.dismiss()
                toast("下载失败：${errorMessage(e)}")
            }
        }
    }

    private fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            toast("请允许本应用安装未知来源应用后，再次检查更新")
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.ota_provider", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
