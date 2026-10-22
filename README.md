# biliTv

当前版本：`0.2.0`

这是一个基于 `source/BBLL1.5.2.apk` 的独立重建项目。目标不是恢复原始工程，而是使用 REA 对 APK 做静态证据分析后，重新实现一个可以直接构建和使用的 Android / Android TV Bilibili 客户端。

## 当前能力

- 首页热门视频：默认使用 Netflix TV 风格的 hero + 横向内容轨道，也可切换到封面网格
- 首页支持一键刷新推荐；热门精选和各分区轨道支持横向滑到末尾自动加载更多
- 分区轨道覆盖动画、音乐、舞蹈、游戏、知识、生活、美食和科技数码
- 网格支持每行 1 / 2 / 3 个视频并记住选择；卡片显示封面、标题、UP 主、时长和统计
- 关键词搜索与 BV 号直达
- 视频详情、作者/统计信息、多 P 选择
- Media3 播放器：MP4 兼容模式或 DASH 分离音视频模式，自动携带 CDN 所需 Referer、Origin 和 User-Agent
- 设置页支持首页样式、默认分辨率、默认视频编码、MP4/DASH、弹幕开关
- 播放页支持画质、编码、音轨、倍速、弹幕和设置快捷入口
- 弹幕 XML 拉取、解析与播放器内滚动显示，可切换开关
- 二维码登录、扫码状态轮询、登录 Cookie 加密保存
- 登录后的观看历史与收藏夹只读浏览
- WBI 动态签名，以及普通接口失败后的兼容回退
- Android TV Leanback 启动入口与 TV banner

## 构建与安装

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

项目使用 Gradle 8.10.2、Android Gradle Plugin 8.8.1、Kotlin 1.9.24、compileSdk 35，最低 Android API 23。首次使用 Gradle Wrapper 需要下载 Gradle；如果网络较慢，也可以使用本机安装的同版本 Gradle 构建。

输出 APK：`app/build/outputs/apk/debug/app-debug.apk`。

## API 兼容策略

Bilibili 的部分 Web 接口要求 WBI 签名，而且接口可能返回 412、限流或调整字段。本项目将请求集中在 `BiliApiClient`，不让 UI 直接拼接 URL：

| 功能 | 主路径 | 回退/备注 |
| --- | --- | --- |
| 热门 | `/x/web-interface/popular` | 公开接口 |
| 分区热门 | `/x/web-interface/ranking/v2` | 按 `rid` 获取动画、音乐、游戏、美食等分区排行榜；回退 `/x/web-interface/ranking` |
| 搜索 | `/x/web-interface/wbi/search/type` | 回退 `/x/web-interface/search/type` |
| 详情 | `/x/web-interface/view` | 按 BVID |
| 播放 | `/x/player/playurl` | 回退 WBI playurl；MP4 durl 或 DASH video/audio，按设置自动选择 |
| 播放信息 | `/x/player/wbi/v2` | 回退 `/x/player/v2` |
| 弹幕 | `comment.bilibili.com/{cid}.xml` | 播放页异步加载 |
| 登录 | `/x/passport-login/web/qrcode/*` | Cookie 仅存 Android Keystore 加密偏好设置 |

WBI 的 `imgKey` / `subKey` 每次从 `/x/web-interface/nav` 动态提取，签名实现位于 `app/src/main/java/com/lvsaber/blblrebuild/data/BiliWbiSigner.kt`，没有把短期密钥写死在应用中。

## 安全与边界

- 不记录、打印或提交用户 Cookie；会话使用 Android Keystore AES/GCM 加密。
- `allowBackup=false`，并通过 Android 12+ backup rules 排除会话偏好设置。
- 禁止明文 HTTP；所有接口使用 HTTPS，弹幕域名也由 HTTPS 访问。
- 这是面向个人使用的客户端重建，不包含 Bilibili 官方资源、账号系统或原 APK 的私有实现。
- Bilibili 接口属于服务端可变接口，登录、搜索、播放地址和匿名访问可能受风控、地区、账号权限和限流影响。

## 验证结果

- `:app:assembleDebug`：通过
- `:app:testDebugUnitTest`：通过
- `:app:lintDebug`：通过；仅有非阻断的依赖升级、国际化和横屏适配提示
- 已验证公开接口：view、普通 playurl、player v2、WBI v2、nav、hotword、二维码生成及未扫码轮询
- 当前环境没有连接 Android 真机或模拟器，因此未进行实际 UI 点击和播放回归；安装命令已保留在上面

版本变更记录见 [CHANGELOG.md](CHANGELOG.md)。后续开发使用 Git 提交和版本 tag 管理，版本号遵循语义化版本规则。

需求分析与实施确认流程见 [docs/REQUIREMENTS_WORKFLOW.md](docs/REQUIREMENTS_WORKFLOW.md)。涉及页面、接口、设置或核心交互的功能，会先完成需求评估和范围确认，再进入编码。

## 仓库边界

公开仓库只跟踪可构建的 Android 源码、资源、Gradle 配置和版本文档。反向工程过程记录、接口响应快照、构建输出和本地测试文件均不纳入版本库；对应规则见 `.gitignore`。
