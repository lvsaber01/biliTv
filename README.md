# biliTv

当前版本：`0.9.0`

这是一个基于 `source/BBLL1.5.2.apk` 的独立重建项目。目标不是恢复原始工程，而是使用 REA 对 APK 做静态证据分析后，重新实现一个可以直接构建和使用的 Android / Android TV Bilibili 客户端。

## 当前能力

- 悬浮深色导航：频道条横向滚动并渐隐，首页、更多、搜索、我的、历史保持固定入口
- 独立历史页：按观看日期分组的封面网格、游标分页、直接回看、长按查看详情；未登录和会话失效时提供登录入口
- 全局续播：普通卡片显示进度条和分 P，详情提供继续/从头播放；历史直接恢复有效分 P 和进度
- 本机 SQLite 按账号保存观看进度，播放中每 5 秒及暂停/退出时保存；登录后按需合并云端历史，使用较新记录
- 个人中心展示头像、昵称、登录状态及收藏/历史/设置卡片；收藏夹和收藏内容卡片化
- 设置改为横屏左右分栏：外观、频道、浏览、播放、弹幕、账号与存储、关于；保留各面板位置和旧配置

- 首页采用紧凑推荐条与横向内容轨道，不再固定显示“发现/轨道管理”工具栏；样式与轨道管理统一放在设置
- 轨道按可用宽度自适应 4–6 张卡片，历史与收藏也独立采用紧凑网格；热门精选和各分区轨道支持首卡按左刷新、末卡按右加载更多
- 分区轨道覆盖动画、音乐、舞蹈、游戏、知识、生活、美食和科技数码
- 设置页支持轨道显示/隐藏、轨道排序、头部推荐开关和接近末尾自动预加载
- 动画、游戏、知识、科技数码、音乐、美食频道：各有推荐轨道和 3 条已验证的主题推荐轨道
- 更多频道面板支持方向键、确定键、返回和触摸操作，另提供舞蹈、生活兴趣频道
- 顶部频道支持固定/取消固定与排序；每个频道独立设置轨道显示和顺序
- 首批及每批追加 12 张，后台预加载只准备下一批；按 BV 去重并有限补取，末批可能不足 12 张
- 横向轨道使用 RecyclerView 增量插入，旧卡片无需重建；封面使用内存及 64 MiB 磁盘缓存、缩放解码和重复请求合并
- 同一次使用期间保留频道内容及轨道位置；切走频道释放卡片视图，返回后恢复列表位置
- TV 遥控器支持卡片间上下切换；每条轨道独立记忆焦点位置，首次进入默认第一张，返回时恢复上次位置
- 网格支持每行 1 / 2 / 3 个视频并记住选择；卡片显示封面、标题、UP 主、时长和统计
- 关键词搜索与 BV 号直达
- 视频详情、作者/统计信息、多 P 选择
- Media3 播放器：MP4 兼容模式或 DASH 分离音视频模式，自动携带 CDN 所需 Referer、Origin 和 User-Agent
- 设置页支持首页样式、轨道管理、默认分辨率、默认视频编码、MP4/DASH、弹幕开关
- 播放画质按服务端实际返回的 360P–8K、60 帧、HDR、Dolby Vision 和 AV1 轨道动态展示，并结合当前设备的解码器、显示能力和帧率过滤；支持关闭自动过滤后手动尝试实验性高规格
- 播放页支持画质、编码、音轨、倍速、弹幕和设置快捷入口
- 播放器支持遥控器定位预览、字幕覆盖层、章节/看点跳转、画面比例和当前线路诊断；定位预览按需加载 B 站快照雪碧图
- 登录后详情页支持添加稍后再看和多选收藏夹，个人中心新增稍后再看入口；稍后再看卡片沿用本机续播进度
- 详情页作者区可进入 UP 主空间；主页展示头像、认证、简介、直播状态、粉丝/关注/投稿统计，支持最新投稿与最多播放切换
- 登录后支持关注/取消关注 UP 主，并在“我的”进入关注列表；未登录时关注操作进入二维码登录
- 播放控制层统一淡出并释放焦点；确认直接播放/暂停，左右每次快退/快进10秒、长按累计定位，上键设置、下键回播放；返回先隐藏再退出。详见 [播放器交互规则](docs/PLAYBACK_INTERACTION.md)
- 弹幕 XML 支持 raw DEFLATE / zlib / gzip 解压与播放器内滚动显示；按钮直接显示开/关、加载、失败等状态，失败可重试
- 弹幕独立设置覆盖区域（25%/50%/75%/全屏）、移动速度、字号、不透明度和密度，支持本页恢复默认；播放器内定向进入，返回生效不重启视频。详见 [弹幕设置设计](docs/DANMAKU_SETTINGS.md)
- 修复网格列数循环切换与显示；从设置返回个人中心保留设置入口焦点
- 二维码登录、扫码状态轮询、登录 Cookie 加密保存
- 登录后的观看历史与收藏夹卡片浏览；详情可添加稍后再看、更新收藏夹
- WBI 动态签名，以及普通接口失败后的兼容回退
- Android TV Leanback 启动入口与 TV banner

