import 'dart:async';

import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:simple_live_core/simple_live_core.dart';
import 'package:simple_live_tv_app/app/constant.dart';
import 'package:simple_live_tv_app/app/log.dart';
import 'package:simple_live_tv_app/app/sites.dart';
import 'package:simple_live_tv_app/routes/app_navigation.dart';

import 'package:simple_live_tv_app/app/app_focus_node.dart';
import 'package:simple_live_tv_app/app/controller/base_controller.dart';

import 'package:simple_live_tv_app/routes/route_path.dart';

class HomeController extends BaseController {
  var datetime = "00:00".obs;

  /// 关注管理列表中每个用户的焦点节点
  final followFocusNodes = <String, AppFocusNode>{};

  @override
  void onInit() {
    initTimer();
    super.onInit();
  }


  void initTimer() {
    Timer.periodic(const Duration(seconds: 1), (timer) {
      var now = DateTime.now();
      datetime.value =
          "${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}";
    });
  }

  void toSync() {
    Get.toNamed(RoutePath.kSync);
  }

  void toFollow() {
    Get.toNamed(RoutePath.kFollow);
  }

  void toSettings() {
    Get.toNamed(RoutePath.kSettings);
  }

  void toHistory() {
    Get.toNamed(RoutePath.kHistory);
  }

  void toHotLive() {
    Get.toNamed(RoutePath.kHotLive);
  }

  /// 输入的是抖音直播间链接/分享文案时直接进入直播间，返回是否已处理
  Future<bool> tryOpenDouyinUrl(String text) async {
    if (!text.contains("douyin.com")) {
      return false;
    }
    var site = Sites.allSites[Constant.kDouyin]!;
    String? roomId;
    try {
      SmartDialog.showLoading(msg: "");
      roomId = await (site.liveSite as DouyinSite).parseRoomIdFromUrl(text);
    } catch (e) {
      Log.logPrint(e);
    } finally {
      SmartDialog.dismiss(status: SmartStatus.loading);
    }
    if (roomId == null) {
      SmartDialog.showToast("无法识别的抖音直播间链接");
      return true;
    }
    AppNavigator.toLiveRoomDetail(site: site, roomId: roomId);
    return true;
  }

  void toSearchRoom(String keyword) {
    Get.toNamed(RoutePath.kSearchRoom, arguments: keyword);
  }

  void toSearchAnchor(String keyword) {
    Get.toNamed(RoutePath.kSearchAnchor, arguments: keyword);
  }

  void toCategory() {
    Get.toNamed(RoutePath.kCategory);
  }
}
