import 'package:simple_live_tv_app/models/version_model.dart';
import 'package:simple_live_tv_app/requests/http_client.dart';

/// 通用的请求
class CommonRequest {
  static const kGitHubRepo = "mmooyyii/dart_simple_live";

  /// TV 版发版 tag 前缀，见 .github/workflows/publish_tv_app_release.yaml
  static const kTvTagPrefix = "tv_";

  /// 获取最新的 TV 版 Release，没有则返回 null
  Future<VersionModel?> checkUpdate() async {
    var result = await HttpClient.instance.getJson(
      "https://api.github.com/repos/$kGitHubRepo/releases",
      queryParameters: {
        "per_page": 30,
      },
      header: {
        "Accept": "application/vnd.github+json",
      },
    );
    for (var item in result as List) {
      if (item['draft'] == true) {
        continue;
      }
      if (item['tag_name'].toString().startsWith(kTvTagPrefix)) {
        return VersionModel.fromGitHubRelease(item as Map<String, dynamic>);
      }
    }
    return null;
  }
}
