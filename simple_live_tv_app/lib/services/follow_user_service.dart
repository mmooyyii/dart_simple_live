import 'dart:async';
import 'dart:collection';
import 'package:get/get.dart';
import 'package:simple_live_tv_app/app/constant.dart';
import 'package:simple_live_tv_app/app/controller/app_settings_controller.dart';
import 'package:simple_live_tv_app/app/controller/base_controller.dart';
import 'package:simple_live_tv_app/app/event_bus.dart';
import 'package:simple_live_tv_app/app/log.dart';
import 'package:simple_live_tv_app/app/sites.dart';
import 'package:simple_live_tv_app/app/utils.dart';
import 'package:simple_live_tv_app/models/db/follow_user.dart';
import 'package:simple_live_tv_app/services/db_service.dart';

class FollowUserService extends BasePageController<FollowUser> {
  static FollowUserService get instance => Get.find<FollowUserService>();
  StreamSubscription<dynamic>? subscription;

  RxList<FollowUser> livingList = RxList<FollowUser>();
  Timer? updateTimer;
  bool needUpdate = true;
  @override
  void onInit() {
    subscription = EventBus.instance.listen(Constant.kUpdateFollow, (p0) {
      needUpdate = false;
      refreshData();
    });

    if (list.isEmpty) {
      refreshData();
    }
    initTimer();
    super.onInit();
  }

  void initTimer() {
    if (AppSettingsController.instance.autoUpdateFollowEnable.value) {
      updateTimer?.cancel();
      updateTimer = Timer.periodic(
        Duration(
          minutes:
              AppSettingsController.instance.autoUpdateFollowDuration.value,
        ),
        (timer) {
          Log.logPrint("Update Follow Timer");
          refreshData();
        },
      );
    } else {
      updateTimer?.cancel();
    }
  }

  var updating = false.obs;

  /// 已查询到的直播状态缓存（id -> liveStatus）
  /// - 关注/取关后重新读库时沿用，避免整个列表状态被清空
  final Map<String, int> _liveStatusCache = {};

  /// 状态更新批次号，新一轮开始后旧一轮的 worker 自动退出
  int _updateBatch = 0;

  @override
  Future<List<FollowUser>> getData(int page, int pageSize) async {
    if (page > 1) {
      return [];
    }

    var followList = DBService.instance.getFollowList();
    for (var item in followList) {
      item.liveStatus.value = _liveStatusCache[item.id] ?? 0;
    }
    // needUpdate=false 时（关注/取关触发）只查询还没有状态的用户
    var toUpdate = needUpdate
        ? followList
        : followList.where((x) => !_liveStatusCache.containsKey(x.id)).toList();
    needUpdate = true;

    followList.sort((a, b) => b.liveStatus.value.compareTo(a.liveStatus.value));
    livingList.assignAll(followList.where((x) => x.liveStatus.value == 2));

    if (toUpdate.isEmpty) {
      updating.value = false;
    } else {
      startUpdateStatus(toUpdate);
    }
    return followList;
  }

  void sortList() {
    list.sort((a, b) => b.liveStatus.value.compareTo(a.liveStatus.value));
    updateLivingList();
  }

  void updateLivingList() {
    livingList.assignAll(list.where((x) => x.liveStatus.value == 2));
  }

  void startUpdateStatus(List<FollowUser> followList) async {
    var batch = ++_updateBatch;
    updating.value = true;

    var threadCount =
        AppSettingsController.instance.updateFollowThreadCount.value;
    if (threadCount < 1) {
      threadCount = 1;
    }

    // 所有 worker 共用一个队列，避免静态分段时某段里有慢请求拖住整段
    var queue = Queue<FollowUser>.from(followList);
    Future<void> worker() async {
      while (queue.isNotEmpty && batch == _updateBatch) {
        await updateLiveStatus(queue.removeFirst());
      }
    }

    await Future.wait(List.generate(threadCount, (_) => worker()));

    // 被新一轮更新取代时，由新一轮负责收尾
    if (batch == _updateBatch) {
      sortList();
      updating.value = false;
    }
  }

  Future updateLiveStatus(FollowUser item) async {
    try {
      var site = Sites.allSites[item.siteId]!;
      item.liveStatus.value =
          (await site.liveSite.getLiveStatus(roomId: item.roomId)) ? 2 : 1;
      _liveStatusCache[item.id] = item.liveStatus.value;
    } catch (e) {
      Log.logPrint(e);
    }
  }

  void removeItem(FollowUser item, {bool refresh = true}) async {
    var result =
        await Utils.showAlertDialog("确定要取消关注${item.userName}吗?", title: "取消关注");
    if (!result) {
      return;
    }
    await DBService.instance.followBox.delete(item.id);
    if (refresh) {
      refreshData();
    } else {
      list.remove(item);
      livingList.remove(item);
    }
  }

  @override
  void onClose() {
    updateTimer?.cancel();
    subscription?.cancel();

    super.onClose();
  }
}
