package com.lvsaber.blblrebuild.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.lvsaber.blblrebuild.data.AppSettings

class SettingsActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private var settings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings.load(this)
        setContentView(buildPage())
    }

    private fun buildPage(): View {
        val scroll = NestedScrollView(this).apply {
            setBackgroundColor(Ui.background)
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 24), Ui.dp(context, 18), Ui.dp(context, 24), Ui.dp(context, 28))
            setBackgroundColor(Ui.background)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.chip(this, "‹ 返回", false) { finish() }, LinearLayout.LayoutParams(-2, Ui.dp(this, 40)))
        header.addView(Ui.text(this, "设置", 28f, Ui.text).apply { setPadding(Ui.dp(context, 14), 0, 0, 0) }, LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f))
        content.addView(header)
        content.addView(Ui.text(this, "为平板和遥控器优化的播放与发现偏好", 14f, Ui.muted).apply {
            setPadding(0, 0, 0, Ui.dp(context, 22))
        })
        addSection("首页")
        addChoiceRow("首页展示样式", "Netflix 风格横向轨道 / 网格卡片", listOf(AppSettings.HomeStyle.RAILS, AppSettings.HomeStyle.GRID), settings.homeStyle) { value ->
            settings = settings.copy(homeStyle = value)
            saveAndRefresh()
        }
        addChoiceRow("网格每行视频数", "仅在网格模式生效", listOf(1, 2, 3), settings.gridColumns) { value ->
            settings = settings.copy(gridColumns = value)
            saveAndRefresh()
        }
        addSection("播放")
        addChoiceRow("默认分辨率", "接口不提供时会自动回退到可用清晰度", listOf(
            QualityOption(16, "360P"), QualityOption(32, "480P"), QualityOption(64, "720P"), QualityOption(80, "1080P"),
        ), QualityOption(settings.defaultQuality, qualityLabel(settings.defaultQuality))) { value ->
            settings = settings.copy(defaultQuality = value.value)
            saveAndRefresh()
        }
        addChoiceRow("默认封装格式", "MP4 兼容性最好；DASH 支持独立视频/音频轨道", AppSettings.VideoFormat.entries.toList(), settings.videoFormat) { value ->
            settings = settings.copy(videoFormat = value)
            saveAndRefresh()
        }
        addChoiceRow("默认视频编码", "DASH 模式下按编码筛选，缺失时自动回退", AppSettings.VideoCodec.entries.toList(), settings.videoCodec) { value ->
            settings = settings.copy(videoCodec = value)
            saveAndRefresh()
        }
        addBooleanRow("默认显示弹幕", settings.danmakuEnabled) { value ->
            settings = settings.copy(danmakuEnabled = value)
            saveAndRefresh()
        }
        addSection("说明")
        content.addView(Ui.text(this, "画质、格式和编码会在下一次打开视频时生效。播放页仍可以临时调整画质、编码、音轨和倍速。部分视频没有 1080P、HEVC 或 AV1 时，会自动选择服务端返回的最佳可用轨道。", 14f, Ui.muted).apply {
            gravity = Gravity.TOP
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, 0)
        })
        scroll.addView(content)
        return scroll
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
        val cycleButton = Ui.chip(this, "切换", false) {
            val index = options.indexOf(selectedValue(options, current.text.toString(), selected))
            val next = options[(index + 1).mod(options.size)]
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
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        else -> "1080P"
    }

    private fun <T> optionLabel(value: T): String = when (value) {
        is AppSettings.HomeStyle -> value.label
        is AppSettings.VideoFormat -> value.label
        is AppSettings.VideoCodec -> value.label
        is QualityOption -> value.label
        is Int -> qualityLabel(value)
        else -> value.toString()
    }

    private fun <T> selectedValue(options: List<T>, label: String, fallback: T): T = options.firstOrNull { optionLabel(it) == label } ?: fallback

    private fun buttonText(button: View, value: Boolean) {
        (button as? TextView)?.text = if (value) "已开启" else "已关闭"
    }

    private data class QualityOption(val value: Int, val label: String)
}
