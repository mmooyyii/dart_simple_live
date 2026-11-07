# CLAUDE.md

## 项目范围

本仓库只维护 **Android TV 端**。手机/桌面端和控制台已删除，不要再加回来。Flutter TV 版已重写为原生 Android（2.0.0 起）。

- `simple_live_android/`：原生 Android TV 工程（Kotlin，传统 View）
  - `:core`：纯 Kotlin/JVM 核心库。`site/` 四个平台（B站、斗鱼、虎牙、抖音）、`danmaku/` 弹幕、`tars/` 虎牙 Tars 编解码、`common/` HTTP/JSON/JS 引擎接口；JS 资源（crypto-js、抖音 a_bogus、webmssdk）在 `core/src/main/resources/js/`
  - `:app`：界面（`ui/`）、存储（`data/Store.kt` 带 updatedAt/墓碑的记录，`data/Settings.kt` SharedPreferences）、服务（`service/` 关注状态、局域网同步、OTA）
- `simple_live_core/`：旧版 Dart 核心库，只作移植参考，不参与构建
- `assets/`：`tv_app_version.json`（发版说明，CI 读取）、`play_config.json`
- `.github/workflows/publish_tv_app_release.yaml`：TV 发版 CI（Gradle 构建）

## 环境与验证

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`，SDK 在 `~/Library/Android/sdk`
- 编译：`cd simple_live_android && ./gradlew :app:assembleDebug`
- 平台冒烟测试（真实请求接口，默认不跑）：`./gradlew :core:test -PliveTest --tests '*SiteSmokeTest*'`
- 小米电视：`adb -s 192.168.31.33:5555`（armeabi-v7a）；debug 包 applicationId 带 `.dev`，与正式版共存

## 开发约定

- 遥控器焦点：可交互 View 要 `isFocusable`，用 `tvFocus()` 做焦点放大
- 播放用 Media3 ExoPlayer：FLV 只支持 H.264，H.265 必须走 HLS/fmp4（B站跳过 HEVC FLV、斗鱼固定 `hevc=0`、抖音 265 优先 HLS）
- 弹幕在热路径上：`LiveRoomActivity` 按每秒上限限流，屏蔽词用 `ShieldFilter`
- 斗鱼签名、抖音 a_bogus / 弹幕签名通过 `Js.engine`（Android 上是 QuickJS，测试里是 Node）执行
- 抖音搜索需要登录，改为按直播间链接/房间号打开（`DouyinSite.parseRoomIdFromUrl`）；搜索框二维码指向局域网服务（端口 23234）的 `/open` 页面
- 局域网同步：UDP 23235 广播 digest，不同则 `POST /gossip/exchange` 全量交换，按 updatedAt 新者胜合并
- 旧 Flutter 版数据（`app_flutter/*.hive`）首次启动由 `HiveMigration` 自动导入，只读不删

## 发版与 OTA

- 应用内更新只在 设置 → 关于 → 版本 手动触发，不自动弹窗
- OTA 读取 GitHub `mmooyyii/dart_simple_live` 最新 `tv_` tag，按 ABI 下载 `app-<abi>-release.apk`，FileProvider 调起安装
- 发版：改 `simple_live_android/app/build.gradle.kts` 的 `appVersionName`（和 `appVersionBase`，versionCode = abi*1000 + base，必须递增）与 `assets/tv_app_version.json`，再打 tag `tv_<版本号>`
- 签名必须与旧版一致（CI secrets `TV_KEYSTORE_BASE64` 等）；CI 带 `-PrequireReleaseSigning`，缺签名直接失败

## Git

- **每完成一项改动，自动 `git commit`**，不用询问
- 只提交本次任务相关的文件，commit message 用中文简述改了什么
- 不自动 push
