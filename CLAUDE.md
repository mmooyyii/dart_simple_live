# CLAUDE.md

## 项目范围

本仓库只维护 **Android TV 端**。手机/桌面端（`simple_live_app`）和控制台（`simple_live_console`）已删除，不要再加回来。

- `simple_live_tv_app/`：Flutter Android TV 客户端（GetX 状态管理/路由，Hive 存储，media_kit 播放，canvas_danmaku 弹幕）
- `simple_live_core/`：核心库，各平台（虎牙、斗鱼、B站、抖音）直播信息与弹幕的实现，TV 端通过 `path: ../simple_live_core` 依赖
- `assets/`：`tv_app_version.json`（TV 检查更新，发版 workflow 会改它）、`play_config.json`（core 里虎牙远程拉取，不要删）
- `.github/workflows/publish_tv_app_*.yaml`：TV 发版 CI

## 环境

- Flutter `3.38`
- 本机未安装 Flutter，无法本地 analyze/编译；改完代码需要说明未经编译验证

## 开发约定

- TV 端交互基于遥控器焦点（`AppFocusNode` / `BaseFocusModel`），新增可交互组件要处理焦点
- 弹幕处理在热路径上（大房间每秒几百条），避免每条消息做重复计算（如构造正则），屏蔽词用 `AppSettingsController.isShielded`

## Git

- **每完成一项改动，自动 `git commit`**，不用询问
- 只提交本次任务相关的文件，commit message 用中文简述改了什么
- 不自动 push
