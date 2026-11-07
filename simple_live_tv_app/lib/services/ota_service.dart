import 'dart:io';

import 'package:device_info_plus/device_info_plus.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_screenutil/flutter_screenutil.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:path_provider/path_provider.dart';
import 'package:simple_live_tv_app/app/app_style.dart';
import 'package:simple_live_tv_app/app/log.dart';
import 'package:simple_live_tv_app/app/utils.dart';
import 'package:simple_live_tv_app/models/version_model.dart';
import 'package:simple_live_tv_app/requests/common_request.dart';

/// 应用内更新：从 GitHub Release 下载对应 ABI 的 APK 并调起系统安装
class OtaService {
  static const _channel = MethodChannel("simple_live_tv/ota");

  static var _checking = false;

  /// 检查更新，只由设置页手动触发
  static Future<void> checkUpdate() async {
    if (_checking || !Platform.isAndroid) {
      return;
    }
    _checking = true;
    try {
      SmartDialog.showLoading(msg: "正在检查更新");
      VersionModel? latest;
      try {
        latest = await CommonRequest().checkUpdate();
      } finally {
        SmartDialog.dismiss(status: SmartStatus.loading);
      }
      var current = Utils.packageInfo.version;
      if (latest == null ||
          VersionModel.compareVersion(latest.version, current) <= 0) {
        SmartDialog.showToast("当前已是最新版本 v$current");
        return;
      }
      var confirm = await Utils.showAlertDialog(
        latest.versionDesc.isEmpty ? "发现新版本" : latest.versionDesc,
        title: "发现新版本 v${latest.version}（当前 v$current）",
        confirm: "立即更新",
        cancel: "以后再说",
      );
      if (confirm) {
        await _downloadAndInstall(latest);
      }
    } catch (e) {
      Log.logPrint(e);
      SmartDialog.showToast("检查更新失败：$e");
    } finally {
      _checking = false;
    }
  }

  /// 按设备支持的 ABI 顺序选择 APK
  static Future<String?> _selectApkUrl(VersionModel version) async {
    var info = await DeviceInfoPlugin().androidInfo;
    for (var abi in info.supportedAbis) {
      var url = version.apkUrls[abi];
      if (url != null) {
        return url;
      }
    }
    return null;
  }

  static Future<void> _downloadAndInstall(VersionModel version) async {
    var url = await _selectApkUrl(version);
    if (url == null) {
      SmartDialog.showToast("没有找到适合本设备的安装包");
      return;
    }

    // 下载到 cache/ota 目录，与 android/app/src/main/res/xml/ota_file_paths.xml 对应
    var dir = Directory("${(await getTemporaryDirectory()).path}/ota");
    if (await dir.exists()) {
      await dir.delete(recursive: true);
    }
    await dir.create(recursive: true);
    var savePath = "${dir.path}/simple_live_tv_${version.version}.apk";

    var progress = 0.0.obs;
    var cancelToken = CancelToken();
    Get.dialog(
      PopScope(
        canPop: false,
        child: AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: AppStyle.radius16),
          surfaceTintColor: Colors.transparent,
          backgroundColor: Get.theme.cardColor,
          title: Text(
            "正在下载 v${version.version}",
            style: AppStyle.titleStyleWhite,
          ),
          content: SizedBox(
            width: 600.w,
            child: Obx(
              () => Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  LinearProgressIndicator(value: progress.value),
                  AppStyle.vGap24,
                  Text(
                    "${(progress.value * 100).toStringAsFixed(1)}%",
                    style: AppStyle.textStyleWhite,
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              autofocus: true,
              onPressed: () => cancelToken.cancel(),
              child: Text("取消", style: AppStyle.textStyleWhite),
            ),
          ],
        ),
      ),
      barrierDismissible: false,
    );

    try {
      await Dio().download(
        url,
        savePath,
        cancelToken: cancelToken,
        onReceiveProgress: (received, total) {
          if (total > 0) {
            progress.value = received / total;
          }
        },
      );
    } catch (e) {
      Get.back();
      if (e is DioException && CancelToken.isCancel(e)) {
        SmartDialog.showToast("已取消更新");
      } else {
        Log.logPrint(e);
        SmartDialog.showToast("下载失败：$e");
      }
      return;
    }
    Get.back();

    try {
      var result = await _channel.invokeMethod<String>(
        "installApk",
        {"path": savePath},
      );
      if (result == "need_permission") {
        SmartDialog.showToast("请允许本应用安装未知来源应用后，再次检查更新");
      }
    } catch (e) {
      Log.logPrint(e);
      SmartDialog.showToast("调起安装失败：$e");
    }
  }
}
