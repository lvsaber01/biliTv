# Changelog

## [0.2.0] - 2026-10-22

### Added

- 首页新增一键刷新推荐内容。
- Netflix 风格轨道支持横向滑到末尾自动追加内容，轨道内同时提供手动刷新按钮。
- 新增动画、音乐、舞蹈、游戏、知识、生活、美食和科技数码分区热门轨道。
- 接入 Bilibili 当前分区排行榜接口 `/x/web-interface/ranking/v2`，并保留旧排行榜接口回退。

### Verification

- 美食分区排行榜接口实测返回 `code=0`，当前返回 97 条视频。
- `:app:assembleDebug` 通过。
- `:app:testDebugUnitTest` 通过。
- `:app:lintDebug` 通过。

## [0.1.0] - 2026-10-22

这是 biliTv 当前重建版本的第一个 Git 基线版本。

### Added

- 热门视频首页、搜索、BV 号直达、视频详情和分 P 播放。
- 首页 TV 横向轨道与网格布局，可配置每行 1/2/3 个视频。
- 首页和详情页视频封面加载、深色高对比度 UI、Android TV/遥控器焦点状态。
- MP4 兼容播放和 DASH 独立音视频播放。
- 播放页画质、视频编码、音轨、倍速、弹幕和设置快捷操作。
- 设置页：首页样式、默认分辨率、封装格式、视频编码和弹幕开关。
- Bilibili WBI 请求兼容、二维码登录、加密 Cookie、观看历史和收藏夹只读浏览。
- Media3 播放器 CDN 请求头兼容，修复自定义 Android UA 导致的播放 403。

### Verification

- `:app:assembleDebug` 通过。
- `:app:testDebugUnitTest` 通过。
- `:app:lintDebug` 通过。
- 已验证公开播放接口可返回 DASH 视频和音频轨道。

## Versioning

后续版本遵循语义化版本号：

- `MAJOR`：不兼容的产品或数据行为变化。
- `MINOR`：向后兼容的新功能。
- `PATCH`：向后兼容的问题修复和小幅优化。

每个版本同时更新 `app/build.gradle.kts` 中的 `versionCode` / `versionName` 和本文件，并创建对应 Git tag，例如 `v0.1.1`。
