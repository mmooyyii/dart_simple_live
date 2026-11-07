package com.xycz.simple_live_tv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "simple_live_tv/ota")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "installApk" -> {
                        val path = call.argument<String>("path")
                        if (path == null) {
                            result.error("ARGUMENT", "path is null", null)
                            return@setMethodCallHandler
                        }
                        try {
                            result.success(installApk(File(path)))
                        } catch (e: Exception) {
                            result.error("INSTALL", e.message, null)
                        }
                    }

                    else -> result.notImplemented()
                }
            }
    }

    /// 调起系统安装器，返回 ok / need_permission
    private fun installApk(file: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            // 引导用户允许本应用安装未知来源应用
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "need_permission"
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.ota_provider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return "ok"
    }
}
