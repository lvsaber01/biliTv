package com.lvsaber.blblrebuild.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.media3.common.util.UnstableApi
import com.lvsaber.blblrebuild.R
import com.lvsaber.blblrebuild.core.AppExecutors
import com.lvsaber.blblrebuild.data.BiliApiClient
import com.lvsaber.blblrebuild.data.BiliApiException
import com.lvsaber.blblrebuild.data.AppSettings
import com.lvsaber.blblrebuild.data.VideoSummary

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var content: FrameLayout
    private lateinit var homeGrid: GridLayout
    private lateinit var homeContainer: LinearLayout
    private lateinit var searchGrid: GridLayout
    private lateinit var searchInput: EditText
    private lateinit var homeStatus: TextView
    private var homeVideos: List<VideoSummary> = emptyList()
    private var searchVideos: List<VideoSummary> = emptyList()
    private var columnButtons: List<Button> = emptyList()
    private var columns = 2
    private var appSettings = AppSettings()
    private var refreshHomeOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        api = BiliApiClient(this)
        appSettings = AppSettings.load(this)
        columns = appSettings.gridColumns
        setContentView(buildRoot())
        showHome()
    }

    override fun onResume() {
        super.onResume()
        if (refreshHomeOnResume) {
            refreshHomeOnResume = false
            appSettings = AppSettings.load(this)
            columns = appSettings.gridColumns
            showHome()
        }
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 20), Ui.dp(context, 12), Ui.dp(context, 20), 0)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(Ui.text(this, "biliTv", 25f, Ui.blue).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 0)
        })
        brand.addView(Ui.text(this, "Bilibili for tablet", 11f, Ui.muted).apply {
            setPadding(0, 0, 0, 0)
        })
        toolbar.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(navButton("首页") { showHome() })
        toolbar.addView(navButton("搜索") { showSearch() })
        toolbar.addView(navButton("我的") { showMe() })
        toolbar.addView(navButton("设置") {
            refreshHomeOnResume = true
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, Ui.dp(this, 62)))
        root.addView(View(this).apply { setBackgroundColor(Ui.divider) }, LinearLayout.LayoutParams(-1, Ui.dp(this, 1)))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun navButton(label: String, action: () -> Unit) = Ui.chip(this, label, false, action).apply {
        Ui.focusHighlight(this)
        layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(context, 40)).apply {
            marginStart = Ui.dp(this@MainActivity, 8)
        }
    }

    private fun showHome() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(Ui.text(this, "发现", 29f, Ui.text).apply { setPadding(0, Ui.dp(context, 12), 0, 0) })
        heading.addView(Ui.text(this, "热门内容 · 随时发现新视频", 13f, Ui.muted).apply { setPadding(0, 0, 0, Ui.dp(context, 10)) })
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(buildStyleToggle())
        header.addView(buildColumnSelector())
        page.addView(header, LinearLayout.LayoutParams(-1, Ui.dp(this, 80)))
        homeStatus = Ui.text(this, "正在加载热门视频…", 13f, Ui.muted).apply {
            setPadding(0, 0, 0, Ui.dp(context, 8))
        }
        page.addView(homeStatus)
        homeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 24))
        }
        if (appSettings.homeStyle == AppSettings.HomeStyle.GRID) {
            homeGrid = createGrid()
            homeContainer.addView(homeGrid)
        }
        val scroll = NestedScrollView(this).apply {
            isFillViewport = true
            addView(homeContainer, FrameLayout.LayoutParams(-1, -2))
        }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.removeAllViews()
        content.addView(page)
        loadHome()
    }

    private fun showSearch() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchInput = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setTextColor(Ui.text)
            setHintTextColor(Ui.muted)
            setSingleLine(true)
            setTextSize(15f)
            setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0)
            background = Ui.roundedBackground(Ui.surfaceRaised, Ui.dp(context, 10))
            isFocusable = true
        }
        row.addView(searchInput, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        row.addView(Ui.button(this, "搜索") { doSearch(searchInput.text.toString()) }, LinearLayout.LayoutParams(-2, Ui.dp(this, 48)).apply {
            marginStart = Ui.dp(this@MainActivity, 10)
        })
        page.addView(row, LinearLayout.LayoutParams(-1, Ui.dp(this, 58)))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(Ui.text(this, "搜索结果", 20f, Ui.text).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(buildColumnSelector())
        page.addView(controls, LinearLayout.LayoutParams(-1, Ui.dp(this, 52)))
        page.addView(Ui.text(this, "支持关键词，也可以直接输入 BV 号打开视频。", 13f, Ui.muted).apply {
            setPadding(0, 0, 0, Ui.dp(context, 8))
        })
        searchGrid = GridLayout(this).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 24))
        }
        val scroll = NestedScrollView(this).apply { addView(searchGrid, FrameLayout.LayoutParams(-1, -2)) }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.removeAllViews()
        content.addView(page)
        searchInput.requestFocus()
        searchInput.setOnEditorActionListener { _, _, _ -> doSearch(searchInput.text.toString()); true }
    }

    private fun showMe() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
            setPadding(0, Ui.dp(context, 20), 0, 0)
        }
        page.addView(Ui.text(this, "我的", 29f, Ui.text).apply { setPadding(0, 0, 0, Ui.dp(context, 4)) })
        page.addView(Ui.text(this, "登录后查看自己的历史和收藏", 14f, Ui.muted).apply { setPadding(0, 0, 0, Ui.dp(context, 18)) })
        val status = Ui.text(this, "正在检查登录状态…", 16f, Ui.muted)
        page.addView(status)
        page.addView(Ui.button(this, "二维码登录") { startActivity(Intent(this, LoginActivity::class.java)) }, LinearLayout.LayoutParams(-2, Ui.dp(this, 48)))
        page.addView(Ui.button(this, "刷新登录状态") { refreshLoginStatus(status) }, LinearLayout.LayoutParams(-2, Ui.dp(this, 48)))
        page.addView(Ui.button(this, "观看历史") {
            startActivity(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, LibraryActivity.MODE_HISTORY))
        }, LinearLayout.LayoutParams(-2, Ui.dp(this, 48)))
        page.addView(Ui.button(this, "我的收藏") {
            startActivity(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, LibraryActivity.MODE_FAVORITES))
        }, LinearLayout.LayoutParams(-2, Ui.dp(this, 48)))
        page.addView(Ui.text(this, "会话使用 Android Keystore 加密，不写入明文 Cookie。", 13f, Ui.muted).apply {
            setPadding(0, Ui.dp(context, 14), 0, 0)
        })
        content.removeAllViews()
        content.addView(page)
        refreshLoginStatus(status)
    }

    private fun refreshLoginStatus(status: TextView) {
        AppExecutors.io.execute {
            val loggedIn = runCatching { api.getNavigation().optBoolean("isLogin") }.getOrDefault(false)
            runOnUiThread { status.text = if (loggedIn) "已登录" else "当前未登录" }
        }
    }

    private fun buildColumnSelector(): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 8), 0, 0, 0)
        }
        row.addView(Ui.text(this, "每行", 12f, Ui.muted).apply { setPadding(0, 0, Ui.dp(context, 6), 0) })
        columnButtons = (1..3).map { value ->
            Ui.chip(this, value.toString(), value == columns) { selectColumns(value) }.also { button ->
                button.tag = value
                row.addView(button, LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 34)).apply {
                    marginStart = Ui.dp(this@MainActivity, 4)
                })
            }
        }
        return row
    }

    private fun selectColumns(value: Int) {
        columns = value.coerceIn(1, 3)
        appSettings = appSettings.copy(gridColumns = columns)
        appSettings.save(this)
        columnButtons.forEach { button ->
            val selected = button.tag == columns
            button.setTextColor(if (selected) Color.WHITE else Ui.muted)
            button.background = Ui.roundedBackground(if (selected) Ui.blue else Ui.surfaceRaised, Ui.dp(this, 17))
        }
        if (::homeGrid.isInitialized) {
            homeGrid.columnCount = columns
            if (homeVideos.isNotEmpty()) renderVideos(homeGrid, homeVideos)
        }
        if (::searchGrid.isInitialized) {
            searchGrid.columnCount = columns
            if (searchVideos.isNotEmpty()) renderVideos(searchGrid, searchVideos)
        }
    }

    private fun buildStyleToggle(): Button = Ui.chip(
        this,
        if (appSettings.homeStyle == AppSettings.HomeStyle.RAILS) "轨道" else "网格",
        false,
    ) {
        appSettings = appSettings.copy(
            homeStyle = if (appSettings.homeStyle == AppSettings.HomeStyle.RAILS) AppSettings.HomeStyle.GRID else AppSettings.HomeStyle.RAILS,
        )
        appSettings.save(this)
        showHome()
    }.apply {
        Ui.focusHighlight(this)
        layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 66), Ui.dp(this@MainActivity, 34)).apply {
            marginStart = Ui.dp(this@MainActivity, 8)
        }
    }

    private fun createGrid(): GridLayout = GridLayout(this).apply {
        columnCount = columns
        alignmentMode = GridLayout.ALIGN_BOUNDS
        useDefaultMargins = false
    }

    private fun loadHome() {
        AppExecutors.io.execute {
            runCatching { api.getPopularVideos() }
                .onSuccess { videos -> runOnUiThread {
                    homeVideos = videos
                    renderHome(videos)
                    homeStatus.text = "热门视频 · ${videos.size} 条"
                } }
                .onFailure { error -> runOnUiThread { homeStatus.text = "热门列表加载失败：${friendlyMessage(error)}" } }
        }
    }

    private fun doSearch(rawKeyword: String) {
        val keyword = rawKeyword.trim()
        if (keyword.isBlank()) {
            Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (keyword.startsWith("BV", ignoreCase = true)) {
            startActivity(Intent(this, VideoDetailActivity::class.java).putExtra(VideoDetailActivity.EXTRA_BVID, keyword))
            return
        }
        searchGrid.removeAllViews()
        searchGrid.addView(Ui.text(this, "正在搜索…", 15f, Ui.muted).apply { setPadding(0, Ui.dp(context, 12), 0, 0) })
        AppExecutors.io.execute {
            runCatching { api.searchVideos(keyword) }
                .onSuccess { videos -> runOnUiThread { searchVideos = videos; renderVideos(searchGrid, videos) } }
                .onFailure { error -> runOnUiThread { searchGrid.removeAllViews(); searchGrid.addView(Ui.text(this, friendlyMessage(error), 15f, Color.rgb(255, 120, 120))) } }
        }
    }

    private fun renderVideos(target: GridLayout, videos: List<VideoSummary>) {
        target.removeAllViews()
        target.columnCount = columns
        if (videos.isEmpty()) {
            target.addView(Ui.text(this, "没有找到可显示的结果。", 16f, Ui.muted).apply { setPadding(0, Ui.dp(context, 18), 0, 0) })
            return
        }
        videos.forEachIndexed { index, video ->
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                rowSpec = GridLayout.spec(index / columns)
                columnSpec = GridLayout.spec(index % columns, 1f)
                setMargins(Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 10))
            }
            target.addView(videoCard(video), params)
        }
    }

    private fun renderHome(videos: List<VideoSummary>) {
        homeContainer.removeAllViews()
        if (appSettings.homeStyle == AppSettings.HomeStyle.GRID) {
            homeGrid = createGrid()
            homeContainer.addView(homeGrid)
            renderVideos(homeGrid, videos)
        } else {
            renderRails(homeContainer, videos)
        }
    }

    private fun renderRails(target: LinearLayout, videos: List<VideoSummary>) {
        if (videos.isEmpty()) {
            target.addView(Ui.text(this, "没有找到可显示的热门内容。", 16f, Ui.muted))
            return
        }
        val heroVideo = videos.first()
        val hero = FrameLayout(this).apply {
            background = Ui.roundedBackground(Ui.surface, Ui.dp(context, 14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, VideoDetailActivity::class.java).putExtra(VideoDetailActivity.EXTRA_BVID, heroVideo.bvid))
            }
        }
        Ui.focusHighlight(hero)
        val heroPoster = Ui.poster(this)
        hero.addView(heroPoster, FrameLayout.LayoutParams(-1, Ui.dp(this, 248)))
        CoverLoader.load(heroPoster, heroVideo.coverUrl)
        val shade = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(Ui.dp(context, 18), Ui.dp(context, 70), Ui.dp(context, 18), Ui.dp(context, 16))
            setBackgroundColor(0xCC0B0E12.toInt())
        }
        shade.addView(Ui.text(this, "本期推荐", 12f, Ui.blue).apply { setPadding(0, 0, 0, Ui.dp(context, 4)) })
        shade.addView(Ui.text(this, heroVideo.title, 23f, Ui.text).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, Ui.dp(context, 4))
        })
        shade.addView(Ui.text(this, "${heroVideo.author.ifBlank { "未知 UP" }}  ·  ${heroVideo.duration}  ·  播放 ${formatCount(heroVideo.views)}", 13f, Ui.muted).apply {
            setPadding(0, 0, 0, 0)
        })
        hero.addView(shade, FrameLayout.LayoutParams(-1, Ui.dp(this, 248)))
        target.addView(hero, LinearLayout.LayoutParams(-1, Ui.dp(this, 248)).apply {
            bottomMargin = Ui.dp(this@MainActivity, 20)
        })

        val groups = videos.chunked(8)
        groups.forEachIndexed { index, group ->
            addRail(target, when (index) {
                0 -> "热门精选"
                1 -> "继续发现"
                else -> "更多推荐"
            }, group)
        }
    }

    private fun addRail(target: LinearLayout, title: String, videos: List<VideoSummary>) {
        target.addView(Ui.text(this, title, 20f, Ui.text).apply {
            setPadding(0, 0, 0, Ui.dp(context, 8))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, Ui.dp(context, 8), Ui.dp(context, 4))
        }
        videos.forEach { video ->
            row.addView(videoCard(video), LinearLayout.LayoutParams(Ui.dp(this, 270), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(this@MainActivity, 12)
            })
        }
        val horizontal = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(row, ViewGroup.LayoutParams(-2, -2))
        }
        target.addView(horizontal, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(this@MainActivity, 22)
        })
    }

    private fun videoCard(video: VideoSummary): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.panel(context)
            isClickable = true
            isFocusable = true
            setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 10))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, VideoDetailActivity::class.java).putExtra(VideoDetailActivity.EXTRA_BVID, video.bvid))
            }
        }
        Ui.focusHighlight(card)
        val poster = Ui.poster(this)
        card.addView(poster, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        CoverLoader.load(poster, video.coverUrl)
        card.addView(Ui.text(this, video.title, 16f, Ui.text).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(context, 4), Ui.dp(context, 9), Ui.dp(context, 4), 0)
        }, LinearLayout.LayoutParams(-1, Ui.dp(this, 52)))
        card.addView(Ui.text(this, "${video.author.ifBlank { "未知 UP" }}  ·  ${video.duration}", 12f, Ui.muted).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), 0)
        })
        card.addView(Ui.text(this, "播放 ${formatCount(video.views)}  ·  弹幕 ${formatCount(video.danmaku)}", 12f, Ui.muted).apply {
            setPadding(Ui.dp(context, 4), Ui.dp(context, 3), Ui.dp(context, 4), 0)
        })
        return card
    }

    private fun formatCount(value: Long): String = when {
        value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000f)
        value >= 10_000 -> "%.1f万".format(value / 10_000f)
        else -> value.toString()
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is BiliApiException -> "接口 ${error.apiCode}：${error.message}"
        else -> error.message ?: "网络请求失败"
    }

}
