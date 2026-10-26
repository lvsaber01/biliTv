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

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var content: FrameLayout
    private lateinit var channelTabs: LinearLayout
    private lateinit var channelScroll: HorizontalScrollView
    private lateinit var moreButton: Button
    private lateinit var homeButton: Button
    private val navButtons = mutableMapOf<String, Button>()
    private val fixedButtons = mutableMapOf<String, Button>()
    private var historyPage: LibraryPage? = null
    private var lastHistorySync = 0L
    private var displayedAccount = ""
    private val pages = mutableMapOf<String, BrowsePage>()
    private var settings = AppSettings()
    private var currentKey = "home"
    private var searchToken = 0
    private var accountFocusTag: String? = null
    private var historyReturnKey = "home"
    private var historyReturnFocusTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        api = BiliApiClient(this)
        settings = AppSettings.load(this)
        historyReturnKey = savedInstanceState?.getString("history_return_key", "home") ?: "home"
        historyReturnFocusTag = savedInstanceState?.getString("history_return_focus")
        setContentView(buildRoot())
        when (val saved = savedInstanceState?.getString("channel")) {
            "history" -> showHistory(); "me" -> showMe(); "search" -> showSearch(); else -> selectChannel(saved ?: "home")
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentKey == "history") returnFromHistory()
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
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0)
        }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1).apply { topMargin = Ui.dp(this@MainActivity, 88) })
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundedBackground(0xED151A21.toInt(), Ui.dp(context, 18))
            elevation = Ui.dp(context, 12).toFloat()
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
        }
        toolbar.addView(ImageView(this).apply {
            setImageResource(R.drawable.bilitv_logo)
            contentDescription = getString(R.string.app_name)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 58)))
        homeButton = tab("首页") { selectChannel("home") }
        toolbar.addView(homeButton)
        channelTabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        channelScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(Ui.dp(context, 24))
            addView(channelTabs)
        }
        toolbar.addView(channelScroll, LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1f))
        moreButton = tab("更多 ▾") { ChannelPickerFragment.newInstance(currentKey).show(supportFragmentManager, "channels") }
        toolbar.addView(moreButton)
        toolbar.addView(View(this).apply { setBackgroundColor(Ui.divider) }, LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 24)))
        listOf("search" to "搜索", "me" to "我的", "history" to "历史").forEach { (key, label) ->
            val button = tab(label) { when (key) { "search" -> showSearch(); "me" -> showMe(); else -> showHistory() } }
            fixedButtons[key] = button; toolbar.addView(button)
        }
        root.addView(toolbar, FrameLayout.LayoutParams(-1, Ui.dp(this, 62)).apply { topMargin = Ui.dp(this@MainActivity, 14) })
        buildTabs()
        return root
    }

    private fun tab(label: String, action: () -> Unit): Button = Ui.chip(this, label, false, action).apply {
        Ui.focusHighlight(this)
        background = Ui.roundedBackground(android.graphics.Color.TRANSPARENT, Ui.dp(context, 12))
        layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(this@MainActivity, 42)).apply { marginStart = Ui.dp(this@MainActivity, 6) }
        setOnFocusChangeListener { view, focused ->
            Ui.applyFocusHighlight(view, focused)
            if (focused && parent === channelTabs) channelScroll.post { channelScroll.smoothScrollTo((left - Ui.dp(context, 12)).coerceAtLeast(0), 0) }
        }
    }

    private fun buildTabs() {
        channelTabs.removeAllViews()
        navButtons.clear()
        fun add(key: String, label: String) {
            val button = tab(label) { selectChannel(key) }
            navButtons[key] = button
            channelTabs.addView(button)
        }
        navButtons["home"] = homeButton
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
        updateTabs()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(context, 16), 0, 0) }
        val row = LinearLayout(this)
        val input = EditText(this).apply {
            hint = "关键词 / BV 号"
            setSingleLine(true)
            setTextColor(Ui.text); setHintTextColor(Ui.muted)
            background = Ui.panel(context)
        }
        row.addView(input, LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f))
        val status = Ui.text(this, "输入关键词发现视频，或输入 BV 号直接打开", 14f, Ui.muted)
        val results = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@MainActivity, settings.gridColumns) }
        fun search() {
            val keyword = input.text.toString().trim()
            if (keyword.isEmpty()) { Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show(); return }
            if (keyword.startsWith("BV", true)) {
                startActivity(Intent(this, VideoDetailActivity::class.java).putExtra(VideoDetailActivity.EXTRA_BVID, keyword)); return
            }
            val token = ++searchToken
            status.text = "正在搜索…"
            AppExecutors.io.execute {
                val result = runCatching { api.searchVideos(keyword) }
                runOnUiThread {
                    if (token != searchToken || currentKey != "search" || isDestroyed) return@runOnUiThread
                    result.onSuccess { videos -> results.adapter = SearchCards(videos); status.text = "搜索结果 ${videos.size} 条" }
                        .onFailure { status.text = "搜索失败，请重试" }
                }
            }
        }
        row.addView(tab("搜索") { search() })
        input.setOnEditorActionListener { _, _, _ -> search(); true }
        page.addView(row)
        page.addView(status)
        page.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        content.removeAllViews(); content.addView(page)
        input.requestFocus()
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
        restoreTag?.let { tag -> page.post { page.findViewWithTag<View>(tag)?.requestFocus() } }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)
    private class SearchCards(private val items: List<VideoSummary>) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder = Holder(VideoCard(parent.context, grid = true).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(6, 6, 6, 6) }
        })
        override fun onBindViewHolder(holder: Holder, position: Int) { (holder.itemView as VideoCard).bind(items[position]) }
    }
}
