package com.lvsaber.bilitv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lvsaber.bilitv.R
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.BiliApiException
import com.lvsaber.bilitv.data.LiveArea
import com.lvsaber.bilitv.data.LiveLibraryRepository
import com.lvsaber.bilitv.data.LiveRecommendationPolicy
import com.lvsaber.bilitv.data.LiveRoomSummary

@UnstableApi
class LiveActivity : AppCompatActivity() {
    private data class PageSnapshot(val gridState: Parcelable? = null, val focusTag: String? = null)
    private data class RoomPageResult(val rooms: List<LiveRoomSummary>, val hasMore: Boolean)

    private lateinit var api: BiliApiClient
    private lateinit var library: LiveLibraryRepository
    private lateinit var content: FrameLayout
    private lateinit var zoneButton: Button
    private lateinit var navStrip: LinearLayout
    private lateinit var navScroll: HorizontalScrollView
    private val navButtons = linkedMapOf<String, Button>()
    private val fixedButtons = linkedMapOf<String, Button>()
    private val pageSnapshots = mutableMapOf<String, PageSnapshot>()
    private var pageKey = PAGE_RECOMMEND
    private var searchReturnPage = PAGE_RECOMMEND
    private var lastSearchText = ""
    private var areas: List<LiveArea> = emptyList()
    private var selectedParentId = 0
    private var selectedAreaId = 0
    private var popularityMode = false
    private var pageGeneration = 0
    private var livePage = 1
    private var canLoadMore = false
    private var loading = false
    private var recommendationRequests = 0
    private var grid: RecyclerView? = null
    private var status: TextView? = null
    private var retryButton: Button? = null
    private var retryAction: (() -> Unit)? = null
    private var activeAdapter: LiveGridAdapter? = null
    private var activeSearchInput: EditText? = null
    private var categoryTab: View? = null
    private var areaFilterButton: Button? = null
    private var categoryModeButton: Button? = null
    private var categoryRefreshButton: Button? = null
    private var upperContentFocus: View? = null
    private var filterOverlay: View? = null
    private var previousSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.keepLandscapeOnTelevision(this)
        api = BiliApiClient(this)
        library = LiveLibraryRepository.get(this)
        pageKey = savedInstanceState?.getString(STATE_PAGE) ?: PAGE_RECOMMEND
        searchReturnPage = savedInstanceState?.getString(STATE_SEARCH_RETURN) ?: PAGE_RECOMMEND
        lastSearchText = savedInstanceState?.getString(STATE_SEARCH_TEXT).orEmpty()
        selectedParentId = savedInstanceState?.getInt(STATE_PARENT_ID) ?: 0
        selectedAreaId = savedInstanceState?.getInt(STATE_AREA_ID) ?: 0
        popularityMode = savedInstanceState?.getBoolean(STATE_POPULARITY) ?: false
        savedInstanceState?.let { state ->
            pageSnapshots[pageKey] = PageSnapshot(
                BundleCompat.getParcelable(state, STATE_GRID_STATE, Parcelable::class.java),
                state.getString(STATE_FOCUS_TAG),
            )
        }
        previousSession = api.hasSession()
        setContentView(buildRoot())
        showPage(pageKey, reload = savedInstanceState == null)
        loadAreas()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (filterOverlay != null) closeAreaFilterPanel()
                else if (pageKey == PAGE_SEARCH) showPage(searchReturnPage)
                else if (pageKey != PAGE_RECOMMEND) showPage(PAGE_RECOMMEND)
                else finish()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        capturePageSnapshot()
        outState.putString(STATE_PAGE, pageKey)
        outState.putString(STATE_SEARCH_RETURN, searchReturnPage)
        outState.putString(STATE_SEARCH_TEXT, activeSearchInput?.text?.toString() ?: lastSearchText)
        outState.putInt(STATE_PARENT_ID, selectedParentId)
        outState.putInt(STATE_AREA_ID, selectedAreaId)
        outState.putBoolean(STATE_POPULARITY, popularityMode)
        pageSnapshots[pageKey]?.let {
            outState.putParcelable(STATE_GRID_STATE, it.gridState)
            outState.putString(STATE_FOCUS_TAG, it.focusTag)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val hasSession = api.hasSession()
        if (hasSession != previousSession) {
            previousSession = hasSession
            if (pageKey == PAGE_FOLLOWING) showPage(PAGE_FOLLOWING, reload = true)
        }
    }

    override fun onDestroy() {
        pageGeneration++
        super.onDestroy()
    }

