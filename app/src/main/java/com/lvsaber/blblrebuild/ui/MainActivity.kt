package com.lvsaber.blblrebuild.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
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
import com.lvsaber.blblrebuild.data.HomeRailCatalog
import com.lvsaber.blblrebuild.data.HomeRailDefinition
import com.lvsaber.blblrebuild.data.VideoSummary

@UnstableApi
class MainActivity : AppCompatActivity() {
    private class RailUi(
        val config: HomeRailDefinition,
        val row: LinearLayout,
        val scroll: HorizontalScrollView,
        val status: TextView,
    ) {
        var sourceVideos: List<VideoSummary> = emptyList()
        var visibleCount = 0
        var page = 1
        var loading = false
        var generation = 0
        var focusedIndex = 0
        var focusedBvid: String? = null
        var hasFocusMemory = false
        var focusWhenReady = false
        var focusAfterAppend = false
        var lastRefreshAt = 0L
        var errorMessage: String? = null
    }

    private companion object {
        const val RAIL_CHUNK_SIZE = 8
        const val AUTO_LOAD_THRESHOLD_DP = 96
        const val REFRESH_DEBOUNCE_MS = 500L
    }

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
    private var homeLoadToken = 0
    private val railViews = mutableMapOf<String, RailUi>()
    private val renderedRailKeys = mutableListOf<String>()
    private var heroView: View? = null

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
        header.addView(Ui.chip(this, "刷新", false) {
            loadHome()
        }.apply {
            Ui.focusHighlight(this)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 66), Ui.dp(this@MainActivity, 34)).apply {
                marginStart = Ui.dp(this@MainActivity, 8)
            }
        })
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
        val requestToken = ++homeLoadToken
        railViews.clear()
        renderedRailKeys.clear()
        heroView = null
        homeStatus.text = "正在刷新推荐和频道…"
        homeContainer.removeAllViews()
        AppExecutors.io.execute {
            val result = runCatching { api.getPopularVideos() }
            runOnUiThread {
                if (requestToken != homeLoadToken) return@runOnUiThread
                result.onSuccess { videos ->
                    homeVideos = videos
                    renderHome(videos)
                    homeStatus.text = if (appSettings.homeStyle == AppSettings.HomeStyle.RAILS) {
                        "推荐 ${videos.size} 条 · 上下切换轨道 · 首卡按左刷新 · 末卡按右加载"
                    } else {
                        "热门视频 · ${videos.size} 条"
                    }
                }.onFailure { error ->
                    homeStatus.text = "推荐列表加载失败：${friendlyMessage(error)}"
                    homeContainer.addView(Ui.button(this, "重新加载") { loadHome() })
                }
            }
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
        heroView = null
        renderedRailKeys.clear()
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
        if (appSettings.heroEnabled && videos.isNotEmpty()) {
            val heroVideo = videos.first()
            val hero = FrameLayout(this).apply {
                background = Ui.roundedBackground(Ui.surface, Ui.dp(context, 14))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, VideoDetailActivity::class.java).putExtra(VideoDetailActivity.EXTRA_BVID, heroVideo.bvid))
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        val firstRail = railViews.values.firstOrNull()
                        if (firstRail != null) {
                            if (firstRail.visibleCount > 0) {
                                focusRailCard(firstRail, preferredFocusIndex(firstRail, 0))
                            } else {
                                firstRail.focusWhenReady = true
                            }
                        }
                        true
                    } else {
                        false
                    }
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
            heroView = hero
        }

        val enabledRails = configuredRails()
        if (enabledRails.isEmpty()) {
            target.addView(Ui.text(this, "当前没有启用任何轨道", 17f, Ui.text).apply {
                setPadding(0, Ui.dp(context, 24), 0, Ui.dp(context, 10))
            })
            target.addView(Ui.button(this, "打开轨道设置") {
                refreshHomeOnResume = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }, LinearLayout.LayoutParams(-2, Ui.dp(this, 46)))
            return
        }
        enabledRails.forEach { config ->
            addRail(target, config, if (config.key == HomeRailCatalog.FEATURED_KEY) videos else emptyList())
        }
        focusInitialHome()
    }

    private fun configuredRails(): List<HomeRailDefinition> {
        val hidden = appSettings.hiddenRailKeys
        return HomeRailCatalog.normalizeOrder(appSettings.railOrder)
            .mapNotNull { key -> HomeRailCatalog.rails.firstOrNull { it.key == key } }
            .filterNot { it.key in hidden }
    }

    private fun focusInitialHome() {
        val hero = heroView
        if (hero != null) {
            hero.post { hero.requestFocus() }
            return
        }
        val firstRail = railViews.values.firstOrNull() ?: return
        firstRail.focusWhenReady = true
        if (firstRail.visibleCount > 0) {
            renderRailItems(firstRail)
        }
    }

    private fun addRail(
        target: LinearLayout,
        config: HomeRailDefinition,
        initialVideos: List<VideoSummary> = emptyList(),
    ) {
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.text(this, config.title, 20f, Ui.text).apply {
            setPadding(0, 0, 0, Ui.dp(context, 8))
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
        val status = Ui.text(this, if (initialVideos.isEmpty()) "加载中…" else "${initialVideos.size} 条", 12f, Ui.muted).apply {
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), Ui.dp(context, 8))
        }
        header.addView(status)
        header.addView(Ui.chip(this, "刷新", false) {
            refreshRail(config.key)
        }.apply {
            // Keep the touch entry point, but let D-pad navigation stay on cards.
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 62), Ui.dp(this@MainActivity, 34)).apply {
                marginStart = Ui.dp(this@MainActivity, 4)
                bottomMargin = Ui.dp(this@MainActivity, 8)
            }
        })
        target.addView(header)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, Ui.dp(context, 8), Ui.dp(context, 4))
        }
        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(row, ViewGroup.LayoutParams(-2, -2))
        }
        val rail = RailUi(config, row, horizontal, status).apply {
            sourceVideos = initialVideos
            visibleCount = minOf(RAIL_CHUNK_SIZE, initialVideos.size)
        }
        railViews[config.key] = rail
        renderedRailKeys += config.key
        horizontal.setOnScrollChangeListener { _, scrollX, _, oldScrollX, _ ->
            val threshold = Ui.dp(this@MainActivity, AUTO_LOAD_THRESHOLD_DP)
            val atEnd = scrollX + horizontal.width >= row.width - threshold
            if (appSettings.railPrefetchEnabled && scrollX > oldScrollX && atEnd) loadMoreRail(config.key)
        }
        target.addView(horizontal, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(this@MainActivity, 22)
        })
        if (initialVideos.isEmpty()) {
            loadRail(config.key, reset = true)
        } else {
            renderRailItems(rail)
        }
    }

    private fun refreshRail(key: String) {
        val rail = railViews[key] ?: return
        val now = SystemClock.uptimeMillis()
        if (rail.loading || now - rail.lastRefreshAt < REFRESH_DEBOUNCE_MS) return
        rail.lastRefreshAt = now
        rail.page = 1
        rail.errorMessage = null
        rail.focusedIndex = 0
        rail.focusedBvid = null
        rail.hasFocusMemory = true
        rail.scroll.smoothScrollTo(0, 0)
        loadRail(key, reset = true)
    }

    private fun loadMoreRail(key: String, focusNewContent: Boolean = false) {
        val rail = railViews[key] ?: return
        if (rail.loading) return
        if (rail.visibleCount < rail.sourceVideos.size) {
            val previousVisible = rail.visibleCount
            rail.visibleCount = minOf(rail.visibleCount + RAIL_CHUNK_SIZE, rail.sourceVideos.size)
            rail.errorMessage = null
            renderRailItems(rail, if (focusNewContent) previousVisible else null)
        } else {
            rail.focusAfterAppend = focusNewContent
            loadRail(key, reset = false)
        }
    }

    private fun loadRail(key: String, reset: Boolean) {
        val rail = railViews[key] ?: return
        if (rail.loading) return
        rail.loading = true
        rail.errorMessage = null
        val generation = ++rail.generation
        val nextPage = if (reset) 1 else rail.page + 1
        rail.status.text = if (reset) "刷新中…" else "加载更多…"
        AppExecutors.io.execute {
            val result = runCatching {
                rail.config.rid?.let { api.getRegionVideos(it) }
                    ?: api.getPopularVideos(nextPage, 20)
            }
            runOnUiThread {
                val currentRail = railViews[key]
                if (currentRail !== rail || rail.generation != generation) return@runOnUiThread
                rail.loading = false
                result.onSuccess { videos ->
                    val previousSize = rail.sourceVideos.size
                    val previousVisible = rail.visibleCount
                    val focusAfterAppend = rail.focusAfterAppend
                    val existing = rail.sourceVideos.asSequence().map { it.bvid }.toSet()
                    val incoming = if (reset) {
                        videos
                    } else {
                        rail.sourceVideos + videos.filterNot { it.bvid in existing }
                    }
                    rail.sourceVideos = incoming
                    rail.page = nextPage
                    rail.errorMessage = null
                    if (reset) {
                        rail.visibleCount = minOf(RAIL_CHUNK_SIZE, incoming.size)
                    } else if (incoming.size > previousSize) {
                        rail.visibleCount = minOf(previousVisible + RAIL_CHUNK_SIZE, incoming.size)
                    }
                    rail.focusAfterAppend = false
                    val focusIndex = when {
                        reset && (rail.hasFocusMemory || rail.focusWhenReady) -> 0
                        !reset && focusAfterAppend && incoming.size > previousSize -> previousSize
                        else -> null
                    }
                    renderRailItems(rail, focusIndex)
                    if (!reset && incoming.size == previousSize) {
                        rail.status.text = "已加载全部 · 首卡按左刷新"
                    }
                }.onFailure { error ->
                    rail.focusAfterAppend = false
                    rail.errorMessage = "加载失败 · ${friendlyMessage(error)}"
                    rail.status.text = rail.errorMessage
                }
            }
        }
    }

    private fun renderRailItems(rail: RailUi, focusIndexOverride: Int? = null) {
        val shouldRestoreFocus = focusIndexOverride != null || rail.hasFocusMemory || rail.focusWhenReady
        rail.row.removeAllViews()
        rail.sourceVideos.take(rail.visibleCount).forEachIndexed { index, video ->
            rail.row.addView(railVideoCard(rail, video, index), LinearLayout.LayoutParams(Ui.dp(this, 270), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(this@MainActivity, 12)
            })
        }
        rail.status.text = when {
            rail.loading -> "加载中…"
            rail.errorMessage != null -> rail.errorMessage
            rail.sourceVideos.isEmpty() -> "暂无内容"
            rail.visibleCount < rail.sourceVideos.size -> "${rail.visibleCount}/${rail.sourceVideos.size} · 右键继续"
            else -> "${rail.visibleCount} 条 · 末卡按右加载 · 首卡按左刷新"
        }
        if (shouldRestoreFocus && rail.visibleCount > 0) {
            val focusIndex = focusIndexOverride ?: preferredFocusIndex(rail)
            rail.focusWhenReady = false
            rail.row.post { focusRailCard(rail, focusIndex) }
        }
    }

    private fun railVideoCard(rail: RailUi, video: VideoSummary, index: Int): View {
        val card = videoCard(video)
        card.setOnFocusChangeListener { view, focused ->
            Ui.applyFocusHighlight(view, focused)
            if (focused) {
                rail.hasFocusMemory = true
                rail.focusedIndex = index
                rail.focusedBvid = video.bvid
                ensureRailCardVisible(rail, view)
                if (appSettings.railPrefetchEnabled && index >= rail.visibleCount - 2) {
                    loadMoreRail(rail.config.key)
                }
            }
        }
        card.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (index == 0) {
                        if (event.repeatCount == 0) refreshRail(rail.config.key)
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (index >= rail.visibleCount - 1) {
                        if (event.repeatCount == 0) loadMoreRail(rail.config.key, focusNewContent = true)
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> moveBetweenRails(rail, index, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveBetweenRails(rail, index, 1)
                else -> false
            }
        }
        return card
    }

    private fun moveBetweenRails(from: RailUi, fromIndex: Int, direction: Int): Boolean {
        val currentPosition = renderedRailKeys.indexOf(from.config.key)
        if (currentPosition < 0) return false
        val targetPosition = currentPosition + direction
        if (targetPosition < 0 || targetPosition >= renderedRailKeys.size) {
            if (direction < 0 && currentPosition == 0) {
                heroView?.let {
                    it.requestFocus()
                    return true
                }
            }
            return false
        }
        val target = railViews[renderedRailKeys[targetPosition]] ?: return true
        if (target.visibleCount == 0) {
            target.focusWhenReady = true
            return true
        }
        focusRailCard(target, preferredFocusIndex(target, fromIndex))
        return true
    }

    private fun preferredFocusIndex(rail: RailUi, fallback: Int = rail.focusedIndex): Int {
        if (!rail.hasFocusMemory) return 0
        val rememberedIndex = rail.focusedBvid?.let { bvid ->
            rail.sourceVideos.indexOfFirst { it.bvid == bvid }.takeIf { it >= 0 }
        } ?: fallback
        return rememberedIndex.coerceIn(0, (rail.visibleCount - 1).coerceAtLeast(0))
    }

    private fun focusRailCard(rail: RailUi, index: Int) {
        if (rail.visibleCount <= 0) return
        val safeIndex = index.coerceIn(0, rail.visibleCount - 1)
        rail.focusedIndex = safeIndex
        rail.focusedBvid = rail.sourceVideos.getOrNull(safeIndex)?.bvid
        rail.hasFocusMemory = true
        val card = rail.row.getChildAt(safeIndex) ?: return
        card.requestFocus()
        ensureRailCardVisible(rail, card)
    }

    private fun ensureRailCardVisible(rail: RailUi, card: View) {
        rail.scroll.post {
            val left = (card.left - Ui.dp(this, 18)).coerceAtLeast(0)
            rail.scroll.smoothScrollTo(left, 0)
        }
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
