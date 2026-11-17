package com.lvsaber.bilitv.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import androidx.core.widget.NestedScrollView
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lvsaber.bilitv.R
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.AppSettings
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.ChannelCatalog
import com.lvsaber.bilitv.data.VideoSummary
import com.lvsaber.bilitv.data.ProgressRepository
import com.lvsaber.bilitv.data.SearchHistoryPolicy
import com.lvsaber.bilitv.data.SearchHistoryStore

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var content: FrameLayout
    private lateinit var channelTabs: LinearLayout
    private lateinit var channelScroll: HorizontalScrollView
    private lateinit var moreButton: Button
    private lateinit var homeButton: Button
    private lateinit var recommendButton: Button
    private val navButtons = mutableMapOf<String, Button>()
    private val fixedButtons = mutableMapOf<String, Button>()
    private var historyPage: LibraryPage? = null
    private var lastHistorySync = 0L
    private var displayedAccount = ""
    private val pages = mutableMapOf<String, BrowsePage>()
    private var settings = AppSettings()
    private var currentKey = "home"
    private var searchToken = 0
    private var searchHotToken = 0
    private var accountFocusTag: String? = null
    private var historyReturnKey = "home"
    private var historyReturnFocusTag: String? = null
    private var globalPageEntry: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.keepLandscapeOnTelevision(this)
        api = BiliApiClient(this)
        settings = AppSettings.load(this)
        globalPageEntry = intent.getStringExtra(EXTRA_OPEN_PAGE)
        historyReturnKey = savedInstanceState?.getString("history_return_key", "home") ?: "home"
        historyReturnFocusTag = savedInstanceState?.getString("history_return_focus")
        setContentView(buildRoot())
        when (val saved = savedInstanceState?.getString("channel") ?: intent.getStringExtra(EXTRA_OPEN_PAGE)) {
            "history" -> showHistory(); "me" -> showMe(); "search" -> showSearch(); else -> selectChannel(saved ?: "home")
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentKey == "history" && globalPageEntry == "history" && historyReturnKey == "home") finish()
                else if (currentKey == "history") returnFromHistory()
                else if (globalPageEntry != null) finish()
                else if (currentKey != "home") selectChannel("home")
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("channel", currentKey)
        outState.putString("history_return_key", historyReturnKey)
        outState.putString("history_return_focus", historyReturnFocusTag)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (currentKey == "me") accountFocusTag = currentFocus?.tag?.toString()?.takeIf { it.startsWith("account/") }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        run {
            val updated = AppSettings.load(this)
            if (updated != settings) {
                settings = updated
                pages.values.forEach { it.dispose() }
                pages.clear()
                buildTabs()
                when (currentKey) { "search" -> showSearch(); "me" -> showMe(); "history" -> { historyPage?.dispose(); historyPage = null; showHistory() }; else -> selectChannel(currentKey) }
            }
        }
        ProgressRepository.get(this).refreshAccount { changed ->
            if (isDestroyed) return@refreshAccount
            val repository = ProgressRepository.get(this)
            if (changed || displayedAccount != repository.account) {
                displayedAccount = repository.account
                historyPage?.dispose(); historyPage = null; lastHistorySync = 0
                if (currentKey == "history") showHistory()
                if (currentKey == "me") showMe()
            }
            historyPage?.refreshLocal()
            VideoCard.refreshVisible(content)
            if (repository.account != "guest" && System.currentTimeMillis() - lastHistorySync > 60_000) {
                lastHistorySync = System.currentTimeMillis()
                val owner = repository.account
                AppExecutors.io.execute {
                    val result = runCatching { api.getHistoryPage(expectedAccount = owner) }
                    runOnUiThread { if (!isDestroyed && owner == repository.account) result.onSuccess { repository.merge(it.entries, owner); VideoCard.refreshVisible(content) } }
                }
            }
        }
    }

    override fun onDestroy() { historyPage?.dispose(); pages.values.forEach { it.dispose() }; super.onDestroy() }

    private fun buildRoot(): View {
        val compact = Ui.isCompactWidth(this)
        val root = FrameLayout(this).apply {
            background = Ui.pageBackground(context)
            val horizontalPadding = if (compact) Ui.dp(context, 8) else Ui.pageInset(context)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = Ui.dp(this@MainActivity, if (compact) 124 else 88)
        })
        val toolbar = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.floatingPanel(context)
            elevation = Ui.dp(context, 12).toFloat()
            val horizontalPadding = Ui.dp(context, if (compact) 6 else 10)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.bilitv_logo)
            contentDescription = getString(R.string.app_name)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        homeButton = tab("专区：视频 ▾") { showZoneChooser() }
        channelTabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        recommendButton = tab("推荐") { selectChannel("home") }
        channelScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(Ui.dp(context, 24))
            addView(channelTabs)
        }
        moreButton = tab("更多 ▾") { ChannelPickerFragment.newInstance(currentKey).show(supportFragmentManager, "channels") }
        val fixedButtonsInOrder = mutableListOf<Button>()
        listOf("search" to "搜索", "me" to "我的", "history" to "历史").forEach { (key, label) ->
            val button = tab(label) { when (key) { "search" -> showSearch(); "me" -> showMe(); else -> showHistory() } }
            fixedButtons[key] = button
            fixedButtonsInOrder += button
        }
        if (compact) {
            val fixedRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            fixedRow.addView(logo, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 42)))
            Ui.compactNavigationButton(homeButton)
            fixedRow.addView(homeButton, LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 48)).apply {
                marginStart = Ui.dp(this@MainActivity, 4)
            })
            fixedButtonsInOrder.forEach { button ->
                Ui.compactNavigationButton(button)
                fixedRow.addView(button, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply {
                    marginStart = Ui.dp(this@MainActivity, 2)
                })
            }
            toolbar.addView(fixedRow, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)))
            val channelsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            channelsRow.addView(channelScroll, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
            channelsRow.addView(moreButton)
            toolbar.addView(channelsRow, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)))
            root.addView(toolbar, FrameLayout.LayoutParams(-1, Ui.dp(this, 100)).apply { topMargin = Ui.dp(this@MainActivity, 14) })
        } else {
            toolbar.addView(logo, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 58)))
            homeButton.text = "专区：视频 ▾"
            toolbar.addView(homeButton)
            toolbar.addView(channelScroll, LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1f))
            toolbar.addView(moreButton)
            toolbar.addView(View(this).apply { setBackgroundColor(Ui.divider) }, LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 24)))
            fixedButtonsInOrder.forEach { toolbar.addView(it) }
            root.addView(toolbar, FrameLayout.LayoutParams(-1, Ui.dp(this, 62)).apply { topMargin = Ui.dp(this@MainActivity, 14) })
        }
        buildTabs()
        return root
    }

    private fun tab(label: String, action: () -> Unit): Button = Ui.navigationButton(this, label, action).apply {
        Ui.focusHighlight(this)
        background = Ui.roundedBackground(android.graphics.Color.TRANSPARENT, Ui.dp(context, 12))
        layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(this@MainActivity, 42)).apply { marginStart = Ui.dp(this@MainActivity, 6) }
        setOnFocusChangeListener { view, focused ->
            Ui.applyFocusHighlight(view, focused)
            if (focused && parent === channelTabs) channelScroll.post { channelScroll.smoothScrollTo((left - Ui.dp(context, 12)).coerceAtLeast(0), 0) }
        }
    }

    private fun showZoneChooser() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("切换内容专区")
            .setItems(arrayOf("视频专区", "番剧专区", "直播专区")) { _, which ->
                when (which) {
                    0 -> selectChannel("home")
                    1 -> startActivity(Intent(this, BangumiActivity::class.java))
                    else -> startActivity(Intent(this, LiveActivity::class.java))
                }
            }
            .show()
    }

    private fun buildTabs() {
        channelTabs.removeAllViews()
        navButtons.clear()
        fun add(key: String, label: String) {
            val button = tab(label) { selectChannel(key) }
            navButtons[key] = button
            channelTabs.addView(button)
        }
        navButtons["home"] = recommendButton
        channelTabs.addView(recommendButton)
        ChannelCatalog.normalize(settings.channelOrder, ChannelCatalog.keys).filterNot { it in settings.hiddenTopChannels }
            .forEach { key -> ChannelCatalog.find(key)?.let { add(key, it.title) } }
        updateTabs()
    }

    private fun updateTabs() {
        (navButtons + fixedButtons).forEach { (key, button) ->
            val selected = key == currentKey
            button.setTextColor(if (selected) android.graphics.Color.WHITE else Ui.muted)
            button.background = Ui.roundedBackground(if (selected) 0x27FFFFFF else android.graphics.Color.TRANSPARENT, Ui.dp(this, 12))
        }
        val other = ChannelCatalog.find(currentKey)?.takeIf { currentKey !in navButtons }
        moreButton.text = other?.let { "${it.title} ▾" } ?: "更多 ▾"
        moreButton.background = Ui.roundedBackground(if (other != null) 0x27FFFFFF else android.graphics.Color.TRANSPARENT, Ui.dp(this, 12))
    }

    fun selectChannel(key: String) {
        val safeKey = key.takeIf { it == "home" || ChannelCatalog.find(it) != null } ?: "home"
        searchToken++
        pages.values.forEach { it.deactivate() }
        currentKey = safeKey
        val page = pages.getOrPut(safeKey) {
            BrowsePage(this, api, ChannelCatalog.find(safeKey), settings,
                openSettings = { openSettings(if (safeKey == "home") "home" else safeKey) },
                changeStyle = {
                    settings = settings.copy(homeStyle = if (settings.homeStyle == AppSettings.HomeStyle.RAILS) AppSettings.HomeStyle.GRID else AppSettings.HomeStyle.RAILS)
                    settings.save(this)
                    pages.remove("home")?.dispose()
                    selectChannel("home")
                },
                leaveToNavigation = { (navButtons[currentKey] ?: moreButton).requestFocus() })
        }
        content.removeAllViews()
        content.addView(page)
        updateTabs()
        page.activate()
    }

    private fun showHistory(originKey: String? = null, originFocusTag: String? = null) {
        if (currentKey != "history") {
            historyReturnKey = (originKey ?: currentKey).takeIf { it != "history" && (it == "home" || it == "me" || it == "search" || ChannelCatalog.find(it) != null) } ?: "home"
            historyReturnFocusTag = originFocusTag ?: content.findFocus()?.tag?.toString()
        }
        searchToken++
        pages.values.forEach { it.deactivate() }
        currentKey = "history"; updateTabs()
        if (historyPage?.accountKey != null && historyPage?.accountKey != ProgressRepository.get(this).account) {
            historyPage?.dispose(); historyPage = null
        }
        val page = historyPage ?: LibraryPage(this, api, navigateUp = { returnFromHistory() }).also { historyPage = it }
        content.removeAllViews(); content.addView(page)
        page.refreshLocal()
    }

    private fun returnFromHistory() {
        val key = historyReturnKey
        val focusTag = historyReturnFocusTag
        when (key) {
            "me" -> showMe(focusTag)
            "search" -> showSearch()
            else -> selectChannel(key)
        }
        if (key == "home" && focusTag == null) homeButton.post { homeButton.requestFocus() }
    }

    fun closeChannelPicker() { moreButton.requestFocus() }
    fun openSettings(section: String = "channels") {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", section))
    }

    private fun showSearch() {
        pages.values.forEach { it.deactivate() }
        currentKey = "search"
        val pageToken = ++searchHotToken
        searchToken++
        updateTabs()
        val historyStore = SearchHistoryStore(this)
        val hotButtons = mutableListOf<Button>()
        val historyButtons = mutableListOf<Button>()
        val historyTermButtons = mutableListOf<Button>()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8), 0)
        }
        page.addView(Ui.text(this, "搜索视频", 25f, Ui.text).apply {
            setPadding(Ui.dp(context, 4), 0, 0, 0)
        }, LinearLayout.LayoutParams(-1, Ui.dp(this, 46)).apply {
            bottomMargin = Ui.dp(this@MainActivity, 10)
        })
        val input = Ui.searchInput(this, "输入关键词或 BV 号").apply { tag = "video-search-input" }
        val submit = Ui.button(this, "搜索") {}.apply { tag = "video-search-submit" }
        val row = Ui.searchBox(this, input, submit)
        Ui.linkSearchFocus(input, submit)
        page.addView(row, LinearLayout.LayoutParams(-1, if (Ui.isTelevision(this)) Ui.dp(this, 58) else Ui.dp(this, 64)))

        fun suggestionRow(): Pair<HorizontalScrollView, LinearLayout> {
            val chips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val scroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                clipChildren = false
                addView(chips, LinearLayout.LayoutParams(-2, -1))
            }
            return scroll to chips
        }
        val (hotScroll, hotRow) = suggestionRow()
        val (historyScroll, historyRow) = suggestionRow()
        val hotLabel = Ui.text(this, "B 站热搜", 14f, Ui.muted)
        val historyLabel = Ui.text(this, "搜索历史", 14f, Ui.muted)
        page.addView(hotLabel, LinearLayout.LayoutParams(-1, Ui.dp(this, 28)).apply {
            topMargin = Ui.dp(this@MainActivity, 7)
            marginStart = Ui.dp(this@MainActivity, 3)
        })
        page.addView(hotScroll, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)))
        page.addView(historyLabel, LinearLayout.LayoutParams(-1, Ui.dp(this, 28)).apply {
            topMargin = Ui.dp(this@MainActivity, 3)
            marginStart = Ui.dp(this@MainActivity, 3)
        })
        page.addView(historyScroll, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)))

        val status = Ui.text(this, "选择热搜或历史词，也可以输入关键词搜索", 14f, Ui.muted)
        status.tag = "video-search-status"
        val results = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = GridLayoutManager(this@MainActivity, settings.gridColumns)
            itemAnimator = null
            clipToPadding = false
            setPadding(Ui.dp(context, 2), Ui.dp(context, 4), Ui.dp(context, 2), 0)
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        fun updateFocusLinks() {
            SearchFocusPolicy.connect(
                input = input,
                submit = submit,
                hotWords = hotButtons,
                history = historyButtons,
                results = results,
                topSearchTab = fixedButtons["search"],
            )
        }
        fun addSuggestion(rowHost: LinearLayout, term: String, tag: String, onClick: () -> Unit): Button =
            Ui.chip(this, term, false, onClick).apply {
                id = View.generateViewId()
                this.tag = tag
                maxWidth = Ui.dp(this@MainActivity, 260)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                rowHost.addView(this, LinearLayout.LayoutParams(-2, Ui.dp(this@MainActivity, 40)).apply {
                    marginEnd = Ui.dp(this@MainActivity, 6)
                })
            }
        lateinit var executeSearch: (String) -> Unit
        lateinit var renderHistory: () -> Unit
        fun renderHotWords(words: List<String>, unavailable: Boolean = false, loading: Boolean = false) {
            hotButtons.clear()
            hotRow.removeAllViews()
            if (words.isEmpty()) {
                val message = when {
                    loading -> "正在加载热搜…"
                    unavailable -> "热搜暂不可用，可使用搜索历史"
                    else -> "当前暂无热搜"
                }
                hotRow.addView(Ui.text(this, message, 13f, Ui.muted),
                    LinearLayout.LayoutParams(-2, -1))
            } else {
                words.take(10).forEachIndexed { index, term ->
                    hotButtons += addSuggestion(hotRow, term, "video-search-hot-$index") { executeSearch(term) }
                }
            }
            updateFocusLinks()
        }
        renderHistory = {
            historyButtons.clear()
            historyTermButtons.clear()
            historyRow.removeAllViews()
            val terms = historyStore.read()
            if (terms.isEmpty()) {
                historyRow.addView(Ui.text(this, "暂无搜索历史", 13f, Ui.muted), LinearLayout.LayoutParams(-2, -1))
            } else {
                terms.forEachIndexed { index, term ->
                    val button = addSuggestion(historyRow, term, "video-search-history-$index") { executeSearch(term) }
                    historyButtons += button
                    historyTermButtons += button
                }
                historyButtons += addSuggestion(historyRow, "清空", "video-search-history-clear") {
                    historyStore.clear()
                    renderHistory()
                    input.requestFocus()
                }
            }
            updateFocusLinks()
        }
        executeSearch = fun(value: String) {
            val keyword = SearchHistoryPolicy.normalize(value)
            if (keyword.isEmpty()) { Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show(); return }
            input.setText(keyword)
            input.setSelection(keyword.length)
            historyStore.record(keyword)
            renderHistory()
            if (keyword.startsWith("BV", true)) {
                status.text = "正在打开视频详情…"
                startActivity(Intent(this, VideoDetailActivity::class.java).putExtra(VideoDetailActivity.EXTRA_BVID, keyword)); return
            }
            val token = ++searchToken
            status.text = "正在搜索…"
            AppExecutors.io.execute {
                val result = runCatching { api.searchVideos(keyword) }
                runOnUiThread {
                    if (token != searchToken || currentKey != "search" || isDestroyed) return@runOnUiThread
                    result.onSuccess { videos ->
                        val returnFocusId = historyTermButtons.lastOrNull()?.id ?: hotButtons.lastOrNull()?.id ?: input.id
                        results.adapter = SearchCards(videos, returnFocusId)
                        status.text = if (videos.isEmpty()) "没有找到相关视频" else "搜索结果 ${videos.size} 条"
                    }
                        .onFailure { status.text = "搜索失败，请重试" }
                }
            }
        }
        submit.setOnClickListener { executeSearch(input.text.toString()) }
        input.setOnEditorActionListener { _, _, _ -> executeSearch(input.text.toString()); true }
        page.addView(status, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply {
            topMargin = Ui.dp(this@MainActivity, 8)
            bottomMargin = Ui.dp(this@MainActivity, 2)
        })
        page.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        content.removeAllViews(); content.addView(page)
        renderHistory()
        renderHotWords(emptyList(), loading = true)
        input.requestFocus()
        AppExecutors.io.execute {
            val result = runCatching { api.getHotSearches(10) }
            runOnUiThread {
                if (pageToken != searchHotToken || currentKey != "search" || isDestroyed) return@runOnUiThread
                renderHotWords(result.getOrDefault(emptyList()), unavailable = result.isFailure)
            }
        }
    }

    private fun showMe(restoreTagOverride: String? = null) {
        val restoreTag = restoreTagOverride ?: if (currentKey == "me") content.findFocus()?.tag?.toString()?.takeIf { it.startsWith("account/") } ?: accountFocusTag else null
        searchToken++
        pages.values.forEach { it.deactivate() }
        currentKey = "me"; updateTabs()
        val page = AccountPage(this, openHistory = { showHistory("me", "account/history") },
            openFavorites = { startActivity(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, LibraryActivity.MODE_FAVORITES)) },
            openWatchLater = { startActivity(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, LibraryActivity.MODE_WATCH_LATER)) },
            openFollowing = { startActivity(Intent(this, FollowingActivity::class.java)) },
            openSettings = { openSettings("appearance") })
        content.removeAllViews()
        content.addView(page)
        page.post {
            val target = restoreTag?.let { tag -> page.findViewWithTag<View>(tag) }
                ?: page.findViewWithTag<View>("account/login")
            target?.requestFocus()
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)
    private class SearchCards(private val items: List<VideoSummary>, private val focusReturnId: Int) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder = Holder(VideoCard(parent.context, grid = true).apply {
            id = View.generateViewId()
            nextFocusUpId = focusReturnId
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(6, 6, 6, 6) }
        })
        override fun onBindViewHolder(holder: Holder, position: Int) { (holder.itemView as VideoCard).bind(items[position]) }
    }

    companion object {
        const val EXTRA_OPEN_PAGE = "open_global_page"
    }
}
