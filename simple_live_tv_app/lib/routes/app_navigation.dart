import 'package:flutter/material.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:simple_live_core/simple_live_core.dart';
import 'package:simple_live_tv_app/app/constant.dart';
import 'package:simple_live_tv_app/app/controller/app_settings_controller.dart';
import 'package:simple_live_tv_app/app/log.dart';
import 'package:simple_live_tv_app/app/sites.dart';
import 'package:simple_live_tv_app/modules/category/category_controller.dart';
import 'package:simple_live_tv_app/modules/live_room/live_room_controller.dart';
import 'package:simple_live_tv_app/routes/route_path.dart';
import 'package:simple_live_tv_app/services/bilibili_account_service.dart';

/// APP页面跳转封装
/// * 需要参数的页面都应使用此类
/// * 如不需要参数，可以使用Get.toNamed
class AppNavigator {
  /// 跳转至直播间
  static void toLiveRoomDetail(
      {required Site site, required String roomId}) async {
    if (site.id == Constant.kBiliBili &&
        !BiliBiliAccountService.instance.logined.value &&
        AppSettingsController.instance.bilibiliLoginTip.value) {
      await toBiliBiliLogin();
      if (!BiliBiliAccountService.instance.logined.value) {
        SmartDialog.showToast("未完成登录");
        return;
      }
    }

    Get.toNamed(RoutePath.kLiveRoomDetail, arguments: site, parameters: {
      "roomId": roomId,
    });
  }

  /// 打开抖音直播间链接/分享文案，成功返回 null，失败返回错误信息
  /// - 首页搜索框和手机扫码发送链接（SyncService /open）共用
  static Future<String?> openDouyinUrl(String text) async {
    var site = Sites.allSites[Constant.kDouyin]!;
    String? roomId;
    try {
      roomId = await (site.liveSite as DouyinSite).parseRoomIdFromUrl(text);
    } catch (e) {
      Log.logPrint(e);
    }
    if (roomId == null) {
      return "无法识别的抖音直播间链接";
    }
    // 先关掉搜索框等弹窗
    Navigator.of(Get.context!).popUntil((route) => route is! PopupRoute);
    // 已经在直播间里就直接切台，避免叠加多个播放器
    if (Get.currentRoute.startsWith(RoutePath.kLiveRoomDetail) &&
        Get.isRegistered<LiveRoomController>()) {
      Get.find<LiveRoomController>().resetRoom(site, roomId);
    } else {
      toLiveRoomDetail(site: site, roomId: roomId);
    }
    return null;
  }

  /// 跳转至哔哩哔哩登录
  static Future toBiliBiliLogin() async {
    await Get.toNamed(RoutePath.kBiliBiliQRLogin);
  }

  /// 跳转至分类详情
  static void toCategoryDetail(
      {required Site site, required LiveSubCategoryExt category}) {
    Get.toNamed(RoutePath.kCategoryDetail, arguments: [site, category]);
  }
}
