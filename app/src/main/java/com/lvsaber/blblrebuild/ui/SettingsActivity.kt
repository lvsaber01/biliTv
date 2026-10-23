package com.lvsaber.blblrebuild.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.core.view.doOnLayout
import com.lvsaber.blblrebuild.data.AppSettings
import com.lvsaber.blblrebuild.data.HomeRailCatalog
import com.lvsaber.blblrebuild.data.ChannelCatalog
import com.lvsaber.blblrebuild.data.DanmakuSettings

class SettingsActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private var settings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings.load(this)
        setContentView(buildPage())
    }

    private val sheetCache = mutableMapOf<String, Pair<NestedScrollView, LinearLayout>>()
    private val categories = linkedMapOf("appearance" to "外观与布局", "channels" to "频道与轨道", "browse" to "浏览与加载", "playback" to "播放", "danmaku" to "弹幕", "account" to "账号与存储", "about" to "关于")
    private val categoryButtons = mutableMapOf<String, Button>()
    private lateinit var sheetHost: android.widget.FrameLayout
    private var selectedSheet = "appearance"
    private var deepLinkPending = true
    private val sheetFocus = mutableMapOf<String, View>()
    private var focusEpoch = 0

    /** Traverse action rows, never geometrically jump into an unrelated sheet. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode in setOf(
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN)) focusEpoch++
        val focused = currentFocus
        if (event.action == android.view.KeyEvent.ACTION_DOWN && focused != null &&
            ::content.isInitialized && isInside(focused, content)) {
            sheetFocus[selectedSheet] = focused
            val targets = arrayListOf<View>()
            content.addFocusables(targets, View.FOCUS_FORWARD)
            val rows = targets.filter { it.isEnabled && it.isShown }.groupBy { actionRow(it) }.values.toList()
            val rowIndex = rows.indexOfFirst { focused in it }
            if (rowIndex >= 0) {
                val row = rows[rowIndex]
                val column = row.indexOf(focused)
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (column > 0) row[column - 1].requestFocus() else categoryButtons[selectedSheet]?.requestFocus()
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        row.getOrNull(column + 1)?.requestFocus(); return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val delta = if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                        rows.getOrNull(rowIndex + delta)?.let { next ->
                            val suffix = focused.tag?.toString()?.substringAfter('/')
                            val sameAction = suffix?.let { action -> next.find { it.tag?.toString()?.substringAfter('/') == action } }
                            (sameAction ?: next[column.coerceAtMost(next.lastIndex)]).requestFocus()
                        }
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var item: View? = view
        while (item != null) { if (item === ancestor) return true; item = item.parent as? View }
        return false
    }

    private fun actionRow(view: View): View {
        var item: View = view
        while (item.parent is View && item.parent !== content) {
            val parent = item.parent
            if (parent is LinearLayout && parent.orientation == LinearLayout.HORIZONTAL) return parent
            item = parent as View
        }
        return view
    }

    private fun buildPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 20), Ui.dp(context, 14), Ui.dp(context, 20), Ui.dp(context, 18))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.chip(this, "‹ 返回", false) { finish() })
        header.addView(Ui.text(this, "设置", 28f), LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1f))
        root.addView(header)
        val columns = LinearLayout(this)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        categories.forEach { (key, label) ->
            val button = Ui.chip(this, label, false) { selectSheet(key) }.apply {
                id = View.generateViewId(); Ui.focusHighlight(this)
                setOnKeyListener { _, code, event ->
                    if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && event.action == android.view.KeyEvent.ACTION_DOWN) {
                        selectSheet(key)
                        val pane = content
                        val epoch = focusEpoch
                        pane.doOnLayout { pane.post {
                            if (selectedSheet != key || content !== pane || focusEpoch != epoch) return@post
                            val targets = arrayListOf<View>()
                            pane.addFocusables(targets, View.FOCUS_FORWARD)
                            val remembered = sheetFocus[key]?.takeIf { it.isShown && it.isEnabled && isInside(it, pane) }
                            (remembered ?: targets.firstOrNull())?.requestFocus()
                        } }
                        true
                    } else false
                }
            }
            categoryButtons[key] = button
            nav.addView(button, LinearLayout.LayoutParams(-1, Ui.dp(this, 50)).apply { bottomMargin = Ui.dp(this@SettingsActivity, 8) })
        }
        columns.addView(NestedScrollView(this).apply { addView(nav) }, LinearLayout.LayoutParams(Ui.dp(this, 155), -1))
        sheetHost = android.widget.FrameLayout(this).apply {
            background = Ui.roundedBackground(Ui.surface, Ui.dp(context, 18))
        }
        columns.addView(sheetHost, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(this@SettingsActivity, 16) })
        root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        val requested = intent.getStringExtra("section")
        selectSheet(when {
            requested == "home" || ChannelCatalog.find(requested) != null -> "channels"
            requested in categories -> requested!!
            else -> "appearance"
        })
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val selected = categoryButtons[selectedSheet]
                if (selected?.hasFocus() == true) finish() else selected?.requestFocus()
            }
        })
        return root
    }

    private fun selectSheet(key: String) {
        selectedSheet = key
        val cached = sheetCache[key]
        val scroll: NestedScrollView
        if (cached != null) {
            scroll = cached.first; content = cached.second
        } else {
            content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(context, 22), Ui.dp(context, 16), Ui.dp(context, 22), Ui.dp(context, 24))
            }
            scroll = NestedScrollView(this).apply { isFillViewport = true; addView(content) }
            sheetCache[key] = scroll to content
            content.addView(Ui.text(this, categories[key].orEmpty(), 25f))
            when (key) {
                "appearance" -> {
                    addChoiceRow("首页展示样式", "轨道 / 网格", AppSettings.HomeStyle.entries.toList(), settings.homeStyle) {
                        settings = settings.copy(homeStyle = it); saveAndRefresh()
                    }
                    addChoiceRow("网格每行视频数", "首页网格布局", listOf(1, 2, 3), settings.gridColumns) {
                        settings = settings.copy(gridColumns = it); saveAndRefresh()
                    }
                    addBooleanRow("显示首页头部推荐", settings.heroEnabled) { settings = settings.copy(heroEnabled = it); saveAndRefresh() }
                }
                "channels" -> { addSection("首页轨道"); addRailManagement(); addChannelManagement() }
                "browse" -> {
                    addBooleanRow("接近末尾自动预加载", settings.railPrefetchEnabled) { settings = settings.copy(railPrefetchEnabled = it); saveAndRefresh() }
                    content.addView(Ui.text(this, "每次展示 12 张；预加载只准备下一批，视频按 BV 去重。本机进度每 5 秒保存，并在暂停、退出时补存。", 14f, Ui.muted))
                }
                "playback" -> {
                    val quality = listOf(
                        QualityOption(0, "自动 · 最高兼容"),
                        QualityOption(80, "最高 1080P"),
                        QualityOption(120, "最高 4K"),
                        QualityOption(127, "最高 8K"),
                    )
                    addChoiceRow("默认分辨率", "没有目标画质时自动回退", quality, QualityOption(settings.defaultQuality, qualityLabel(settings.defaultQuality))) {
                        settings = settings.copy(defaultQuality = it.value); saveAndRefresh()
                    }
                    addChoiceRow("默认封装格式", "MP4 兼容 / DASH 独立音视频", AppSettings.VideoFormat.entries.toList(), settings.videoFormat) {
                        settings = settings.copy(videoFormat = it); saveAndRefresh()
                    }
                    addChoiceRow("默认视频编码", "编码不可用时回退", AppSettings.VideoCodec.entries.toList(), settings.videoCodec) {
                        settings = settings.copy(videoCodec = it); saveAndRefresh()
                    }
                    addChoiceRow("画面比例", "播放器默认显示方式", AppSettings.VideoResizeMode.entries.toList(), settings.videoResizeMode) {
                        settings = settings.copy(videoResizeMode = it); saveAndRefresh()
                    }
                    val subtitleSizes = listOf(DisplayOption(0.8f, "小"), DisplayOption(1f, "标准"), DisplayOption(1.25f, "大"))
                    addChoiceRow("字幕字号", "播放器内字幕覆盖层大小", subtitleSizes, subtitleSizes.first { it.value == settings.subtitleTextScale }) {
                        settings = settings.copy(subtitleTextScale = it.value); saveAndRefresh()
                    }
                    addBooleanRow("实验性高规格播放", settings.experimentalQualityEnabled) {
                        settings = settings.copy(experimentalQualityEnabled = it); saveAndRefresh()
                    }
                    content.addView(Ui.text(this, "关闭时按当前设备的解码器、最大分辨率、帧率和 HDR 显示能力过滤；开启后允许在播放器中手动尝试服务端返回但设备未明确声明支持的轨道。实验性轨道不会被自动模式优先选择。", 13f, Ui.muted))
                }
                "danmaku" -> {
                    addBooleanRow("默认显示弹幕", settings.danmakuEnabled) { settings = settings.copy(danmakuEnabled = it); saveAndRefresh() }
                    val areas = DanmakuSettings.AREAS.map { DisplayOption(it, if (it == 100) "全屏" else "上方 $it%") }
                    addChoiceRow("覆盖范围", "从视频画面顶部向下覆盖，不含黑边", areas, areas.first { it.value == settings.danmaku.areaPercent }) {
                        settings = settings.copy(danmaku = settings.danmaku.copy(areaPercent = it.value)); saveAndRefresh()
                    }
                    val speeds = DanmakuSettings.SPEEDS.map { DisplayOption(it, "${it} 倍") }
                    addChoiceRow("弹幕速度", "相对视频时间；数值越大移动越快，1 倍约 6 秒穿屏", speeds, speeds.first { it.value == settings.danmaku.speed }) {
                        settings = settings.copy(danmaku = settings.danmaku.copy(speed = it.value)); saveAndRefresh()
                    }
                    val sizes = listOf(DisplayOption(0.8f, "小"), DisplayOption(1f, "标准"), DisplayOption(1.25f, "大"))
                    addChoiceRow("弹幕字号", "根据屏幕密度缩放", sizes, sizes.first { it.value == settings.danmaku.textScale }) {
                        settings = settings.copy(danmaku = settings.danmaku.copy(textScale = it.value)); saveAndRefresh()
                    }
                    val opacities = DanmakuSettings.OPACITIES.map { DisplayOption(it, "$it%") }
                    addChoiceRow("不透明度", "数值越大越清晰，越小越通透", opacities, opacities.first { it.value == settings.danmaku.opacityPercent }) {
                        settings = settings.copy(danmaku = settings.danmaku.copy(opacityPercent = it.value)); saveAndRefresh()
                    }
                    val densities = listOf(DisplayOption(12, "低 · 最多 12 条"), DisplayOption(24, "中 · 最多 24 条"), DisplayOption(40, "高 · 最多 40 条"))
                    addChoiceRow("弹幕密度", "同屏上限；空间不足会减少显示，不补发积压弹幕", densities, densities.first { it.value == settings.danmaku.maxVisible }) {
                        settings = settings.copy(danmaku = settings.danmaku.copy(maxVisible = it.value)); saveAndRefresh()
                    }
                    content.addView(Ui.text(this, "设置自动保存；返回播放器即时生效，不重启视频。仅调整滚动弹幕，不支持智能防遮挡。", 14f, Ui.muted))
                }
                "account" -> {
                    content.addView(Ui.text(this, "账号进度独立保存，退出登录后使用访客记录。清理封面缓存保留观看进度和设置。", 14f, Ui.muted))
                    content.addView(Ui.button(this, "二维码登录") { startActivity(android.content.Intent(this, LoginActivity::class.java)) })
                    content.addView(Ui.button(this, "退出登录") {
                        confirm("退出当前账号？") {
                            com.lvsaber.blblrebuild.data.BiliApiClient(this).clearSession()
                            com.lvsaber.blblrebuild.data.ProgressRepository.get(this).refreshAccount()
                            android.widget.Toast.makeText(this, "已退出登录", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    })
                    content.addView(Ui.button(this, "清理封面缓存") {
                        confirm("清理已缓存的封面图片？") {
                            android.widget.Toast.makeText(this, "正在清理封面缓存…", android.widget.Toast.LENGTH_SHORT).show()
                            CoverLoader.clear(this) { success ->
                                if (!isDestroyed) android.widget.Toast.makeText(this, if (success) "封面缓存已清理" else "清理未完成，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                }
                "about" -> {
                    val version = packageManager.getPackageInfo(packageName, 0).versionName
                    content.addView(Ui.text(this, "biliTv $version", 22f))
                    content.addView(Ui.text(this, "悬浮导航 · 历史回看 · 全局续播\n观看进度保存在本机；登录后按需读取云端历史。", 15f, Ui.muted))
                }
            }
            if (key in setOf("appearance", "browse", "playback", "danmaku")) content.addView(Ui.chip(this, "恢复本页默认设置", false) {
                confirm("恢复${categories[key]}的默认设置？") {
                    val defaults = AppSettings()
                    settings = when (key) {
                        "appearance" -> settings.copy(homeStyle = defaults.homeStyle, gridColumns = defaults.gridColumns, heroEnabled = defaults.heroEnabled)
                        "browse" -> settings.copy(railPrefetchEnabled = defaults.railPrefetchEnabled)
                        "danmaku" -> settings.copy(danmakuEnabled = defaults.danmakuEnabled, danmaku = defaults.danmaku)
                        else -> settings.copy(defaultQuality = defaults.defaultQuality, videoFormat = defaults.videoFormat, videoCodec = defaults.videoCodec,
                            videoResizeMode = defaults.videoResizeMode, subtitleTextScale = defaults.subtitleTextScale)
                    }
                    settings.save(this); sheetCache.remove(key); selectSheet(key); categoryButtons[key]?.requestFocus()
                }
            })
        }
        sheetHost.removeAllViews(); sheetHost.addView(scroll)
        if (key == "channels" && deepLinkPending) {
            deepLinkPending = false
            val requested = intent.getStringExtra("section")
            val targetTag = if (ChannelCatalog.find(requested) != null) "channel-selector" else if (requested == "channels") "channel-management" else null
            targetTag?.let { tag -> scroll.post { content.findViewWithTag<View>(tag)?.let { scroll.scrollTo(0, it.top) } } }
        }
        categoryButtons.forEach { (category, button) ->
            button.background = Ui.roundedBackground(if (category == key) Ui.blue else Ui.surfaceRaised, Ui.dp(this, 12))
        }
    }

    private fun confirm(message: String, action: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> action() }.show()
    }

    private fun addChannelManagement(): View {
        val anchor = Ui.text(this, "频道管理", 22f, Ui.text)
        anchor.tag = "channel-management"
        content.addView(anchor)
        content.addView(Ui.text(this, "固定频道显示在顶部；其余频道仍可从“更多频道”进入。调整后自动保存。", 13f, Ui.muted))
        val topHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(topHost)
        fun renderTop(focus: String? = null) {
            val keys = ChannelCatalog.normalize(settings.channelOrder, ChannelCatalog.keys)
            renderManagedRows(topHost, keys, { ChannelCatalog.find(it)?.title.orEmpty() },
                { it !in settings.hiddenTopChannels }, "固定", "更多", focus,
                toggle = { key ->
                    val hidden = settings.hiddenTopChannels.toMutableSet()
                    if (!hidden.add(key)) hidden.remove(key)
                    settings = settings.copy(hiddenTopChannels = hidden)
                    settings.save(this)
                    renderTop("$key/toggle")
                }, move = { key, delta ->
                    val order = keys.toMutableList()
                    val index = order.indexOf(key)
                    java.util.Collections.swap(order, index, index + delta)
                    settings = settings.copy(channelOrder = order)
                    settings.save(this)
                    renderTop("$key/${if (delta < 0) "up" else "down"}")
                })
        }
        renderTop()
        content.addView(Ui.chip(this, "恢复默认顶部频道", false) {
            settings = settings.copy(channelOrder = ChannelCatalog.keys,
                hiddenTopChannels = (ChannelCatalog.keys - ChannelCatalog.defaultPinned.toSet()).toSet())
            settings.save(this)
            renderTop()
        })
        content.addView(Ui.text(this, "频道内轨道", 20f))
        var selected = ChannelCatalog.find(intent.getStringExtra("section")) ?: ChannelCatalog.channels.first()
        val railHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderChannel(focus: String? = null) {
            val keys = ChannelCatalog.normalize(settings.channelRailOrders[selected.key].orEmpty(), selected.rails.map { it.key })
            renderManagedRows(railHost, keys, { key -> selected.rails.find { it.key == key }?.title.orEmpty() },
                { "${selected.key}/$it" !in settings.hiddenChannelRails }, "显示", "隐藏", focus,
                toggle = { key ->
                    val hidden = settings.hiddenChannelRails.toMutableSet()
                    val fullKey = "${selected.key}/$key"
                    if (!hidden.add(fullKey)) hidden.remove(fullKey)
                    settings = settings.copy(hiddenChannelRails = hidden)
                    settings.save(this)
                    renderChannel("$key/toggle")
                }, move = { key, delta ->
                    val order = keys.toMutableList()
                    val index = order.indexOf(key)
                    java.util.Collections.swap(order, index, index + delta)
                    settings = settings.copy(channelRailOrders = settings.channelRailOrders + (selected.key to order))
                    settings.save(this)
                    renderChannel("$key/${if (delta < 0) "up" else "down"}")
                })
        }
        addChoiceRow("选择频道", "每个频道单独保存轨道设置；主题推荐按关键词和分类聚合。", ChannelCatalog.channels, selected) {
            selected = it
            renderChannel()
        }
        content.getChildAt(content.childCount - 1).tag = "channel-selector"
        content.addView(railHost)
        content.addView(Ui.chip(this, "恢复当前频道轨道", false) {
            settings = settings.copy(channelRailOrders = settings.channelRailOrders - selected.key,
                hiddenChannelRails = settings.hiddenChannelRails.filterNot { it.startsWith("${selected.key}/") }.toSet())
            settings.save(this)
            renderChannel()
        })
        renderChannel()
        return anchor
    }

    private fun renderManagedRows(
        host: LinearLayout, keys: List<String>, label: (String) -> String, enabled: (String) -> Boolean,
        enabledLabel: String, disabledLabel: String, focus: String?,
        toggle: (String) -> Unit, move: (String, Int) -> Unit,
    ) {
        val epoch = focusEpoch
        host.removeAllViews()
        val focusable = mutableListOf<View>()
        keys.forEachIndexed { index, key ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = Ui.panel(context) }
            row.addView(Ui.text(this, label(key), 16f), LinearLayout.LayoutParams(0, -2, 1f))
            fun action(text: String, suffix: String, available: Boolean = true, block: () -> Unit) {
                val button = Ui.chip(this, text, suffix == "toggle" && enabled(key), block).apply {
                    tag = "$key/$suffix"; isEnabled = available; Ui.focusHighlight(this)
                    if (!available) alpha = 0.4f
                }
                row.addView(button, LinearLayout.LayoutParams(Ui.dp(this, if (suffix == "toggle") 80 else 48), Ui.dp(this, 42)))
                focusable += button
            }
            action(if (enabled(key)) enabledLabel else disabledLabel, "toggle") { toggle(key) }
            action("↑", "up", index > 0) { move(key, -1) }
            action("↓", "down", index < keys.lastIndex) { move(key, 1) }
            host.addView(row, LinearLayout.LayoutParams(-1, Ui.dp(this, 62)).apply { bottomMargin = Ui.dp(this@SettingsActivity, 6) })
        }
        if (focus != null) host.doOnLayout { host.post {
            val desired = focusable.find { it.tag == focus && it.isEnabled }
                ?: focusable.find { it.tag == "${focus.substringBefore('/')}/toggle" }
            if (host.isShown && selectedSheet == "channels" && focusEpoch == epoch) desired?.let {
                it.requestFocus(); sheetFocus[selectedSheet] = it
            }
        } }
    }

    private fun addRailManagement() {
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(Ui.text(this, "轨道顺序与显示", 16f, Ui.text).apply {
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
        })
        content.addView(Ui.text(this, "遥控器上下移动轨道，左右调整操作按钮；首页上下切换时不会经过这些标题按钮。", 12f, Ui.muted).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, Ui.dp(context, 10))
        })
        content.addView(host)
        renderRailManagement(host)
    }

    private fun renderRailManagement(host: LinearLayout, focus: String? = null) {
        val order = HomeRailCatalog.normalizeOrder(settings.railOrder)
        renderManagedRows(host, order, { key -> HomeRailCatalog.rails.find { it.key == key }?.title.orEmpty() },
            { it !in settings.hiddenRailKeys }, "显示", "隐藏", focus,
            toggle = { key ->
                val hidden = settings.hiddenRailKeys.toMutableSet()
                if (!hidden.add(key)) hidden.remove(key)
                settings = settings.copy(hiddenRailKeys = hidden)
                settings.save(this)
                renderRailManagement(host, "$key/toggle")
            }, move = { key, delta ->
                val next = order.toMutableList()
                java.util.Collections.swap(next, next.indexOf(key), next.indexOf(key) + delta)
                settings = settings.copy(railOrder = next)
                settings.save(this)
                renderRailManagement(host, "$key/${if (delta < 0) "up" else "down"}")
            })
        val actions = LinearLayout(this)
        actions.addView(Ui.chip(this, "恢复默认排序", false) {
            settings = settings.copy(railOrder = HomeRailCatalog.defaultKeys)
            settings.save(this)
            renderRailManagement(host, "${HomeRailCatalog.defaultKeys.first()}/toggle")
        })
        actions.addView(Ui.chip(this, "全部显示", false) {
            settings = settings.copy(hiddenRailKeys = emptySet())
            settings.save(this)
            renderRailManagement(host, "${order.first()}/toggle")
        })
        host.addView(actions)
    }

    private fun addSection(title: String) {
        content.addView(Ui.text(this, title.uppercase(), 12f, Ui.blue).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, Ui.dp(context, 18), 0, Ui.dp(context, 8))
        })
    }

    private fun <T> addChoiceRow(title: String, summary: String, options: List<T>, selected: T, onChange: (T) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 10), Ui.dp(context, 10))
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(Ui.text(this, title, 16f, Ui.text).apply { setPadding(0, 0, 0, Ui.dp(context, 3)) })
        labels.addView(Ui.text(this, summary, 12f, Ui.muted).apply { setPadding(0, 0, 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val current = Ui.text(this, optionLabel(selected), 14f, Ui.blue).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
        }
        row.addView(current, LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 42)))
        var currentValue = selected
        val cycleButton = Ui.chip(this, "切换", false) {
            val index = options.indexOf(currentValue)
            val next = options[(index + 1).mod(options.size)]
            currentValue = next
            current.text = optionLabel(next)
            onChange(next)
        }
        row.addView(cycleButton, LinearLayout.LayoutParams(Ui.dp(this, 74), Ui.dp(this, 38)))
        content.addView(row, LinearLayout.LayoutParams(-1, Ui.dp(this, 72)).apply {
            bottomMargin = Ui.dp(this@SettingsActivity, 8)
        })
    }

    private fun addBooleanRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        var current = checked
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 10), 0)
        }
        row.addView(Ui.text(this, title, 16f, Ui.text).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var button: Button
        button = Ui.chip(this, if (current) "已开启" else "已关闭", current) {
            current = !current
            buttonText(button = button, value = current)
            button.background = Ui.roundedBackground(if (current) Ui.blue else Ui.surfaceRaised, Ui.dp(this, 17))
            button.setTextColor(if (current) android.graphics.Color.WHITE else Ui.muted)
            onChange(current)
        }
        row.addView(button, LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 38)))
        content.addView(row, LinearLayout.LayoutParams(-1, Ui.dp(this, 58)).apply {
            bottomMargin = Ui.dp(this@SettingsActivity, 8)
        })
    }

    private fun saveAndRefresh() {
        settings.save(this)
    }

    private fun qualityLabel(value: Int): String = when (value) {
        0 -> "自动 · 最高兼容"
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        80 -> "最高 1080P"
        120 -> "最高 4K"
        127 -> "最高 8K"
        else -> "自动 · 最高兼容"
    }

    private fun <T> optionLabel(value: T): String = when (value) {
        is AppSettings.HomeStyle -> value.label
        is AppSettings.VideoFormat -> value.label
        is AppSettings.VideoCodec -> value.label
        is QualityOption -> value.label
        is DisplayOption<*> -> value.label
        is com.lvsaber.blblrebuild.data.ChannelDefinition -> value.title
        else -> value.toString()
    }

    private fun buttonText(button: View, value: Boolean) {
        (button as? TextView)?.text = if (value) "已开启" else "已关闭"
    }

    private data class QualityOption(val value: Int, val label: String)
    private data class DisplayOption<T>(val value: T, val label: String)
}