## 构建与安装

本地电视模拟器的准备、一键启动、遥控器按键与截图日志操作见 [TV 测试环境](docs/TV_TEST_LAB.md)。日常可运行 `bash tools/tv-lab.sh launch`，或双击 `tools/Launch-TV-Lab.command`；SDK、设备数据和诊断产物均留在 Git 忽略的 `.toolcache/` 中。

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
| 频道推荐 | `/x/web-interface/region/feed/rcmd` | 新版分区 ID，`display_id` 递增、`request_cnt=12`；去重后最多补取 3 次，不用有限排行榜模拟分页 |
| 主题轨道 | `/x/web-interface/wbi/search/type` | 关键词 + 已验证的旧版 `tids` 分类过滤；明确标注“主题推荐”，不是官方子分区列表 |
| 云端历史 | `/x/web-interface/history/cursor` | 需要登录；保留 max / business / view_at 游标、分 P、观看进度及观看时间，按账号合并 |
| 搜索 | `/x/web-interface/wbi/search/type` | 回退 `/x/web-interface/search/type` |
| 详情 | `/x/web-interface/view` | 按 BVID |
| 播放 | `/x/player/wbi/playurl` | 回退旧 playurl；DASH 请求完整高规格能力，按服务端轨道和本机解码/显示能力过滤 |
| 播放信息 | `/x/player/wbi/v2` | 回退 `/x/player/v2` |
| 定位预览 | `/x/player/videoshot` | 按需读取时间表和雪碧图；失败不影响普通 seek |
| 字幕/章节 | `/x/player/wbi/v2` | 播放器信息中的字幕与 `view_points`，本地时间轴覆盖层 |
| 弹幕 | `comment.bilibili.com/{cid}.xml` | 播放页异步加载 |
| 稍后再看 | `/x/v2/history/toview` | 登录后读取；详情使用 `/add` 写入 |
| 收藏 | `/x/v3/fav/folder/created/list-all`、`/x/v3/fav/resource/deal` | 登录后读取收藏夹并多选更新，写操作需要 Cookie CSRF |
| UP 主资料 | `/x/space/wbi/acc/info` | WBI 资料；回退 `/x/web-interface/card`，并补充 `/x/relation/stat`、`/x/space/navnum` |
| UP 主投稿 | `/x/space/wbi/arc/search` | 回退旧空间投稿接口，再回退 `/x/series/recArchivesByKeywords`，应对 WBI 风控/`-799` |
| 关注关系 | `/x/relation`、`/x/relation/modify` | 登录后读取和写入；写操作需要 Cookie CSRF |
| 我的关注 | `/x/relation/followings` | 登录后读取关注列表，支持分页进入 UP 主空间 |
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
- `:app:testDebugUnitTest`：通过（59 项，包含设备画质能力过滤、UP 主资料/投稿解析）
- `:app:lintDebug`：通过；仅有非阻断的依赖升级、国际化和横屏适配提示
- 0.9.0 已完成 Android TV API31/ARM64 模拟器 UI 回归：详情作者入口、UP 主资料与投稿网格、排序切换、关注登录引导、我的关注入口均通过；模拟器已关闭
- 已验证公开接口：view、普通 playurl、player v2、WBI v2、nav、hotword、二维码生成及未扫码轮询
- 2026-10-22 实测六个优先频道、舞蹈和生活兴趣的首批及第二批推荐，以及 18 条主题推荐；六个优先频道另以客户端 Android 请求头验证封面、作者和 BV 字段
- 当前环境没有连接 Android 真机或模拟器，因此未进行实际 UI 点击和播放回归；安装命令已保留在上面
- 0.5.0 增加 JVM Android 面板、导航、历史解析、SQLite 恢复与卡片进度验证；这些测试不能替代登录账号和实际播放器/遥控器验收

版本变更记录见 [CHANGELOG.md](CHANGELOG.md)。后续开发使用 Git 提交和版本 tag 管理，版本号遵循语义化版本规则。

需求分析与实施确认流程见 [docs/REQUIREMENTS_WORKFLOW.md](docs/REQUIREMENTS_WORKFLOW.md)。涉及页面、接口、设置或核心交互的功能，会先完成需求评估和范围确认，再进入编码。

本版交互、API 验证和设备验收清单见 [docs/CHANNEL_BROWSING.md](docs/CHANNEL_BROWSING.md)。频道位置记忆目前限当前 Activity 会话；退出或系统重建后重新加载，频道与轨道设置持久保存。

0.5.0 的导航、历史和续播规则见 [docs/NAVIGATION_AND_PROGRESS.md](docs/NAVIGATION_AND_PROGRESS.md)。本机进度跨重启保存；云端历史按需读取补充，不上报本机播放进度。未读取到的云端历史不会提前显示到普通卡片。

## 仓库边界

公开仓库只跟踪可构建的 Android 源码、资源、Gradle 配置和版本文档。反向工程过程记录、接口响应快照、构建输出和本地测试文件均不纳入版本库；对应规则见 `.gitignore`。
