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
import java.util.Calendar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.core.widget.NestedScrollView
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lvsaber.bilitv.R
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.BiliApiException
import com.lvsaber.bilitv.data.PgcCategory
import com.lvsaber.bilitv.data.PgcEpisode
import com.lvsaber.bilitv.data.PgcIndexPage
import com.lvsaber.bilitv.data.PgcJsonParser
import com.lvsaber.bilitv.data.PgcProgress
import com.lvsaber.bilitv.data.PgcSeason
import com.lvsaber.bilitv.data.PgcTimelineDay
import com.lvsaber.bilitv.data.PgcTimelineItem
import com.lvsaber.bilitv.data.ProgressRepository

@UnstableApi
class BangumiActivity : AppCompatActivity() {
    private data class PagePosition(val scrollY: Int = 0, val layoutState: Parcelable? = null, val focusTag: String? = null)
    private lateinit var api: BiliApiClient
    private lateinit var content: FrameLayout
    private lateinit var navStrip: LinearLayout
    private lateinit var navScroll: HorizontalScrollView
    private val navButtons = mutableMapOf<String, Button>()
    private val fixedButtons = mutableMapOf<String, Button>()
    private val pagePositions = mutableMapOf<String, PagePosition>()
    private val repository by lazy { ProgressRepository.get(this) }
    private var currentPage = "recommend"
    private var searchReturnPage = "recommend"
    private var category = PgcCategory.JAPANESE_ANIME
    private var rankingDay = 3
    private var scheduleBefore = 1
    private var scheduleAfter = 7
    private var indexYear: Int? = null
    private var indexFinished: Boolean? = null
    private var indexOrder = 4
    private var indexPage = 1
    private var indexHasMore = false
    private var followingPage = 0
    private var followingHasMore = true
    private var searchPage = 0
    private var searchHasMore = false
    private var restoreIndexThroughPage = 0
    private var restoreFollowingThroughPage = 0
    private var restoreSearchThroughPage = 0
    private val searchItems = mutableListOf<PgcSeason>()
    private var pageGeneration = 0
    private var currentAdapter: PgcSeasonAdapter? = null
    private var currentStatus: TextView? = null
    private var currentMore: Button? = null
    private var pendingSearch = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        api = BiliApiClient(this)
        currentPage = savedInstanceState?.getString(STATE_PAGE) ?: "recommend"
        category = PgcCategory.fromSeasonType(savedInstanceState?.getInt(STATE_CATEGORY, 1) ?: 1) ?: PgcCategory.JAPANESE_ANIME
        rankingDay = savedInstanceState?.getInt(STATE_RANKING_DAY, 3) ?: 3
        scheduleBefore = savedInstanceState?.getInt(STATE_SCHEDULE_BEFORE, 1) ?: 1
        scheduleAfter = savedInstanceState?.getInt(STATE_SCHEDULE_AFTER, 7) ?: 7
        indexYear = savedInstanceState?.getInt(STATE_INDEX_YEAR, Int.MIN_VALUE)?.takeUnless { it == Int.MIN_VALUE }
        indexFinished = savedInstanceState?.getInt(STATE_INDEX_FINISHED, -1)?.let { if (it < 0) null else it == 1 }
        indexOrder = savedInstanceState?.getInt(STATE_INDEX_ORDER, 4) ?: 4
        pendingSearch = savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty()
        restoreIndexThroughPage = savedInstanceState?.getInt(STATE_INDEX_PAGE, 0) ?: 0
        restoreFollowingThroughPage = savedInstanceState?.getInt(STATE_FOLLOWING_PAGE, 0) ?: 0
        restoreSearchThroughPage = savedInstanceState?.getInt(STATE_SEARCH_PAGE, 0) ?: 0
        savedInstanceState?.let { state ->
            pagePositions[currentPage] = PagePosition(
                state.getInt(STATE_SCROLL_Y, 0),
                BundleCompat.getParcelable(state, STATE_LAYOUT_STATE, Parcelable::class.java),
                state.getString(STATE_FOCUS_TAG),
            )
        }
        searchReturnPage = savedInstanceState?.getString(STATE_SEARCH_RETURN) ?: "recommend"
        setContentView(buildRoot())
        showPage(currentPage, reload = savedInstanceState == null)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPage == "search") showPage(searchReturnPage)
                else if (currentPage != "recommend") showPage("recommend")
                else finish()
            }
        })
        repository.refreshAccount { if (!isDestroyed && currentPage == "recommend") showPage("recommend") }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        capturePagePosition()
        outState.putString(STATE_PAGE, currentPage)
        outState.putString(STATE_SEARCH_RETURN, searchReturnPage)
        outState.putInt(STATE_CATEGORY, category.seasonType)
        outState.putInt(STATE_RANKING_DAY, rankingDay)
        outState.putInt(STATE_SCHEDULE_BEFORE, scheduleBefore)
        outState.putInt(STATE_SCHEDULE_AFTER, scheduleAfter)
        outState.putInt(STATE_INDEX_YEAR, indexYear ?: Int.MIN_VALUE)
        outState.putInt(STATE_INDEX_FINISHED, indexFinished?.let { if (it) 1 else 0 } ?: -1)
        outState.putInt(STATE_INDEX_ORDER, indexOrder)
        outState.putString(STATE_SEARCH_QUERY, pendingSearch)
        outState.putInt(STATE_INDEX_PAGE, indexPage)
        outState.putInt(STATE_FOLLOWING_PAGE, followingPage)
        outState.putInt(STATE_SEARCH_PAGE, searchPage)
        pagePositions[currentPage]?.let { position ->
            outState.putInt(STATE_SCROLL_Y, position.scrollY)
            outState.putParcelable(STATE_LAYOUT_STATE, position.layoutState)
            outState.putString(STATE_FOCUS_TAG, position.focusTag)
        }
        super.onSaveInstanceState(outState)
    }

    private fun buildRoot(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0)
        }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1).apply { topMargin = Ui.dp(this@BangumiActivity, 88) })
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
        toolbar.addView(navButton("专区：番剧 ▾") { showZoneChooser() })
        navStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        navScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(Ui.dp(context, 18))
            addView(navStrip)
        }
        toolbar.addView(navScroll, LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1f))
        listOf("search" to "搜索", "me" to "我的", "history" to "历史").forEach { (key, label) ->
            val button = navButton(label) { openGlobalPage(key) }
            fixedButtons[key] = button
            toolbar.addView(button)
        }
        root.addView(toolbar, FrameLayout.LayoutParams(-1, Ui.dp(this, 62)).apply { topMargin = Ui.dp(this@BangumiActivity, 14) })
        buildSubnav()
        return root
    }

    private fun navButton(label: String, action: () -> Unit): Button = Ui.chip(this, label, false, action).apply {
        tag = "nav:$label"
        Ui.focusHighlight(this)
        background = Ui.roundedBackground(android.graphics.Color.TRANSPARENT, Ui.dp(context, 12))
        layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(this@BangumiActivity, 42)).apply { marginStart = Ui.dp(this@BangumiActivity, 5) }
        setOnFocusChangeListener { view, focused ->
            Ui.applyFocusHighlight(view, focused)
            if (focused && parent === navStrip) navScroll.post { navScroll.smoothScrollTo((left - Ui.dp(context, 12)).coerceAtLeast(0), 0) }
        }
    }

    private fun buildSubnav() {
        navStrip.removeAllViews(); navButtons.clear()
        listOf("recommend" to "推荐", "schedule" to "放送", "ranking" to "排行", "index" to "索引", "following" to "我的追番").forEach { (key, label) ->
            val button = navButton(label) { showPage(key) }
            navButtons[key] = button
            navStrip.addView(button)
        }
        updateNavigation()
    }

    private fun updateNavigation() {
        navButtons.forEach { (key, button) ->
            val selected = key == currentPage
            button.setTextColor(if (selected) android.graphics.Color.WHITE else Ui.muted)
            button.background = Ui.roundedBackground(if (selected) 0x27FFFFFF else android.graphics.Color.TRANSPARENT, Ui.dp(this, 12))
        }
        fixedButtons.forEach { (key, button) ->
            button.background = Ui.roundedBackground(if (currentPage == key) 0x27FFFFFF else android.graphics.Color.TRANSPARENT, Ui.dp(this, 12))
        }
    }

    private fun showZoneChooser() {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("切换内容专区")
            .setItems(arrayOf("视频专区", "番剧专区")) { _, which -> if (which == 0) finish() }
            .show()
    }

    private fun openGlobalPage(page: String) {
        if (page == "search") {
            searchReturnPage = currentPage
            showPage("search")
            return
        }
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PAGE, page))
    }

    private fun showPage(key: String, reload: Boolean = true) {
        capturePagePosition()
        currentPage = key
        pageGeneration++
        currentAdapter = null; currentStatus = null; currentMore = null
        updateNavigation()
        content.removeAllViews()
        when (key) {
            "recommend" -> showRecommendation()
            "schedule" -> showSchedule()
            "ranking" -> showRanking()
            "index" -> showIndex(reload)
            "following" -> showFollowing()
            "search" -> showSearch()
            else -> showRecommendation()
        }
        content.getChildAt(0)?.let(::restorePagePosition)
    }

    private fun capturePagePosition() {
        val root = content.getChildAt(0) ?: return
        val nested = findFirst(root, NestedScrollView::class.java)
        val recycler = findFirst(root, RecyclerView::class.java)
        pagePositions[currentPage] = PagePosition(
            scrollY = nested?.scrollY ?: 0,
            layoutState = if (nested == null) recycler?.layoutManager?.onSaveInstanceState() else null,
            focusTag = currentFocus?.takeIf { it.rootView === window.decorView.rootView }?.tag?.toString()?.takeIf(String::isNotBlank),
        )
    }

    private fun restorePagePosition(root: View) {
        val saved = pagePositions[currentPage] ?: return
        val nested = findFirst(root, NestedScrollView::class.java)
        val recycler = findFirst(root, RecyclerView::class.java)
        if (nested != null && saved.scrollY > 0) nested.post { nested.scrollTo(0, saved.scrollY) }
        if (nested == null && saved.layoutState != null) recycler?.layoutManager?.onRestoreInstanceState(saved.layoutState)
        restoreTaggedFocus(root, saved.focusTag)
    }

    private fun <T : View> findFirst(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) findFirst(root.getChildAt(index), type)?.let { return it }
        return null
    }

    private fun restoreTaggedFocus(root: View, tag: String?) {
        if (tag.isNullOrBlank()) return
        val target = findByTag(root, tag) ?: findByTag(window.decorView, tag)
        target?.post { if (!isFinishing && !isDestroyed) target.requestFocus() }
    }

    private fun restoreCurrentPageFocus() {
        val tag = pagePositions[currentPage]?.focusTag ?: return
        val focused = currentFocus
        if (focused != null && !isWithin(focused, content)) return
        findByTag(content, tag)?.requestFocus()
    }

    private fun isWithin(view: View, parent: ViewGroup): Boolean {
        var node: View? = view
        while (node != null) {
            if (node === parent) return true
            node = node.parent as? View
        }
        return false
    }

    private fun findByTag(root: View, tag: String): View? {
        if (root.tag?.toString() == tag) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) findByTag(root.getChildAt(index), tag)?.let { return it }
        return null
    }

    private fun showRecommendation() {
        val scroll = NestedScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 26))
        }
        val hero = buildFeaturedHero()
        body.addView(hero.root, LinearLayout.LayoutParams(-1, Ui.dp(this, 236)))
        body.addView(Ui.text(this, "番剧 · 日本动画与国创", 25f, Ui.text).apply { setPadding(Ui.dp(context, 8), 0, 0, Ui.dp(context, 3)) })
        body.addView(Ui.text(this, "作品、季与集独立呈现；会员、地区和上线限制以 B 站实际权限为准。", 14f, Ui.muted))
        val recent = repository.recentPgc().take(12).map { it.playable.toSeasonCard() }
        if (recent.isNotEmpty()) addRail(body, "继续观看", recent, repository.recentPgc())
        val jp = addLoadingRail(body, "日本动画 · 近期排行")
        val gc = addLoadingRail(body, "国创动画 · 近期排行")
        val airing = addLoadingRail(body, "最近放送")
        scroll.addView(body); content.addView(scroll)
        val token = pageGeneration
        loadHomeRail(token, jp, onLoaded = { items -> items.firstOrNull()?.let { bindFeaturedHero(hero, it) } }) {
            api.getPgcRanking(PgcCategory.JAPANESE_ANIME).take(12)
        }
        loadHomeRail(token, gc) { api.getPgcRanking(PgcCategory.GUOCHUANG).take(12) }
        loadHomeTimeline(token, airing)
    }

    private data class HomeRail(val row: LinearLayout, val status: TextView, val adapter: PgcSeasonAdapter)
    private data class FeaturedHero(val root: LinearLayout, val cover: ImageView, val title: TextView, val meta: TextView,
        val description: TextView, val action: Button)

    private fun buildFeaturedHero(): FeaturedHero {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 18), Ui.dp(context, 12))
            visibility = View.GONE
        }
        val cover = Ui.poster(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(cover, LinearLayout.LayoutParams(Ui.dp(this, 300), -1).apply { marginEnd = Ui.dp(this@BangumiActivity, 18) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val kicker = Ui.text(this, "日本动画 · 近期人气", 13f, Ui.blue)
        val title = Ui.text(this, "", 25f, Ui.text).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        val meta = Ui.text(this, "", 14f, Ui.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val description = Ui.text(this, "", 14f, Ui.text).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        val action = Ui.button(this, "查看详情") {}.apply { isEnabled = false; minWidth = Ui.dp(context, 150) }
        info.addView(kicker)
        info.addView(title, LinearLayout.LayoutParams(-1, Ui.dp(this, 38)))
        info.addView(meta, LinearLayout.LayoutParams(-1, Ui.dp(this, 26)))
        info.addView(description, LinearLayout.LayoutParams(-1, 0, 1f))
        info.addView(action, LinearLayout.LayoutParams(-2, Ui.dp(this, 42)))
        root.addView(info, LinearLayout.LayoutParams(0, -1, 1f))
        return FeaturedHero(root, cover, title, meta, description, action)
    }

    private fun bindFeaturedHero(hero: FeaturedHero, season: PgcSeason) {
        CoverLoader.load(hero.cover, season.coverUrl)
        hero.title.text = season.title
        hero.meta.text = listOf(season.score.takeIf(String::isNotBlank)?.let { "评分 $it" }, season.indexShow,
            if (season.isFinished) "已完结" else "连载中").filterNotNull().filter(String::isNotBlank).joinToString(" · ")
        hero.description.text = season.description.ifBlank { season.badge.takeIf(String::isNotBlank) ?: "根据近期日本动画排行榜生成的非个性化精选。" }
        hero.action.setOnClickListener { openDetail(season.seasonId) }
        hero.action.isEnabled = true
        hero.root.visibility = View.VISIBLE
    }

    private fun addLoadingRail(body: LinearLayout, title: String): HomeRail {
        body.addView(Ui.text(this, title, 19f, Ui.text).apply { setPadding(Ui.dp(context, 8), Ui.dp(context, 10), 0, Ui.dp(context, 5)) })
        val status = Ui.text(this, "正在加载…", 13f, Ui.muted)
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BangumiActivity, RecyclerView.HORIZONTAL, false)
            itemAnimator = null
        }
        val adapter = PgcSeasonAdapter({ openDetail(it.seasonId) }, itemWidthDp = 174)
        rv.adapter = adapter
        row.addView(status); row.addView(rv, LinearLayout.LayoutParams(-1, Ui.dp(this, 294)))
        body.addView(row)
        return HomeRail(row, status, adapter)
    }

    private fun addRail(body: LinearLayout, title: String, seasons: List<PgcSeason>, progress: List<PgcProgress> = emptyList()) {
        if (seasons.isEmpty()) return
        body.addView(Ui.text(this, title, 19f, Ui.text).apply { setPadding(Ui.dp(context, 8), Ui.dp(context, 10), 0, Ui.dp(context, 5)) })
        val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@BangumiActivity, RecyclerView.HORIZONTAL, false); itemAnimator = null }
        val adapter = PgcSeasonAdapter({ openDetail(it.seasonId) }, itemWidthDp = 174)
        adapter.setProgress(progress); adapter.submitList(seasons)
        rv.adapter = adapter
        body.addView(rv, LinearLayout.LayoutParams(-1, Ui.dp(this, 294)))
    }

    private fun loadHomeRail(token: Int, rail: HomeRail, onLoaded: (List<PgcSeason>) -> Unit = {}, request: () -> List<PgcSeason>) {
        AppExecutors.io.execute {
            val result = runCatching(request)
            runOnUiThread {
                if (token != pageGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { items ->
                    onLoaded(items)
                    rail.adapter.submitList(items) { restoreCurrentPageFocus() }
                    rail.status.text = if (items.isEmpty()) "暂无内容" else ""
                    rail.status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }.onFailure { error -> rail.status.text = "加载失败：${friendlyMessage(error)}" }
            }
        }
    }

    private fun loadHomeTimeline(token: Int, rail: HomeRail) {
        AppExecutors.io.execute {
            val result = runCatching {
                val jp = api.getPgcTimeline(PgcCategory.JAPANESE_ANIME).flatMap { it.items }
                val gc = api.getPgcTimeline(PgcCategory.GUOCHUANG).flatMap { it.items }
                (jp + gc).distinctBy { it.season.seasonId }.take(12).map { item ->
                    item.season.copy(indexShow = item.episode.displayTitle, newestEpisode = item.airTime)
                }
            }
            runOnUiThread {
                if (token != pageGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { items -> rail.adapter.submitList(items) { restoreCurrentPageFocus() }; rail.status.text = if (items.isEmpty()) "暂无近期放送" else ""; rail.status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE }
                    .onFailure { rail.status.text = "放送表暂不可用" }
            }
        }
    }

    private fun showSchedule() {
        val root = listPage("放送时间表") { showPage("schedule") }
        val filters = categoryFilters { showPage("schedule") }
        root.addView(filters, 1)
        val range = horizontalRow().apply {
            addView(Ui.chip(this@BangumiActivity, "仅今天", scheduleBefore == 0 && scheduleAfter == 0) {
                scheduleBefore = 0; scheduleAfter = 0; showPage("schedule")
            })
            addView(Ui.chip(this@BangumiActivity, "未来 7 天", scheduleBefore == 0 && scheduleAfter == 7) {
                scheduleBefore = 0; scheduleAfter = 7; showPage("schedule")
            })
            addView(Ui.chip(this@BangumiActivity, "前后 7 天", scheduleBefore == 7 && scheduleAfter == 7) {
                scheduleBefore = 7; scheduleAfter = 7; showPage("schedule")
            })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(range) }, 2)
        val status = Ui.text(this, "正在读取放送表…", 14f, Ui.muted)
        currentStatus = status; root.addView(status, 3)
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@BangumiActivity); itemAnimator = null }
        val adapter = PgcTimelineAdapter { season, episode -> openDetail(season.seasonId, episode.episodeId) }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(root)
        val token = pageGeneration
        AppExecutors.io.execute {
            val result = runCatching { api.getPgcTimeline(category, before = scheduleBefore, after = scheduleAfter) }
            runOnUiThread {
                if (!isCurrent(token)) return@runOnUiThread
                result.onSuccess { days ->
                    val rows = buildList {
                        days.forEach { day ->
                            add(PgcTimelineItemRow(dayLabel = dayLabel(day)))
                            day.items.forEach { item -> add(PgcTimelineItemRow(season = item.season, episode = item.episode, time = item.airTime)) }
                        }
                    }
                    adapter.submitList(rows) { restoreCurrentPageFocus() }
                    status.text = if (rows.none { it.season != null }) "近期没有放送条目" else "${category.label} · 按日期分组"
                }.onFailure { status.text = "放送表加载失败：${friendlyMessage(it)}" }
            }
        }
    }

    private fun dayLabel(day: PgcTimelineDay): String {
        val weekdays = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        return listOf(day.date, weekdays.getOrNull(day.dayOfWeek).orEmpty()).filter(String::isNotBlank).joinToString(" · ")
    }

    private fun showRanking() {
        val root = listPage("番剧排行") { showPage("ranking") }
        val filters = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        filters.addView(categoryFilters { showPage("ranking") })
        filters.addView(Ui.chip(this, "3 日榜", rankingDay == 3) { rankingDay = 3; showPage("ranking") })
        filters.addView(Ui.chip(this, "周榜", rankingDay == 7) { rankingDay = 7; showPage("ranking") })
        root.addView(filters, 1)
        val status = Ui.text(this, "正在加载排行…", 14f, Ui.muted); currentStatus = status; root.addView(status, 2)
        val grid = seasonGrid { openDetail(it.seasonId) }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f)); content.addView(root)
        loadSeasons(pageGeneration, status, currentAdapter!!, "排行") { api.getPgcRanking(category, rankingDay) }
    }

    private fun showIndex(reload: Boolean = true) {
        val root = listPage("番剧索引", ::resetIndex)
        val filters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filters.addView(categoryFilters { resetIndex() })
        val row = horizontalRow()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        listOf("全部年份" to null, "$currentYear" to currentYear,
            "${currentYear - 1}" to currentYear - 1,
            "${currentYear - 2}" to currentYear - 2).forEach { (label, year) ->
            row.addView(Ui.chip(this, label, indexYear == year) { indexYear = year; resetIndex() })
        }
        row.addView(Ui.chip(this, "全部状态", indexFinished == null) { indexFinished = null; resetIndex() })
        row.addView(Ui.chip(this, "连载中", indexFinished == false) { indexFinished = false; resetIndex() })
        row.addView(Ui.chip(this, "已完结", indexFinished == true) { indexFinished = true; resetIndex() })
        filters.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        val orderRow = horizontalRow()
        listOf(0 to "更新时间", 2 to "播放量", 3 to "追番数", 4 to "评分", 5 to "开播时间").forEach { (value, label) ->
            orderRow.addView(Ui.chip(this, label, indexOrder == value) { indexOrder = value; resetIndex() })
        }
        filters.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(orderRow) })
        root.addView(filters, 1)
        val status = Ui.text(this, "正在读取索引…", 14f, Ui.muted); currentStatus = status; root.addView(status, 2)
        val grid = seasonGrid { openDetail(it.seasonId) }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val more = Ui.chip(this, "加载更多", false) { loadIndexPage(false) }; currentMore = more
        root.addView(more, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)))
        content.addView(root)
        if (reload) resetIndex() else loadIndexPage(true)
    }

    private fun loadIndexPage(reset: Boolean) {
        val adapter = currentAdapter ?: return
        val status = currentStatus ?: return
        val more = currentMore ?: return
        if (!indexHasMore && !reset) return
        val token = pageGeneration
        val requestedPage = if (reset) 1 else indexPage + 1
        more.isEnabled = false; status.text = "正在加载第 $requestedPage 页…"
        AppExecutors.io.execute {
            val result = runCatching { api.getPgcIndex(category, requestedPage, indexYear, indexFinished, indexOrder) }
            runOnUiThread {
                if (!isCurrent(token)) return@runOnUiThread
                more.isEnabled = true
                result.onSuccess { page ->
                    val items = if (reset) page.items else adapter.currentList + page.items.filterNot { incoming -> adapter.currentList.any { it.seasonId == incoming.seasonId } }
                    adapter.submitList(items) { restoreCurrentPageFocus() }
                    indexPage = requestedPage; indexHasMore = page.hasNext
                    more.visibility = if (indexHasMore) View.VISIBLE else View.GONE
                    status.text = if (items.isEmpty()) "没有符合条件的日本动画/国创结果" else "${category.label} · ${items.size} 部作品${if (indexHasMore) "" else " · 已到末尾"}"
                    if (indexPage < restoreIndexThroughPage && indexHasMore) loadIndexPage(reset = false)
                    else restoreIndexThroughPage = 0
                }.onFailure { status.text = "索引加载失败：${friendlyMessage(it)}"; more.text = "重试"; more.visibility = View.VISIBLE; more.setOnClickListener { loadIndexPage(reset) } }
            }
        }
    }

    private fun showFollowing() {
        val root = listPage("我的追番", ::resetFollowing)
        root.addView(categoryFilters(::resetFollowing), 1)
        val status = Ui.text(this, "正在读取账号追番…", 14f, Ui.muted); currentStatus = status; root.addView(status, 2)
        val grid = seasonGrid { openDetail(it.seasonId) }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f)); content.addView(root)
        val more = Ui.chip(this, "加载更多", false) { loadFollowingPage(reset = false) }; currentMore = more
        root.addView(more, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)))
        resetFollowing(preserveRestore = true)
    }

    private fun showSearch() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(context, 8), 0, 0) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(this, "搜索番剧", 21f, Ui.text), LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
        header.addView(Ui.chip(this, "返回", false) { showPage(searchReturnPage) })
        root.addView(header)
        val filter = categoryFilters { resetSearch(pendingSearch) }
        root.addView(filter)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            tag = "bangumi-search-input"
            hint = "输入番剧名称"
            setSingleLine(true); setTextColor(Ui.text); setHintTextColor(Ui.muted); background = Ui.panel(context)
            setText(pendingSearch)
        }
        row.addView(input, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        row.addView(Ui.button(this, "搜索") { pendingSearch = input.text.toString().trim(); resetSearch(pendingSearch) })
        input.setOnEditorActionListener { _, _, _ -> pendingSearch = input.text.toString().trim(); resetSearch(pendingSearch); true }
        root.addView(row)
        val status = Ui.text(this, "搜索只返回日本动画与国创动画；无法验证分类的结果会被过滤。", 14f, Ui.muted)
        currentStatus = status; root.addView(status)
        val grid = seasonGrid { openDetail(it.seasonId) }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val more = Ui.chip(this, "加载更多", false) { searchPgc(pendingSearch, reset = false) }.apply { visibility = View.GONE }
        currentMore = more
        root.addView(more, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)))
        content.addView(root)
        if (pendingSearch.isNotBlank()) resetSearch(pendingSearch, preserveRestore = true) else input.requestFocus()
    }

    private fun resetSearch(keyword: String, preserveRestore: Boolean = false) {
        pageGeneration++
        if (!preserveRestore) restoreSearchThroughPage = 0
        searchPage = 0; searchHasMore = true; searchItems.clear()
        currentAdapter?.submitList(emptyList())
        searchPgc(keyword, reset = true)
    }

    private fun searchPgc(keyword: String, reset: Boolean) {
        val status = currentStatus ?: return
        val adapter = currentAdapter ?: return
        val more = currentMore ?: return
        if (keyword.isBlank()) { status.text = "请输入番剧名称"; return }
        if (!reset && !searchHasMore) return
        val token = pageGeneration
        val requestedPage = if (reset) 1 else searchPage + 1
        status.text = "正在搜索第 $requestedPage 页…"; more.isEnabled = false
        AppExecutors.io.execute {
            val result = runCatching { api.searchPgc(keyword, category, requestedPage) }
            runOnUiThread {
                if (!isCurrent(token)) return@runOnUiThread
                more.isEnabled = true
                result.onSuccess { page ->
                    val fresh = if (reset) page.items else page.items.filterNot { item -> searchItems.any { it.seasonId == item.seasonId } }
                    if (reset) searchItems.clear()
                    searchItems.addAll(fresh)
                    searchPage = requestedPage; searchHasMore = page.hasNext
                    adapter.submitList(searchItems.toList()) { restoreCurrentPageFocus() }
                    more.visibility = if (searchHasMore) View.VISIBLE else View.GONE
                    more.text = "加载更多"
                    more.setOnClickListener { searchPgc(pendingSearch, reset = false) }
                    status.text = if (searchItems.isEmpty()) "没有找到符合范围的番剧" else "找到 ${searchItems.size} 部作品${if (searchHasMore) " · 还可继续加载" else " · 已到末尾"}"
                    if (searchPage < restoreSearchThroughPage && searchHasMore) searchPgc(keyword, reset = false)
                    else restoreSearchThroughPage = 0
                }.onFailure {
                    more.text = "重试"
                    more.visibility = View.VISIBLE
                    more.setOnClickListener { searchPgc(keyword, reset) }
                    status.text = "搜索失败：${friendlyMessage(it)}"
                }
            }
        }
    }

    private fun resetFollowing(preserveRestore: Boolean = false) {
        pageGeneration++
        if (!preserveRestore) restoreFollowingThroughPage = 0
        followingPage = 0; followingHasMore = true
        currentAdapter?.submitList(emptyList())
        loadFollowingPage(reset = true)
    }

    private fun loadFollowingPage(reset: Boolean) {
        val status = currentStatus ?: return
        val adapter = currentAdapter ?: return
        val more = currentMore ?: return
        if (!reset && !followingHasMore) return
        val token = pageGeneration
        val requestedPage = if (reset) 1 else followingPage + 1
        more.isEnabled = false; status.text = "正在加载追番列表第 $requestedPage 页…"
        AppExecutors.io.execute {
            val result = runCatching { api.getPgcFollowing(category, requestedPage) }
            runOnUiThread {
                if (!isCurrent(token)) return@runOnUiThread
                more.isEnabled = true
                result.onSuccess { page ->
                    val items = if (reset) page.items else adapter.currentList + page.items.filterNot { incoming -> adapter.currentList.any { it.seasonId == incoming.seasonId } }
                    adapter.submitList(items) { restoreCurrentPageFocus() }
                    followingPage = requestedPage; followingHasMore = page.hasNext
                    more.text = "加载更多"; more.visibility = if (followingHasMore) View.VISIBLE else View.GONE
                    more.setOnClickListener { loadFollowingPage(reset = false) }
                    status.text = if (items.isEmpty()) "暂无追番作品" else "已加载 ${items.size} 部 · ${category.label}${if (followingHasMore) "" else " · 已到末尾"}"
                    if (followingPage < restoreFollowingThroughPage && followingHasMore) loadFollowingPage(reset = false)
                    else restoreFollowingThroughPage = 0
                }.onFailure { error ->
                    more.text = "重试"; more.visibility = View.VISIBLE
                    more.setOnClickListener { loadFollowingPage(reset) }
                    status.text = when (error) {
                        is BiliApiException -> if (error.apiCode == -101 || error.apiCode == 53013) "登录或隐私设置限制，无法读取追番列表" else "追番列表加载失败：${friendlyMessage(error)}"
                        else -> "追番列表加载失败：${friendlyMessage(error)}"
                    }
                }
            }
        }
    }

    private fun listPage(title: String, refresh: () -> Unit): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(context, 5), 0, Ui.dp(context, 12)) }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(Ui.text(this, title, 21f, Ui.text), LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f))
        heading.addView(Ui.chip(this, "刷新", false) { refresh() })
        root.addView(heading)
        return root
    }

    private fun seasonGrid(onClick: (PgcSeason) -> Unit): RecyclerView {
        val manager = GridLayoutManager(this, Ui.videoColumns(this))
        return RecyclerView(this).apply {
            layoutManager = manager; itemAnimator = null; clipToPadding = false
            adapter = PgcSeasonAdapter(onClick).also { currentAdapter = it; it.setProgress(repository.recentPgc()) }
        }
    }

    private fun categoryFilters(onChange: () -> Unit): HorizontalScrollView {
        val row = horizontalRow()
        PgcCategory.entries.forEach { value -> row.addView(Ui.chip(this, value.label, category == value) { category = value; onChange() }) }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun horizontalRow() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }

    private fun loadSeasons(token: Int, status: TextView, adapter: PgcSeasonAdapter, label: String, request: () -> List<PgcSeason>) {
        AppExecutors.io.execute {
            val result = runCatching(request)
            runOnUiThread {
                if (!isCurrent(token)) return@runOnUiThread
                result.onSuccess { items -> adapter.submitList(items) { restoreCurrentPageFocus() }; status.text = if (items.isEmpty()) "暂无$label" else "${items.size} 部作品 · ${category.label}" }
                    .onFailure { error ->
                        status.text = when (error) {
                            is BiliApiException -> if (error.apiCode == -101 || error.apiCode == 53013) "登录或隐私设置限制，无法读取追番列表" else "${label}加载失败：${friendlyMessage(error)}"
                            else -> "${label}加载失败：${friendlyMessage(error)}"
                        }
                    }
            }
        }
    }

    private fun openDetail(seasonId: Long, episodeId: Long? = null) {
        startActivity(Intent(this, PgcDetailActivity::class.java).putExtra(PgcDetailActivity.EXTRA_SEASON_ID, seasonId)
            .putExtra(PgcDetailActivity.EXTRA_EPISODE_ID, episodeId ?: 0L))
    }

    private fun isCurrent(token: Int) = token == pageGeneration && !isFinishing && !isDestroyed

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is BiliApiException -> when (error.apiCode) {
            -101 -> "请重新登录"
            -404 -> "内容已不可用"
            -412, -352 -> "B 站暂时限制了请求，请稍后重试"
            else -> error.message
        }
        else -> error.message ?: "网络暂不可用"
    }

    private fun com.lvsaber.bilitv.data.PgcPlayableRef.toSeasonCard(): PgcSeason = PgcSeason(
        seasonId = seasonId, title = seasonTitle, coverUrl = coverUrl, category = category,
        areas = if (category == PgcCategory.JAPANESE_ANIME) listOf("日本") else listOf("国创"),
        newestEpisode = episodeTitle,
    )

    private fun resetIndex() {
        pageGeneration++
        restoreIndexThroughPage = 0
        indexPage = 1; indexHasMore = true
        currentAdapter?.submitList(emptyList())
        loadIndexPage(true)
    }

    companion object {
        private const val STATE_PAGE = "bangumi_page"
        private const val STATE_SEARCH_RETURN = "bangumi_search_return"
        private const val STATE_CATEGORY = "bangumi_category"
        private const val STATE_RANKING_DAY = "bangumi_ranking_day"
        private const val STATE_SCHEDULE_BEFORE = "bangumi_schedule_before"
        private const val STATE_SCHEDULE_AFTER = "bangumi_schedule_after"
        private const val STATE_INDEX_YEAR = "bangumi_index_year"
        private const val STATE_INDEX_FINISHED = "bangumi_index_finished"
        private const val STATE_INDEX_ORDER = "bangumi_index_order"
        private const val STATE_SEARCH_QUERY = "bangumi_search_query"
        private const val STATE_INDEX_PAGE = "bangumi_index_page"
        private const val STATE_FOLLOWING_PAGE = "bangumi_following_page"
        private const val STATE_SEARCH_PAGE = "bangumi_search_page"
        private const val STATE_SCROLL_Y = "bangumi_scroll_y"
        private const val STATE_LAYOUT_STATE = "bangumi_layout_state"
        private const val STATE_FOCUS_TAG = "bangumi_focus_tag"
    }
}