    private fun buildRoot(): View {
        val compact = Ui.isCompactWidth(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.background)
            val padding = Ui.dp(context, if (compact) 8 else 20)
            setPadding(padding, 0, padding, 0)
        }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = Ui.dp(this@LiveActivity, if (compact) 124 else 88)
        })
        val toolbar = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundedBackground(0xED151A21.toInt(), Ui.dp(context, 18))
            elevation = Ui.dp(context, 12).toFloat()
            val horizontal = Ui.dp(context, if (compact) 6 else 10)
            setPadding(horizontal, 0, horizontal, 0)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.bilitv_logo)
            contentDescription = getString(R.string.app_name)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        zoneButton = navButton("专区：直播 ▾") { showZoneChooser() }
        navStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        navScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(Ui.dp(context, 22))
            addView(navStrip)
        }
        val fixed = listOf("search" to "搜索", "me" to "我的", "history" to "历史")
        if (compact) {
            val fixedRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            fixedRow.addView(logo, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 42)))
            Ui.compactNavigationButton(zoneButton)
            fixedRow.addView(zoneButton, LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 48)).apply {
                marginStart = Ui.dp(this@LiveActivity, 4)
            })
            fixed.forEach { (key, label) ->
                val button = navButton(label) { openGlobalPage(key) }
                fixedButtons[key] = button
                Ui.compactNavigationButton(button)
                fixedRow.addView(button, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply {
                    marginStart = Ui.dp(this@LiveActivity, 2)
                })
            }
            toolbar.addView(fixedRow, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)))
            toolbar.addView(navScroll, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)))
            root.addView(toolbar, FrameLayout.LayoutParams(-1, Ui.dp(this, 100)).apply { topMargin = Ui.dp(this@LiveActivity, 14) })
        } else {
            toolbar.addView(logo, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 58)))
            toolbar.addView(zoneButton)
            toolbar.addView(navScroll, LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1f))
            fixed.forEach { (key, label) ->
                val button = navButton(label) { openGlobalPage(key) }
                fixedButtons[key] = button
                toolbar.addView(button)
            }
            root.addView(toolbar, FrameLayout.LayoutParams(-1, Ui.dp(this, 62)).apply { topMargin = Ui.dp(this@LiveActivity, 14) })
        }
        buildNavigation()
        return root
    }

    private fun navButton(label: String, action: () -> Unit): Button = Ui.chip(this, label, false, action).apply {
        Ui.focusHighlight(this)
        background = Ui.roundedBackground(android.graphics.Color.TRANSPARENT, Ui.dp(context, 12))
        layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(this@LiveActivity, 42)).apply { marginStart = Ui.dp(this@LiveActivity, 5) }
        setOnFocusChangeListener { view, focused ->
            Ui.applyFocusHighlight(view, focused)
            if (focused && parent === navStrip) navScroll.post { navScroll.smoothScrollTo((left - Ui.dp(context, 12)).coerceAtLeast(0), 0) }
        }
    }

    private fun buildNavigation() {
        navStrip.removeAllViews()
        navButtons.clear()
        listOf(
            PAGE_RECOMMEND to "推荐",
            PAGE_CATEGORIES to "分类",
            PAGE_FOLLOWING to "关注",
            PAGE_FAVORITES to "收藏",
            PAGE_RECENT to "最近看",
        ).forEach { (key, label) ->
            val button = navButton(label) { showPage(key) }
            navButtons[key] = button
            if (key == PAGE_CATEGORIES) categoryTab = button
            navStrip.addView(button)
        }
        updateNavigation()
    }

    private fun updateNavigation() {
        (navButtons + fixedButtons).forEach { (key, button) ->
            val selected = key == pageKey
            button.setTextColor(if (selected) android.graphics.Color.WHITE else Ui.muted)
            button.background = Ui.roundedBackground(if (selected) 0x27FFFFFF else android.graphics.Color.TRANSPARENT, Ui.dp(this, 12))
        }
        zoneButton.text = "专区：直播 ▾"
    }

    private fun showZoneChooser() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("切换内容专区")
            .setItems(arrayOf("视频专区", "番剧专区", "直播专区")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, MainActivity::class.java))
                    1 -> startActivity(Intent(this, BangumiActivity::class.java))
                    else -> showPage(PAGE_RECOMMEND)
                }
            }
            .show()
    }

    private fun openGlobalPage(key: String) {
        if (key == "search") {
            if (pageKey != PAGE_SEARCH) searchReturnPage = pageKey
            showPage(PAGE_SEARCH)
        } else {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PAGE, key))
        }
    }

    private fun showPage(key: String, reload: Boolean = true) {
        if (filterOverlay != null) closeAreaFilterPanel(restoreFocus = false)
        capturePageSnapshot()
        pageKey = key
        pageGeneration++
        loading = false
        canLoadMore = false
        livePage = 1
        recommendationRequests = 0
        grid = null
        status = null
        retryButton = null
        retryAction = null
        activeAdapter = null
        activeSearchInput = null
        areaFilterButton = null
        categoryModeButton = null
        categoryRefreshButton = null
        upperContentFocus = null
        content.removeAllViews()
        updateNavigation()
        when (key) {
            PAGE_RECOMMEND -> showRecommendationPage()
            PAGE_CATEGORIES -> showCategoriesPage(reload)
            PAGE_FOLLOWING -> showFollowingPage()
            PAGE_FAVORITES -> showLocalPage(library.favorites(), "本机收藏的直播间", "还没有收藏直播间")
            PAGE_RECENT -> showLocalPage(library.recent(), "最近观看 · 最多保留 20 个", "还没有观看记录")
            PAGE_SEARCH -> showSearchPage()
            else -> showRecommendationPage()
        }
    }

    private fun showRecommendationPage() {
        val page = basePage("直播推荐")
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val refreshButton = Ui.button(this, "刷新推荐") { loadRecommendations(reset = true) }
        upperContentFocus = refreshButton
        actions.addView(refreshButton)
        actions.addView(Ui.button(this, "按分区浏览") { showPage(PAGE_CATEGORIES) }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(context, 42)).apply { marginStart = Ui.dp(this@LiveActivity, 8) }
        })
        page.addView(actions)
        attachStatusAndGrid(page)
        content.addView(page)
        loadRecommendations(reset = true)
    }

    private fun showCategoriesPage(reload: Boolean) {
        val page = basePage("直播分区")
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        areaFilterButton = Ui.button(this, categorySelectionLabel()) { showAreaFilterPanel() }.apply {
            tag = "live-area-filter"
            contentDescription = "选择直播分区，${categorySelectionLabel()}"
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@LiveActivity, 38), 1f)
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { navButtons[PAGE_CATEGORIES]?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { grid?.getChildAt(0)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { categoryModeButton?.requestFocus(); true }
                    else -> false
                }
            }
        }
        upperContentFocus = areaFilterButton
        controls.addView(areaFilterButton)
        categoryModeButton = Ui.button(this, if (popularityMode) "分类推荐" else "人气排行") {
            popularityMode = !popularityMode
            categoryModeButton?.text = if (popularityMode) "分类推荐" else "人气排行"
            loadCategoryRooms(reset = true)
        }.apply {
            tag = "live-sort-mode"
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(this@LiveActivity, 38)).apply {
                marginStart = Ui.dp(this@LiveActivity, 7)
            }
        }
        controls.addView(categoryModeButton)
        categoryModeButton?.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { navButtons[PAGE_CATEGORIES]?.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { grid?.getChildAt(0)?.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { areaFilterButton?.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { categoryRefreshButton?.requestFocus(); true }
                else -> false
            }
        }
        categoryRefreshButton = Ui.button(this, "刷新") { loadCategoryRooms(reset = true) }.apply {
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { navButtons[PAGE_CATEGORIES]?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { grid?.getChildAt(0)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { categoryModeButton?.requestFocus(); true }
                    else -> false
                }
            }
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(context, 38)).apply { marginStart = Ui.dp(this@LiveActivity, 7) }
        }
        controls.addView(categoryRefreshButton)
        page.addView(controls)
        attachStatusAndGrid(page)
        content.addView(page)
        if (reload && areas.isNotEmpty()) loadCategoryRooms(reset = true)
        else if (areas.isEmpty()) setStatus("正在加载直播分区…", true)
    }

    private fun categorySelectionLabel(): String {
        val parent = areas.firstOrNull { it.parentId == selectedParentId }?.parentName ?: "全部分区"
        val child = areas.firstOrNull { it.areaId == selectedAreaId }?.areaName ?: "全部"
        return if (selectedParentId == 0) "全部分区 · 全部" else "$parent · $child"
    }

    private fun updateCategoryFilterLabel() {
        val label = categorySelectionLabel()
        areaFilterButton?.text = label
        areaFilterButton?.contentDescription = "选择直播分区，$label"
    }

    private fun showAreaFilterPanel() {
        if (areas.isEmpty()) {
            setStatus("直播分区仍在加载，稍后再试。", false)
            return
        }
        val parentEntries = listOf(0 to "全部分区") + areas.groupBy { it.parentId to it.parentName }.entries
            .sortedBy { it.key.second }
            .map { it.key.first to it.key.second }
        var pendingParentId = selectedParentId
        var pendingAreaId = selectedAreaId
        if (parentEntries.none { it.first == pendingParentId }) pendingParentId = 0
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xB8000000.toInt())
            isClickable = true
            isFocusableInTouchMode = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.panel(context)
            elevation = Ui.dp(context, 18).toFloat()
            setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
        }
        panel.addView(Ui.text(this, "选择直播分区", 18f, Ui.text), LinearLayout.LayoutParams(-1, Ui.dp(this, 34)))
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val parentHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val childHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val parentScroll = ScrollView(this).apply { isFillViewport = false; addView(parentHost) }
        val childScroll = ScrollView(this).apply { isFillViewport = false; addView(childHost) }
        columns.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@LiveActivity, "频道", 12f, Ui.muted), LinearLayout.LayoutParams(-1, Ui.dp(this@LiveActivity, 26)))
            addView(parentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }, LinearLayout.LayoutParams(0, -1, 0.82f).apply { marginEnd = Ui.dp(this@LiveActivity, 8) })
        columns.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@LiveActivity, "子分区", 12f, Ui.muted), LinearLayout.LayoutParams(-1, Ui.dp(this@LiveActivity, 26)))
            addView(childScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }, LinearLayout.LayoutParams(0, -1, 1.18f))
        panel.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = Ui.dp(this@LiveActivity, 5)
            bottomMargin = Ui.dp(this@LiveActivity, 8)
        })
        var focusedParent: View? = null
        var selectedChildButton: Button? = null
        val parentButtons = mutableMapOf<Int, Button>()
        val childButtons = mutableListOf<Pair<Int, Button>>()
        fun selectChild(id: Int) {
            pendingAreaId = id
            childButtons.forEach { (candidate, button) -> Ui.setChipSelected(button, candidate == pendingAreaId) }
            selectedChildButton = childButtons.firstOrNull { it.first == id }?.second
        }
        fun renderChildren() {
            childHost.removeAllViews()
            childButtons.clear()
            selectedChildButton = null
            val all = Ui.chip(this, "全部", pendingAreaId == 0) { selectChild(0) }
            all.tag = "live-filter-child/0"
            all.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    focusedParent?.requestFocus(); true
                } else false
            }
            childHost.addView(all, LinearLayout.LayoutParams(-1, Ui.dp(this, 38)).apply { bottomMargin = Ui.dp(this@LiveActivity, 4) })
            childButtons += 0 to all
            if (pendingAreaId == 0) selectedChildButton = all
            areas.filter { pendingParentId == 0 || it.parentId == pendingParentId }
                .sortedWith(compareByDescending<LiveArea> { it.isHot }.thenBy { it.areaName })
                .forEach { area ->
                    val button = Ui.chip(this, area.areaName, area.areaId == pendingAreaId) { selectChild(area.areaId) }
                    button.tag = "live-filter-child/${area.areaId}"
                    button.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            focusedParent?.requestFocus(); true
                        } else false
                    }
                    childHost.addView(button, LinearLayout.LayoutParams(-1, Ui.dp(this, 38)).apply { bottomMargin = Ui.dp(this@LiveActivity, 4) })
                    childButtons += area.areaId to button
                    if (area.areaId == pendingAreaId) selectedChildButton = button
                }
        }
        parentEntries.forEach { entry ->
            val id = entry.first
            val button = Ui.chip(this, entry.second, id == pendingParentId) {
                pendingParentId = id
                pendingAreaId = 0
                parentButtons.forEach { (candidate, chip) -> Ui.setChipSelected(chip, candidate == pendingParentId) }
                renderChildren()
                focusedParent = parentButtons[id]
                focusedParent?.requestFocus()
            }
            button.tag = "live-filter-parent/$id"
            button.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    (selectedChildButton ?: childButtons.firstOrNull()?.second)?.requestFocus(); true
                } else false
            }
            parentHost.addView(button, LinearLayout.LayoutParams(-1, Ui.dp(this, 38)).apply { bottomMargin = Ui.dp(this@LiveActivity, 4) })
            parentButtons[id] = button
            if (id == pendingParentId) focusedParent = button
        }
        renderChildren()
        val actions = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        actions.addView(Ui.button(this, "取消") { closeAreaFilterPanel() }.apply { tag = "live-filter-cancel" })
        actions.addView(Ui.button(this, "应用") {
            selectedParentId = pendingParentId
            selectedAreaId = pendingAreaId.takeIf { candidate ->
                candidate == 0 || areas.any { it.areaId == candidate && (pendingParentId == 0 || it.parentId == pendingParentId) }
            } ?: 0
            updateCategoryFilterLabel()
            closeAreaFilterPanel()
            loadCategoryRooms(reset = true)
        }.apply {
            tag = "live-filter-apply"
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(this@LiveActivity, 42)).apply { marginStart = Ui.dp(this@LiveActivity, 8) }
        })
        panel.addView(actions, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)))
        val availableWidth = content.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val availableHeight = content.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val width = minOf(availableWidth - Ui.dp(this, 32), Ui.dp(this, if (Ui.isCompactWidth(this)) 620 else 920))
        val height = minOf(availableHeight - Ui.dp(this, 32), Ui.dp(this, 560))
        overlay.addView(panel, FrameLayout.LayoutParams(width.coerceAtLeast(Ui.dp(this, 300)), height.coerceAtLeast(Ui.dp(this, 280)), Gravity.CENTER))
        content.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        filterOverlay = overlay
        val initialFocus = selectedChildButton ?: focusedParent ?: parentButtons.values.firstOrNull()
        initialFocus?.post { initialFocus.requestFocus() }
    }

    private fun closeAreaFilterPanel(restoreFocus: Boolean = true) {
        val overlay = filterOverlay ?: return
        content.removeView(overlay)
        filterOverlay = null
        if (restoreFocus) areaFilterButton?.post { areaFilterButton?.requestFocus() }
    }

    private fun showFollowingPage() {
        val page = basePage("关注的直播")
        if (!api.hasSession()) {
            page.addView(Ui.text(this, "登录后查看已关注且正在直播的主播。", 14f, Ui.muted))
            page.addView(Ui.button(this, "扫码登录") { startActivity(Intent(this, LoginActivity::class.java)) })
            content.addView(page)
            return
        }
        attachStatusAndGrid(page)
        content.addView(page)
        setStatus("正在加载关注直播…", true)
        val token = pageGeneration
        AppExecutors.io.execute {
            val result = runCatching { api.getLiveFollowing() }
            runOnUiThread {
                if (token != pageGeneration || pageKey != PAGE_FOLLOWING || isDestroyed) return@runOnUiThread
                result.onSuccess { rooms -> showRooms(rooms, "关注的主播当前没有开播", false) }
                    .onFailure { showFailure(it, "关注列表加载失败") }
            }
        }
    }

    private fun showLocalPage(rooms: List<LiveRoomSummary>, title: String, empty: String) {
        val page = basePage(title)
        attachStatusAndGrid(page)
        content.addView(page)
        if (rooms.isEmpty()) setStatus(empty, false) else {
            showRooms(rooms, empty, false)
            refreshLocalRoomStatuses(rooms)
        }
    }

    private fun showSearchPage() {
        val page = basePage("搜索直播间")
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "搜索直播间 / 主播"
            setSingleLine(true)
            setTextColor(Ui.text)
            setHintTextColor(Ui.muted)
            background = Ui.panel(context)
            setText(lastSearchText)
            tag = "live-search-input"
        }
        activeSearchInput = input
        row.addView(input, LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f))
        val submitButton = Ui.button(this, "搜索") { lastSearchText = input.text.toString().trim(); searchLiveRooms(reset = true) }
        upperContentFocus = submitButton
        row.addView(submitButton)
        input.setOnEditorActionListener { _, _, _ -> lastSearchText = input.text.toString().trim(); searchLiveRooms(reset = true); true }
        page.addView(row)
        attachStatusAndGrid(page)
        content.addView(page)
        if (lastSearchText.isNotBlank()) searchLiveRooms(reset = true)
        else setStatus("输入关键词，查找正在直播的房间。", false)
        input.requestFocus()
    }

    private fun basePage(heading: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 8))
        addView(Ui.text(this@LiveActivity, heading, 18f, Ui.text).apply {
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
        }, LinearLayout.LayoutParams(-1, Ui.dp(this@LiveActivity, 34)))
    }

    private fun attachStatusAndGrid(parent: LinearLayout) {
        status = Ui.text(this, "", 13f, Ui.muted).apply { tag = "live-status" }
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LinearLayout.LayoutParams(0, Ui.dp(this@LiveActivity, 28), 1f))
            val retry = Ui.button(this@LiveActivity, "重试") { retryAction?.invoke() }.apply {
                tag = "live-retry"
                visibility = View.GONE
                minHeight = Ui.dp(context, 30)
            }
            retryButton = retry
            addView(retry, LinearLayout.LayoutParams(-2, Ui.dp(this@LiveActivity, 30)))
        }
        parent.addView(statusRow, LinearLayout.LayoutParams(-1, Ui.dp(this, 30)))
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@LiveActivity, Ui.videoColumns(this@LiveActivity))
            itemAnimator = null
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
        }
        activeAdapter = LiveGridAdapter(this) { room -> openRoom(room) }
        grid?.adapter = activeAdapter
        grid?.setOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as? GridLayoutManager ?: return
                val last = manager.findLastVisibleItemPosition()
                val count = activeAdapter?.itemCount ?: 0
                if (canLoadMore && !loading && count > 0 && last >= count - manager.spanCount * 2) {
                    loadNextPage()
                }
            }
        })
        parent.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        restoreGridStateWhenReady()
    }

    private fun loadRecommendations(reset: Boolean) {
        if (pageKey != PAGE_RECOMMEND) return
        if (loading) return
        if (reset) {
            recommendationRequests = 0
            activeAdapter?.submit(emptyList())
            canLoadMore = false
        }
        setStatus("正在加载直播推荐…", true)
        loading = true
        val token = pageGeneration
        AppExecutors.io.execute {
            val result = runCatching { api.getLiveRecommendations() }
            runOnUiThread {
                if (token != pageGeneration || pageKey != PAGE_RECOMMEND || isDestroyed) return@runOnUiThread
                loading = false
                result.onSuccess { rooms ->
                    recommendationRequests = 1
                    showRooms(rooms, "当前没有可展示的直播推荐，请稍后刷新。", rooms.isNotEmpty())
                }.onFailure { showFailure(it, "直播推荐加载失败") { loadRecommendations(reset = true) } }
            }
        }
    }

    private fun loadMoreRecommendations(force: Boolean = false) {
        if (pageKey != PAGE_RECOMMEND || loading || (!force && !canLoadMore)) return
        if (recommendationRequests >= LiveRecommendationPolicy.MAX_REQUESTS_PER_VISIT) {
            canLoadMore = false
            setStatus("已达到本次推荐刷新上限。", false)
            return
        }
        loading = true
        setStatus("正在获取更多推荐直播…", true)
        val token = pageGeneration
        AppExecutors.io.execute {
            val result = runCatching { api.getLiveRecommendations() }
            runOnUiThread {
                if (token != pageGeneration || pageKey != PAGE_RECOMMEND || isDestroyed) return@runOnUiThread
                loading = false
                result.onSuccess { rooms ->
                    recommendationRequests++
                    val added = activeAdapter?.append(rooms) ?: 0
                    refreshVisibleRoomStatuses(rooms)
                    canLoadMore = LiveRecommendationPolicy.shouldRequestNext(recommendationRequests, added)
                    setStatus(
                        when {
                            added == 0 -> "已到底：推荐接口没有返回新的直播间。"
                            canLoadMore -> "已追加 $added 个直播间 · 继续下滑加载"
                            else -> "已追加 $added 个直播间 · 本次推荐批次已完成"
                        },
                        false,
                    )
                }.onFailure { error ->
                    canLoadMore = false
                    showFailure(error, "继续加载推荐失败") { loadMoreRecommendations(force = true) }
                }
            }
        }
    }

    private fun loadCategoryRooms(reset: Boolean) {
        if (pageKey != PAGE_CATEGORIES || loading || (areas.isEmpty() && !reset)) return
        loading = true
        val requestedPage = if (reset) 1 else livePage + 1
        setStatus(if (reset) "正在加载分类直播…" else "正在加载更多…", true)
        val parentId = selectedParentId
        val areaId = selectedAreaId
        val popularity = popularityMode
        val token = pageGeneration
        AppExecutors.io.execute {
            val result = runCatching {
                if (popularity) {
                    val rooms = api.getLiveCategoryRooms(parentId, areaId, requestedPage, popularity = true)
                    RoomPageResult(rooms, rooms.size >= LIVE_PAGE_SIZE)
                } else {
                    val recommended = if (requestedPage == 1) {
                        runCatching { api.getLiveRecommendations() }.getOrDefault(emptyList())
                            .filter { room -> (areaId == 0 || room.areaId == areaId) && (parentId == 0 || room.parentAreaId == parentId) }
                    } else emptyList()
                    val localList = api.getLiveCategoryRooms(parentId, areaId, requestedPage, popularity = false)
                    RoomPageResult((recommended + localList).distinctBy { it.roomId }, localList.size >= LIVE_PAGE_SIZE)
                }
            }
            runOnUiThread {
                if (token != pageGeneration || pageKey != PAGE_CATEGORIES || isDestroyed) return@runOnUiThread
                loading = false
                result.onSuccess { response ->
                    if (reset) {
                        livePage = 1
                        showRooms(response.rooms, if (popularity) "本分区暂时没有开播房间。" else "暂无推荐直播，试试其他分区或切换人气排行。", response.hasMore)
                    } else {
                        livePage = requestedPage
                        appendRooms(response.rooms)
                        canLoadMore = response.hasMore
                    }
                }.onFailure { error ->
                    val message = if (reset && activeAdapter?.itemCount == 0) "该分区暂时无法加载" else "加载失败"
                    showFailure(error, message) {
                        if (!reset) canLoadMore = true
                        loadCategoryRooms(reset)
                    }
                }
            }
        }
    }

    private fun searchLiveRooms(reset: Boolean) {
        if (pageKey != PAGE_SEARCH || loading) return
        if (lastSearchText.isBlank()) {
            setStatus("请输入直播间或主播关键词。", false)
            return
        }
        loading = true
        val requestedPage = if (reset) 1 else livePage + 1
        val query = lastSearchText
        val token = pageGeneration
        setStatus(if (reset) "正在搜索…" else "正在加载更多…", true)
        AppExecutors.io.execute {
            val result = runCatching { api.searchLiveRooms(query, requestedPage) }
            runOnUiThread {
                if (token != pageGeneration || pageKey != PAGE_SEARCH || isDestroyed) return@runOnUiThread
                loading = false
                result.onSuccess { rooms ->
                    if (reset) { livePage = 1; showRooms(rooms, "没有找到正在直播的房间。", false) }
                    else { livePage = requestedPage; appendRooms(rooms) }
                    canLoadMore = rooms.size >= LIVE_PAGE_SIZE
                }.onFailure {
                    showFailure(it, "直播搜索失败") {
                        if (!reset) canLoadMore = true
                        searchLiveRooms(reset)
                    }
                }
            }
        }
    }

    private fun loadNextPage() {
        if (!canLoadMore || loading) return
        when (pageKey) {
            PAGE_RECOMMEND -> loadMoreRecommendations()
            PAGE_CATEGORIES -> loadCategoryRooms(reset = false)
            PAGE_SEARCH -> searchLiveRooms(reset = false)
        }
    }

    private fun loadAreas() {
        if (areas.isNotEmpty()) return
        AppExecutors.io.execute {
            val result = runCatching { api.getLiveAreas() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { loaded ->
                    areas = loaded
                    if (pageKey == PAGE_CATEGORIES) {
                        showPage(PAGE_CATEGORIES)
                        if (areas.isEmpty()) setStatus("B 站没有返回直播分区。", false)
                    }
                }.onFailure {
                    if (pageKey == PAGE_CATEGORIES) setStatus("直播分区加载失败：${friendlyMessage(it)}", false)
                }
            }
        }
    }

    private fun showRooms(rooms: List<LiveRoomSummary>, emptyMessage: String, hasMore: Boolean) {
        activeAdapter?.submit(rooms)
        refreshVisibleRoomStatuses(rooms)
        canLoadMore = hasMore
        setStatus(if (rooms.isEmpty()) emptyMessage else "${rooms.size} 个直播间 · 选择卡片即可播放", false)
        restoreGridStateWhenReady()
    }

    private fun appendRooms(rooms: List<LiveRoomSummary>): Int {
        val added = activeAdapter?.append(rooms) ?: 0
        refreshVisibleRoomStatuses(rooms)
        setStatus(if (added == 0) "已到当前列表末尾。" else "已加载 $added 个直播间", false)
        return added
    }

    /** Missing live_status is not evidence that a room is live; resolve visible dynamic feeds authoritatively. */
    private fun refreshVisibleRoomStatuses(rooms: List<LiveRoomSummary>) {
        if (pageKey !in setOf(PAGE_RECOMMEND, PAGE_CATEGORIES, PAGE_SEARCH)) return
        val mids = rooms.asSequence().map { it.anchorMid }.filter { it > 0L }.distinct().take(50).toList()
        if (mids.isEmpty()) return
        val token = pageGeneration
        val targetPage = pageKey
        AppExecutors.io.execute {
            val result = runCatching { api.getLiveRoomStatuses(mids) }
            runOnUiThread {
                if (token != pageGeneration || pageKey != targetPage || isDestroyed) return@runOnUiThread
                result.onSuccess { activeAdapter?.updateStatuses(it) }
            }
        }
    }

    private fun showFailure(error: Throwable, fallback: String, retry: (() -> Unit)? = null) {
        canLoadMore = false
        val defaultRetry = retry ?: when (pageKey) {
            PAGE_RECOMMEND -> ({ loadRecommendations(reset = true) })
            PAGE_CATEGORIES -> ({ loadCategoryRooms(reset = true) })
            PAGE_SEARCH -> ({ searchLiveRooms(reset = true) })
            PAGE_FOLLOWING -> ({ showPage(PAGE_FOLLOWING, reload = true) })
            else -> null
        }
        setStatus("$fallback：${friendlyMessage(error)}", false, defaultRetry)
    }

    private fun setStatus(text: String, busy: Boolean, retry: (() -> Unit)? = null) {
        status?.text = if (busy) "◌  $text" else text
        status?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        retryAction = retry
        retryButton?.visibility = if (retry == null || busy) View.GONE else View.VISIBLE
    }

    private fun capturePageSnapshot() {
        if (!::content.isInitialized) return
        val currentGrid = grid
        val focusView = currentFocus?.takeIf { filterOverlay == null && isWithin(content, it) }
        val focusTag = focusView?.tag?.toString()?.takeIf(String::isNotBlank)
        val old = pageSnapshots[pageKey]
        pageSnapshots[pageKey] = PageSnapshot(currentGrid?.layoutManager?.onSaveInstanceState() ?: old?.gridState, focusTag ?: old?.focusTag)
        activeSearchInput?.text?.toString()?.let { lastSearchText = it }
    }

    private fun restoreGridStateWhenReady() {
        val state = pageSnapshots[pageKey] ?: return
        val restoringPage = pageKey
        grid?.post {
            if (pageKey != restoringPage || isFinishing || isDestroyed) return@post
            state.gridState?.let { grid?.layoutManager?.onRestoreInstanceState(it) }
            state.focusTag?.let { tag ->
                content.postOnAnimation { content.findViewWithTag<View>(tag)?.requestFocus() }
            }
        }
    }

    private fun isWithin(parent: View, candidate: View): Boolean {
        var current: View? = candidate
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    private fun refreshLocalRoomStatuses(rooms: List<LiveRoomSummary>) {
        val mids = rooms.map { it.anchorMid }.filter { it > 0L }.distinct()
        if (mids.isEmpty()) return
        val token = pageGeneration
        AppExecutors.io.execute {
            val result = runCatching { api.getLiveRoomStatuses(mids) }
            runOnUiThread {
                if (token != pageGeneration || pageKey !in setOf(PAGE_FAVORITES, PAGE_RECENT) || isDestroyed) return@runOnUiThread
                result.onSuccess { statuses ->
                    library.updateStatuses(statuses)
                    activeAdapter?.updateStatuses(statuses)
                    setStatus("${activeAdapter?.itemCount ?: rooms.size} 个直播间 · 状态已更新", false)
                }.onFailure {
                    setStatus("${rooms.size} 个直播间 · 状态暂不可用", false)
                }
            }
        }
    }

    private fun openRoom(room: LiveRoomSummary) {
        library.recordWatch(room)
        startActivity(Intent(this, LivePlayerActivity::class.java).putExtra(LivePlayerActivity.EXTRA_ROOM_ID, room.roomId))
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is BiliApiException -> when (error.apiCode) {
            -101, -111 -> "请重新登录"
            -352, -412 -> "B 站请求校验未通过，请稍后重试"
            else -> error.message
        }
        else -> error.message?.take(90) ?: "网络异常"
    }

    private class LiveGridAdapter(
        private val activity: LiveActivity,
        private val onOpen: (LiveRoomSummary) -> Unit,
    ) : RecyclerView.Adapter<LiveGridHolder>() {
        private val items = mutableListOf<LiveRoomSummary>()
        init { setHasStableIds(true) }
        override fun getItemCount(): Int = items.size
        override fun getItemId(position: Int): Long = items[position].roomId
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LiveGridHolder = LiveGridHolder(
            LiveRoomCard(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(6, 6, 6, 10) }
            },
        )
        override fun onBindViewHolder(holder: LiveGridHolder, position: Int) {
            val card = holder.itemView as LiveRoomCard
            card.bind(items[position])
            card.setOnKeyListener { _, keyCode, event ->
                val spanCount = (activity.grid?.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && position < spanCount) {
                    (activity.upperContentFocus ?: activity.navButtons[activity.pageKey] ?: activity.categoryTab)?.requestFocus()
                    true
                } else false
            }
            card.setOnClickListener { onOpen(items[position]) }
        }
        fun submit(rooms: List<LiveRoomSummary>) {
            items.clear(); items.addAll(rooms.distinctBy { it.roomId }); notifyDataSetChanged()
        }
        fun append(rooms: List<LiveRoomSummary>): Int {
            val known = items.mapTo(hashSetOf()) { it.roomId }
            val fresh = rooms.filter { known.add(it.roomId) }
            if (fresh.isNotEmpty()) { val start = items.size; items.addAll(fresh); notifyItemRangeInserted(start, fresh.size) }
            return fresh.size
        }

        fun updateStatuses(statuses: Map<Long, LiveRoomSummary>) {
            items.indices.forEach { index ->
                val saved = items[index]
                val current = statuses[saved.anchorMid] ?: return@forEach
                items[index] = saved.copy(liveStatus = current.liveStatus, online = current.online)
                notifyItemChanged(index)
            }
        }
    }

    private class LiveGridHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        private const val PAGE_RECOMMEND = "recommend"
        private const val PAGE_CATEGORIES = "categories"
        private const val PAGE_FOLLOWING = "following"
        private const val PAGE_FAVORITES = "favorites"
        private const val PAGE_RECENT = "recent"
        private const val PAGE_SEARCH = "search"
        private const val LIVE_PAGE_SIZE = 20
        private const val STATE_PAGE = "live_page"
        private const val STATE_SEARCH_RETURN = "live_search_return"
        private const val STATE_SEARCH_TEXT = "live_search_text"
        private const val STATE_PARENT_ID = "live_parent_area_id"
        private const val STATE_AREA_ID = "live_area_id"
        private const val STATE_POPULARITY = "live_popularity_mode"
        private const val STATE_GRID_STATE = "live_grid_state"
        private const val STATE_FOCUS_TAG = "live_focus_tag"
    }
}
