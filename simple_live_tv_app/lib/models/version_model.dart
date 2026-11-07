/// GitHub Release 中的 TV 版本信息
class VersionModel {
  VersionModel({
    required this.version,
    required this.versionDesc,
    required this.releaseUrl,
    required this.apkUrls,
  });

  /// 版本号，如 1.6.6
  String version;

  /// 更新说明
  String versionDesc;

  /// Release 页面地址
  String releaseUrl;

  /// ABI -> APK 下载地址，如 arm64-v8a -> https://.../app-arm64-v8a-release.apk
  Map<String, String> apkUrls;

  factory VersionModel.fromGitHubRelease(Map<String, dynamic> json) {
    var apkUrls = <String, String>{};
    var apkReg = RegExp(r'^app-(.+)-release\.apk$');
    for (var asset in (json['assets'] as List? ?? [])) {
      var match = apkReg.firstMatch(asset['name']?.toString() ?? "");
      if (match != null) {
        apkUrls[match.group(1)!] = asset['browser_download_url'].toString();
      }
    }
    return VersionModel(
      version: parseTagVersion(json['tag_name'].toString()),
      versionDesc: json['body']?.toString() ?? "",
      releaseUrl: json['html_url']?.toString() ?? "",
      apkUrls: apkUrls,
    );
  }

  /// 从 tag 中取出版本号，如 tv_1.6.6 / tv_v1.6.6 -> 1.6.6
  static String parseTagVersion(String tag) {
    return RegExp(r'\d+(\.\d+)*').firstMatch(tag)?.group(0) ?? tag;
  }

  /// 比较版本号，a > b 返回正数
  static int compareVersion(String a, String b) {
    var aParts = a.split('.').map((e) => int.tryParse(e) ?? 0).toList();
    var bParts = b.split('.').map((e) => int.tryParse(e) ?? 0).toList();
    for (var i = 0; i < aParts.length || i < bParts.length; i++) {
      var x = i < aParts.length ? aParts[i] : 0;
      var y = i < bParts.length ? bParts[i] : 0;
      if (x != y) {
        return x - y;
      }
    }
    return 0;
  }
}
