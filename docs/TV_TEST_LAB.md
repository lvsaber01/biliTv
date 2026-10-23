# biliTv 本地 TV 测试环境

## 设备与边界

目标真机由用户确认：**2019 款圆筒普通版 NVIDIA Shield TV（非 Pro），Android 11，Shield Experience 9.2.4（33.2.0.400）**。后续真机兼容验收以此为准。

本机为 Apple Silicon。当前 Google SDK 软件包目录提供的 Android TV ARM64 最低版本是 API 31，因此主测试设备采用 **Android TV 12 / API 31 / ARM64 / 1080p / 2 GiB RAM / 2 核**。这是通用 TV 行为测试环境，**不是 Shield 固件或 Tegra 仿真**，也不代表圆筒款 Shield 的实际用户空间 ABI。

适合验证卡片布局、方向键焦点、分页、设置排序、登录入口、续播参数、播放与弹幕基本流程。Tegra 硬件解码、HDR / Dolby Vision、音频直通、HDMI-CEC、实体遥控器键码以及 Shield 特有问题仍需真机。

参考：[NVIDIA Android 11 升级说明](https://blogs.nvidia.com/blog/shield-experience-9/)、[Android Emulator](https://developer.android.com/studio/run/emulator)、[命令行启动](https://developer.android.com/studio/run/emulator-commandline)。

## 本机组织

- SDK / Emulator / 系统镜像：`.toolcache/android/`
- AVD 与持久化设备数据：`.toolcache/tv-lab/avd/`
- 截图与诊断日志：`.toolcache/tv-lab/captures/`
- 模拟器名称：`biliTv_TV_API31_ARM64`，ADB 序号：`emulator-5580`
- 不修改已有的个人 AVD，不自动清除 app 数据，不自动连接或部署到真机。
- 默认关闭内存快照、采用冷启动：实测旧 TV 镜像在新 Emulator 上恢复快照可能挂起。`-no-snapshot` 不等于清除数据，安装包、设置和观看进度仍保留在数据盘。
- 旧 TV 镜像禁用 Vulkan，保留 GLES 图形加速；关闭模拟器统计与崩溃报告上传，避免报告确认弹窗阻挡自动启动。
- `.toolcache/` 已被 Git 忽略；镜像、账户状态、截图和日志不可上传。日志仅采集 app 进程，但分享前仍需检查视频内容、账号标识等隐私。

脚本默认使用项目 SDK，可用 `BILITV_SDK_ROOT` 指定其他已准备好的 SDK。需要 JDK 17；本机可自动识别 Homebrew 的 JDK 17。首次准备需要下载镜像并按提示处理 SDK 许可；无需安装完整 Android Studio。

重复 `prepare` 会复用已安装的 Emulator / 镜像，不自动升级已验收的工具版本。

## 日常操作

在项目根目录运行：

```bash
# 首次准备（重复运行不会覆盖已有虚拟设备）
bash tools/tv-lab.sh prepare

# 日常一键操作：自动构建、启动模拟器、覆盖安装并打开
bash tools/tv-lab.sh launch

# 在单独终端启动有窗口的 TV 模拟器，保持该终端打开
bash tools/tv-lab.sh start

# 另一个终端：等待启动完成、构建、覆盖安装并打开
bash tools/tv-lab.sh wait
bash tools/tv-lab.sh build
bash tools/tv-lab.sh install
bash tools/tv-lab.sh run

# 模拟遥控器按键；连续输入不包含额外等待，界面转换时应分开执行
bash tools/tv-lab.sh key down
bash tools/tv-lab.sh key right ok
bash tools/tv-lab.sh key back

# 保存截图与进程日志 / 持续查看日志
bash tools/tv-lab.sh capture
bash tools/tv-lab.sh logs

# 查询环境 / 关闭模拟器（保留 app 和登录状态）
bash tools/tv-lab.sh status
bash tools/tv-lab.sh stop
```

也可以在 Finder 双击 `tools/Launch-TV-Lab.command`，执行相同的一键流程。已有模拟器会直接复用，不会重复启动。

双击入口同时将构建与启动结果保存到 `.toolcache/tv-lab/launch.log`；模拟器本身的启动诊断在 `.toolcache/tv-lab/emulator.log`。

无窗口回归可用 `start --headless`，其他命令相同。测试焦点时始终以方向键、确定、返回操作，不用鼠标点击目标控件来代替遥控器导航。

## 每次交付前的 TV 回归清单

1. 首页：频道可达；没有固定发现工具栏；1080p 默认密度下检查至少四张完整卡片及两条轨道的可见空间；上下保留各轨道位置。
2. 轨道：首卡左键刷新；末卡右键追加；旧卡片封面和焦点不跳回；每次最多展开 12 张。
3. 详情：初始焦点在播放；左右切换继续/从头播放；上下可达选集、简介和返回；长简介不阻挡选集；快速重复确定不启动多个播放器。
4. 播放：可播放、暂停、退出；保存进度后从普通卡片和历史分别继续；画质/编码切换保留位置；弹幕按钮状态明确，加载失败可重试。
5. 设置：每个分类都能右移进入、看到高亮、修改配置，再左移返回当前分类；排序/隐藏后焦点保留同一轨道，边界操作安全回落。
6. 历史/收藏：未登录入口正常；登录后检查日期分组、四张以上卡片密度、下移分页保持对应列，刷新进度不切换选中视频。
7. 留证：关键页面截图、app 日志、Android 版本与显示密度。没有实际执行的登录/续播/播放项必须标为待验，不能以单元测试替代。

## Shield 真机补验

后续如启用 Shield 网络调试，可在用户提供 IP 并于电视确认配对授权后，通过 ADB 安装和采集日志。不要自动扫描局域网或把调试端口暴露到公网。真机与模拟器使用明确序号分别操作，不使用不指定目标的 `adb install`。

## 首次环境验收（2026-10-22）

- 官方 Emulator 37.2.12，Android TV API 31 ARM64 镜像 rev 4；Hypervisor.Framework 检查通过，实际分辨率 1920×1080、密度 320 dpi（960×540 dp）。
- biliTv 0.5.1 覆盖安装和启动成功；首页截图显示 5 张完整视频卡片和两条轨道。
- 通过 ADB 方向键实际验证详情的播放 → 从头播放 → 返回路径，焦点状态由 UI 层级确认，不使用鼠标代替遥控器。
- 实际打开公开视频，播放器出现有效视频画面并有滚动弹幕；日志显示 H.264 / AAC 解码器创建。此结果不代表 Shield 的 Tegra 硬件兼容验收。
- 播放退出后详情显示“继续播放 · 看到 00:38 · 第 1 P”；覆盖安装后该进度保留。
- 关闭快照 / Vulkan 后再次实测播放，生成 00:30 的观看进度；正常关闭再冷启动、覆盖安装后，详情仍显示该进度。最近一次冷启动日志记录约 8 秒完成启动。
- 一键流程已在最小系统 PATH 下验证；不依赖 Android Studio 或开发终端中的 ripgrep。
- 本轮没有登录 Bilibili，云端历史/收藏、账号切换、多 P、设置完整回归及 Shield 特有播放能力仍为待验项目。
