# biliTv 品牌图片

这组资源采用用户选定的猫咪探头电视稿：蓝色圆角电视框、粉色播放键和卷尾巴、奶白猫脸与小爪。主图保留透明背景；APK 图标加暖白底，Android TV 横幅使用深蓝底并搭配 `biliTv` 字标。

## 配色

| 用途 | 色值 |
| --- | --- |
| 蓝色 | `#00A1D6` |
| 粉色 | `#FB7299` |
| 暖白 | `#FFF8F4` |
| 深蓝背景 | `#102B38` |
| 墨色细节 | `#173C50` |

蓝粉参考常见的哔哩哔哩品牌色。猫咪脸、白爪、单条卷尾和画面内粉色播放键作为本稿的识别点；没有使用哔哩哔哩官方小电视表情或官方字标。网络检索发现“猫咪 + 电视”是常见题材，因此这些差异点用于降低混淆；这不是商标或法律检索结论。

## 文件

- `source/biliTv-mark-master.png` — 选定的透明主图，1254 × 1254。
- `exports/biliTv-mark-{1024,512,256,128}.png` — 透明主图尺寸。
- `exports/ic_launcher-{512,192,144,96,72,48,36}.png` — 暖白底方形应用图标。
- `exports/biliTv-logo-{1200x400,600x200,300x100}.png` — 横向主图与 `biliTv` 字标。
- `exports/biliTv-tv-banner-{1280x720,640x360,320x180}.png` — 16:9 电视横幅；320 × 180 已接入 Android TV 应用资源。
- `generate_brand_assets.py` — 从主图重新生成 PNG、APK 资源与压缩包。
- `biliTv-brand-assets.zip` — 脚本在本地生成的完整图片包；它与目录内源图和导出图重复，因此不纳入 Git。

APK 资源包含六档密度图标、Android 8.0 及以上使用的自适应图标、首页悬浮导航字标，以及电视横幅。重新生成：

```sh
python3 branding/generate_brand_assets.py
```
