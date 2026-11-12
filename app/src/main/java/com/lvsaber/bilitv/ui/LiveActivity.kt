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
import com.lvsaber.bilitv.data.LiveRoomSummary

@UnstableApi
class LiveActivity : AppCompatActivity() {
    private data class PageSnapshot(val gridState: Parcelable? = null, val focusTag: String? = null)

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
    private var moreButton: Button? = null
    private var grid: RecyclerView? = null
    private var status: TextView? = null
    private var activeAdapter: LiveGridAdapter? = null
    private var categoryParentHost: LinearLayout? = null
    private var categoryAreaHost: LinearLayout? = null
    private var activeSearchInput: EditText? = null
    private var selectedParentButton: View? = null
    private var selectedAreaButton: View? = null
    private var categoryTab: View? = null
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
                if (pageKey == PAGE_SEARCH) showPage(searchReturnPage)
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
        capturePageSnapshot()
        pageKey = key
        pageGeneration++
        loading = false
        canLoadMore = false
        livePage = 1
        moreButton = null
        grid = null
        status = null
        activeAdapter = null
        activeSearchInput = null
        selectedParentButton = null
        selectedAreaButton = null
        categoryParentHost = null
        categoryAreaHost = null
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
        actions.addView(Ui.button(this, "刷新推荐") { loadRecommendations() })
        actions.addView(Ui.button(this, "按分区浏览") { showPage(PAGE_CATEGORIES) }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(context, 42)).apply { marginStart = Ui.dp(this@LiveActivity, 8) }
        })
        page.addView(actions)
        attachStatusAndGrid(page)
        content.addView(page)
        loadRecommendations()
    }

    private fun showCategoriesPage(reload: Boolean) {
        val page = basePage("直播分区")
        page.addView(Ui.text(this, "从 B 站动态读取一级/二级分区；默认混合首页推荐与本区开播列表。", 13f, Ui.muted))
        categoryParentHost = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        categoryAreaHost = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val parentScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryParentHost)
        }
        val areaScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryAreaHost)
        }
        page.addView(parentScroll, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)))
        page.addView(areaScroll, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(Ui.button(this, if (popularityMode) "切到分类推荐" else "人气排行") {
            popularityMode = !popularityMode
            showPage(PAGE_CATEGORIES)
        })
        controls.addView(Ui.button(this, "刷新") { loadCategoryRooms(reset = true) }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(context, 42)).apply { marginStart = Ui.dp(this@LiveActivity, 8) }
        })
        page.addView(controls)
        attachStatusAndGrid(page)
        content.addView(page)
        renderCategoryFilters()
        if (reload && areas.isNotEmpty()) loadCategoryRooms(reset = true)
        else if (areas.isEmpty()) setStatus("正在加载直播分区…", true)
    }

    private fun renderCategoryFilters() {
        val parents = areas.groupBy { it.parentId to it.parentName }.entries.toList()
        if (parents.isEmpty()) {
            categoryParentHost?.let { host -> host.removeAllViews(); host.addView(Ui.text(this, "暂无分区数据", 13f, Ui.muted)) }
            return
        }
        if (parents.none { it.key.first == selectedParentId }) selectedParentId = parents.first().key.first
        val parentHost = categoryParentHost ?: return
        parentHost.removeAllViews()
        parents.forEach { entry ->
            val parentId = entry.key.first
            val button = filterChip(entry.key.second, parentId == selectedParentId) {
                selectedParentId = parentId
                selectedAreaId = 0
                renderCategoryFilters()
                loadCategoryRooms(reset = true)
            }
            if (parentId == selectedParentId) selectedParentButton = button
            button.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    navButtons[PAGE_CATEGORIES]?.requestFocus(); true
                } else if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    (selectedAreaButton ?: grid?.getChildAt(0))?.requestFocus(); true
                } else false
            }
            parentHost.addView(button)
        }
        val childHost = categoryAreaHost ?: return
        childHost.removeAllViews()
        val children = areas.filter { it.parentId == selectedParentId }
            .sortedWith(compareByDescending<LiveArea> { it.isHot }.thenBy { it.areaName })
        val allButton = filterChip("全部", selectedAreaId == 0) {
            selectedAreaId = 0
            renderCategoryFilters()
            loadCategoryRooms(reset = true)
        }
        allButton.setOnKeyListener(categoryChildKeyListener())
        if (selectedAreaId == 0) selectedAreaButton = allButton
        childHost.addView(allButton)
        children.forEach { area ->
            val button = filterChip(area.areaName, area.areaId == selectedAreaId) {
                selectedAreaId = area.areaId
                renderCategoryFilters()
                loadCategoryRooms(reset = true)
            }
            button.setOnKeyListener(categoryChildKeyListener())
            if (area.areaId == selectedAreaId) selectedAreaButton = button
            childHost.addView(button)
        }
    }

    private fun categoryChildKeyListener() = View.OnKeyListener { _, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { selectedParentButton?.requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { grid?.getChildAt(0)?.requestFocus(); true }
            else -> false
        }
    }

    private fun filterChip(label: String, selected: Boolean, action: () -> Unit): Button = Ui.chip(this, label, selected, action).apply {
        layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(this@LiveActivity, 38)).apply { marginEnd = Ui.dp(this@LiveActivity, 6) }
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
        if (rooms.isEmpty()) setStatus(empty, false) else showRooms(rooms, empty, false)
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
        row.addView(Ui.button(this, "搜索") { lastSearchText = input.text.toString().trim(); searchLiveRooms(reset = true) })
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
        setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 14))
        addView(Ui.text(this@LiveActivity, heading, 23f, Ui.text).apply {
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), Ui.dp(context, 2))
        })
    }

    private fun attachStatusAndGrid(parent: LinearLayout) {
        status = Ui.text(this, "", 13f, Ui.muted).apply { tag = "live-status" }
        parent.addView(status, LinearLayout.LayoutParams(-1, Ui.dp(this, 34)))
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@LiveActivity, Ui.videoColumns(this@LiveActivity))
            itemAnimator = null
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
        }
        activeAdapter = LiveGridAdapter(this, library) { room -> openRoom(room) }
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
        moreButton = Ui.button(this, "加载更多") { loadNextPage() }.apply { visibility = View.GONE }
        parent.addView(moreButton)
        restoreGridStateWhenReady()
    }

    private fun loadRecommendations() {
        if (pageKey != PAGE_RECOMMEND) return
        setStatus("正在加载直播推荐…", true)
        loading = true
        val token = pageGeneration
        AppExecutors.io.execute {
            val result = runCatching { api.getLiveRecommendations() }
            runOnUiThread {
                if (token != pageGeneration || pageKey != PAGE_RECOMMEND || isDestroyed) return@runOnUiThread
                loading = false
                result.onSuccess { rooms -> showRooms(rooms, "当前没有可展示的直播推荐，请稍后刷新。", false) }
                    .onFailure { showFailure(it, "直播推荐加载失败") }
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
                    api.getLiveCategoryRooms(parentId, areaId, requestedPage, popularity = true)
                } else {
                    val recommended = if (requestedPage == 1) {
                        runCatching { api.getLiveRecommendations() }.getOrDefault(emptyList())
                            .filter { room -> (areaId == 0 || room.areaId == areaId) && (parentId == 0 || room.parentAreaId == parentId) }
                    } else emptyList()
                    val localList = api.getLiveCategoryRooms(parentId, areaId, requestedPage, popularity = false)
                    (recommended + localList).distinctBy { it.roomId }
                }
            }
            runOnUiThread {
                if (token != pageGeneration || pageKey != PAGE_CATEGORIES || isDestroyed) return@runOnUiThread
                loading = false
                result.onSuccess { rooms ->
                    if (reset) {
                        livePage = 1
                        showRooms(rooms, if (popularity) "本分区暂时没有开播房间。" else "暂无推荐直播，试试其他分区或切换人气排行。", false)
                    } else {
                        livePage = requestedPage
                        appendRooms(rooms)
                    }
                    canLoadMore = rooms.size >= LIVE_PAGE_SIZE
                    updateMoreButton()
                }.onFailure { error ->
                    if (reset && activeAdapter?.itemCount == 0) showFailure(error, "该分区暂时无法加载")
                    else setStatus("加载失败：${friendlyMessage(error)}", false)
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
                    updateMoreButton()
                }.onFailure { showFailure(it, "直播搜索失败") }
            }
        }
    }

    private fun loadNextPage() {
        if (!canLoadMore || loading) return
        when (pageKey) {
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
                    if (selectedParentId == 0) selectedParentId = loaded.firstOrNull()?.parentId ?: 0
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
        canLoadMore = hasMore
        updateMoreButton()
        setStatus(if (rooms.isEmpty()) emptyMessage else "${rooms.size} 个直播间 · 选择卡片即可播放", false)
        restoreGridStateWhenReady()
    }

    private fun appendRooms(rooms: List<LiveRoomSummary>) {
        val added = activeAdapter?.append(rooms) ?: 0
        setStatus(if (added == 0) "已到当前列表末尾。" else "已加载 $added 个直播间", false)
    }

    private fun showFailure(error: Throwable, fallback: String) {
        canLoadMore = false
        updateMoreButton()
        setStatus("$fallback：${friendlyMessage(error)}", false)
    }

    private fun setStatus(text: String, busy: Boolean) {
        status?.text = if (busy) "◌  $text" else text
        status?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun updateMoreButton() {
        moreButton?.visibility = if (canLoadMore) View.VISIBLE else View.GONE
        moreButton?.isEnabled = !loading
    }

    private fun capturePageSnapshot() {
        if (!::content.isInitialized) return
        val currentGrid = grid
        val focusTag = currentFocus?.tag?.toString()?.takeIf(String::isNotBlank)
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
                grid?.postOnAnimation { grid?.findViewWithTag<View>(tag)?.requestFocus() }
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
        private val library: LiveLibraryRepository,
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
            card.bind(items[position], library)
            card.setOnKeyListener { _, keyCode, event ->
                val spanCount = (activity.grid?.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && position < spanCount) {
                    (activity.selectedAreaButton ?: activity.selectedParentButton ?: activity.categoryTab)?.requestFocus()
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
