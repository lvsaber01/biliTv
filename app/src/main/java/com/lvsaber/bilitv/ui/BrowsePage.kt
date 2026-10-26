package com.lvsaber.bilitv.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.util.UnstableApi
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.AppSettings
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.BiliApiException
import com.lvsaber.bilitv.data.BrowseRail
import com.lvsaber.bilitv.data.BrowseSource
import com.lvsaber.bilitv.data.ChannelCatalog
import com.lvsaber.bilitv.data.ChannelDefinition
import com.lvsaber.bilitv.data.RailBuffer
import com.lvsaber.bilitv.data.VideoSummary
import java.util.concurrent.RejectedExecutionException

private data class RailLoadResult(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val exhausted: Boolean,
    val successfulPages: Int,
    val error: Throwable? = null,
)

/** A page keeps its data and scroll state while switching channels. */
@UnstableApi
class BrowsePage(
    context: Context,
    private val api: BiliApiClient,
    val channel: ChannelDefinition?,
    private val settings: AppSettings,
    private val openSettings: () -> Unit,
    private val changeStyle: () -> Unit,
    private val leaveToNavigation: () -> Unit,
) : LinearLayout(context) {
    private inner class Rail(val definition: BrowseRail) {
        var buffer = RailBuffer()
        val items = mutableListOf<VideoSummary>()
        val status = Ui.text(context, "等待加载", 12f, Ui.muted)
        val retry = Ui.chip(context, "重试", false) { fetch(this, demand = true, reset = failedReset) }
        val list = RecyclerView(context)
        var focusIndex = 0
        var started = false
        var loading = false
        var version = 0
        var revealWhenReady = false
        var focusAfterReveal = false
        var focusWhenReady = false
        var prefetchAt = -1
        var lastRefresh = 0L
        var userScrolled = false
        var layoutState: android.os.Parcelable? = null
        var error: String? = null
        var failedReset = false
        var pendingRefresh = false
        var focusAfterRefresh = false
        var refreshScrollY = 0
        val adapter = Cards(this)
    }

    private val scroll = NestedScrollView(context)
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val rails = mutableListOf<Rail>()
    private var focusRail: Rail? = null
    private var active = false
    private var disposed = false
    private var hero: View? = null
    private var heroImage: ImageView? = null
    private var heroVideo: VideoSummary? = null
    private val coverScope = CoverLoader.newScope()
    private val heroHost = FrameLayout(context)
    private var verticalPosition = 0
    private var lastFocusedRail: Rail? = null
    private val grid = channel == null && settings.homeStyle == AppSettings.HomeStyle.GRID
    private val cardWidth get() = Ui.railCardWidth(context, width.takeIf { it > 0 }
        ?: (resources.displayMetrics.widthPixels - Ui.dp(context, 40)))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && w > 0 && !grid) post {
            rails.forEach { rail ->
                rail.list.layoutParams = (rail.list.layoutParams as LayoutParams).apply {
                    height = Ui.cardHeight(context, cardWidth) + Ui.dp(context, 12)
                }
                rail.adapter.notifyDataSetChanged()
            }
        }
    }

    init {
        orientation = VERTICAL
        val definitions = if (grid) listOf(BrowseRail("featured", "热门精选", BrowseSource.POPULAR))
            else channel?.let { ChannelCatalog.rails(it, settings) } ?: ChannelCatalog.homeRails(settings)
        if (channel == null && settings.heroEnabled && !grid && definitions.isNotEmpty()) {
            heroHost.addView(Ui.text(context, "正在准备本期推荐…", 20f))
            body.addView(heroHost, LayoutParams(-1, Ui.dp(context, 40)).apply { bottomMargin = Ui.dp(context, 4) })
        }
        if (definitions.isEmpty()) {
            body.addView(Ui.text(context, "当前没有启用轨道，请到设置中开启。", 18f))
            body.addView(Ui.button(context, "打开轨道设置", openSettings))
        }
        definitions.forEach { addRail(it) }
        if (grid) body.addView(Ui.button(context, "加载更多（12 条）") { rails.firstOrNull()?.let { append(it, focusNew = false) } })
        scroll.isFillViewport = true
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ -> loadNearby() })
        addView(scroll, LayoutParams(-1, 0, 1f))
    }

    fun activate() {
        active = true
        rails.forEach { rail ->
            if (rail.list.adapter == null) {
                rail.list.adapter = rail.adapter
                rail.list.layoutManager?.onRestoreInstanceState(rail.layoutState)
            }
        }
        post {
            heroVideo?.let { video ->
                heroImage?.let { image ->
                    CoverLoader.load(image, video.coverUrl, 1024, coverScope,
                        if (image.isAttachedToWindow) CoverLoader.Priority.VISIBLE else CoverLoader.Priority.NORMAL)
                }
            }
            scroll.scrollTo(0, verticalPosition)
            val remembered = lastFocusedRail
            if (remembered != null && remembered.items.isNotEmpty()) focusCard(remembered, remembered.focusIndex)
            else rails.firstOrNull()?.let { first ->
                focusRail = first
                if (hero != null) hero?.requestFocus()
                else if (first.items.isNotEmpty()) focusCard(first, first.focusIndex)
                else first.focusWhenReady = true
            }
            loadNearby()
        }
    }

    fun deactivate() {
        if (!active) return
        active = false
        CoverLoader.cancel(coverScope)
        verticalPosition = scroll.scrollY
        focusRail = null
        rails.forEach {
            it.focusWhenReady = false; it.focusAfterReveal = false
            it.layoutState = it.list.layoutManager?.onSaveInstanceState()
            it.list.adapter = null
            it.list.recycledViewPool.clear()
        }
    }

    fun dispose() {
        disposed = true
        deactivate()
        CoverLoader.cancel(coverScope)
        rails.forEach { it.version++ }
    }

    private fun addRail(definition: BrowseRail) {
        val rail = Rail(definition)
        rails += rail
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val titleColumn = LinearLayout(context).apply { orientation = VERTICAL }
        titleColumn.addView(Ui.text(context, definition.title, 15f).apply { setPadding(0, 0, 0, 0) })
        if (definition.source == BrowseSource.ALL_RANKING || definition.source == BrowseSource.REGION_RANKING) {
            titleColumn.addView(Ui.text(context, "按质量与近期数据综合，动态更新", 10f, Ui.muted).apply { setPadding(0, 0, 0, 0) })
        }
        header.addView(titleColumn, LayoutParams(0, -2, 1f))
        header.addView(rail.status)
        rail.retry.visibility = GONE
        header.addView(rail.retry)
        body.addView(header)
        rail.list.apply {
            layoutManager = if (grid) GridLayoutManager(context, settings.gridColumns) else LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = rail.adapter
            itemAnimator = null
            isNestedScrollingEnabled = !grid
            clipToPadding = false
            setPadding(Ui.dp(context, 5), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4))
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
            isFocusable = false
            setItemViewCacheSize(2)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) rail.userScrolled = true
                    if (newState == RecyclerView.SCROLL_STATE_IDLE && rail.userScrolled) {
                        rail.userScrolled = false
                        val atEnd = if (grid) !canScrollVertically(1) else !canScrollHorizontally(1)
                        if (active && atEnd && rail.items.isNotEmpty()) append(rail, focusNew = false)
                    }
                }
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (active && (dx > 0 || grid && dy > 0)) {
                        val last = (layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
                        if (last >= rail.items.size - 3) prefetch(rail)
                    }
                }
            })
        }
        body.addView(rail.list, LayoutParams(-1, if (grid) Ui.dp(context, 620) else Ui.cardHeight(context, cardWidth) + Ui.dp(context, 12)).apply { bottomMargin = Ui.dp(context, 4) })
        // Empty and failed rails still have a D-pad landing point.
        rail.status.isFocusable = true
        Ui.focusHighlight(rail.status)
        rail.status.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> { moveVertical(rail, -1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveVertical(rail, 1); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val retryEmpty = rail.items.isEmpty() && rail.buffer.exhausted
                    fetch(rail, demand = true, reset = rail.failedReset || retryEmpty)
                    true
                }
                else -> false
            }
        }
    }

    private fun loadNearby() {
        if (!active || disposed) return
        rails.filter { !it.started && it.list.top < scroll.scrollY + scroll.height + Ui.dp(context, 320) }
            .forEach { fetch(it, demand = true) }
    }

    private fun prefetch(rail: Rail) {
        if (!settings.railPrefetchEnabled || rail.prefetchAt == rail.items.size || rail.loading || rail.buffer.exhausted) return
        rail.prefetchAt = rail.items.size
        if (rail.buffer.availableCount < RailBuffer.BATCH_SIZE) fetch(rail, demand = false)
    }

    private fun reveal(rail: Rail, focusNew: Boolean = false) {
        val newItems = rail.buffer.reveal()
        val oldSize = rail.items.size
        if (newItems.isNotEmpty()) {
            rail.items += newItems
            rail.adapter.notifyItemRangeInserted(oldSize, newItems.size)
            rail.status.isFocusable = false
            if (active && (focusNew || rail.focusWhenReady)) {
                rail.focusWhenReady = false
                focusCard(rail, if (focusNew) oldSize else rail.focusIndex)
            }
            if (channel == null && settings.heroEnabled && !grid && oldSize == 0 && rail === rails.firstOrNull()) addHero(newItems.first())
        }
        status(rail)
    }

    private fun append(rail: Rail, focusNew: Boolean = true) {
        if (rail.buffer.availableCount >= RailBuffer.BATCH_SIZE || rail.buffer.exhausted) {
            reveal(rail, focusNew = focusNew)
        } else {
            rail.focusAfterReveal = focusNew
            fetch(rail, demand = true)
        }
    }

    private fun refresh(rail: Rail) {
        val now = SystemClock.uptimeMillis()
        if (rail.lastRefresh != 0L && now - rail.lastRefresh < 500) return
        rail.lastRefresh = now
        rail.focusAfterRefresh = active
        rail.refreshScrollY = scroll.scrollY
        if (rail.loading) {
            // A first-card refresh is an explicit user action; queue it behind
            // any automatic initial load/prefetch instead of silently dropping it.
            rail.pendingRefresh = true
            return
        }
        fetch(rail, demand = true, reset = true)
    }

    private fun fetch(rail: Rail, demand: Boolean, reset: Boolean = false) {
        if (disposed) return
        if (rail.loading) { if (demand) rail.revealWhenReady = true; return }
        if (!reset && rail.buffer.exhausted) { if (demand) reveal(rail); return }
        rail.started = true
        rail.loading = true
        rail.revealWhenReady = demand
        rail.error = null
        rail.failedReset = reset
        val version = ++rail.version
        // Build a local result; failed refresh never discards visible data or advances the cursor.
        val firstPage = if (reset) 1 else rail.buffer.nextPage
        val known = if (reset) emptySet() else rail.buffer.knownIds
        val alreadyBuffered = if (reset) 0 else rail.buffer.availableCount
        status(rail)
        try {
            AppExecutors.browse.execute {
                val result = loadBatch(rail, firstPage, known, alreadyBuffered, reset)
                post {
                    if (disposed || rail.version != version) return@post
                    rail.loading = false
                    if (rail.pendingRefresh) {
                        rail.pendingRefresh = false
                        rail.revealWhenReady = false
                        rail.focusAfterRefresh = active
                        fetch(rail, demand = true, reset = true)
                        return@post
                    }
                    if (result.successfulPages == 0 && result.error != null) {
                        rail.error = failureStatus(result.error)
                        rail.failedReset = reset
                        if (!reset && rail.revealWhenReady && rail.buffer.availableCount > 0) {
                            reveal(rail, focusNew = rail.focusAfterReveal)
                        }
                        rail.focusAfterReveal = false
                        rail.revealWhenReady = false
                        restoreRefreshFocus(rail)
                        status(rail)
                        focusEmptyStatusIfPending(rail)
                        return@post
                    }

                    if (reset && result.error != null && rail.items.isNotEmpty()) {
                        // A refresh that fails partway through must leave the existing rail usable.
                        rail.error = "刷新未完成，原内容已保留，按确定重试"
                        rail.failedReset = true
                        restoreRefreshFocus(rail)
                        status(rail)
                        focusEmptyStatusIfPending(rail)
                        return@post
                    }

                    if (reset) {
                        rail.buffer = RailBuffer()
                        rail.buffer.add(result.videos)
                        rail.buffer.nextPage = result.nextPage
                        rail.buffer.exhausted = result.exhausted
                        val refreshed = rail.buffer.reveal()
                        replaceItems(rail, refreshed)
                        rail.focusIndex = 0
                        // Diff by BVID so unchanged cards and their loaded covers stay in place.
                        rail.focusWhenReady = active && !rail.focusAfterRefresh &&
                            (focusRail === rail || lastFocusedRail === rail)
                        rail.prefetchAt = -1
                        if (channel == null && settings.heroEnabled && !grid && rail === rails.firstOrNull() && refreshed.isNotEmpty()) {
                            addHero(refreshed.first())
                        }
                    } else {
                        rail.buffer.add(result.videos)
                        rail.buffer.nextPage = result.nextPage
                        rail.buffer.exhausted = result.exhausted
                        if (rail.revealWhenReady) reveal(rail, focusNew = rail.focusAfterReveal)
                    }
                    rail.error = result.error?.let { failureStatus(it, partial = true) }
                    rail.failedReset = false
                    if (reset) restoreRefreshFocus(rail)
                    rail.focusAfterReveal = false
                    rail.revealWhenReady = false
                    status(rail)
                    focusEmptyStatusIfPending(rail)
                    loadNearby()
                }
            }
        } catch (_: RejectedExecutionException) {
            rail.loading = false
            rail.error = "请求繁忙，请重试"
            restoreRefreshFocus(rail)
            status(rail)
            focusEmptyStatusIfPending(rail)
        }
    }

    /** Consume at most three API pages per user action; only successful pages advance the cursor. */
    private fun loadBatch(
        rail: Rail,
        firstPage: Int,
        knownIds: Set<String>,
        alreadyBuffered: Int,
        reset: Boolean,
    ): RailLoadResult {
        val batch = mutableListOf<VideoSummary>()
        val seen = knownIds.toMutableSet()
        var page = firstPage
        var exhausted = false
        var attempts = 0
        var successfulPages = 0
        var failure: Throwable? = null
        while (alreadyBuffered + batch.size < RailBuffer.BATCH_SIZE && attempts < 3 && !exhausted) {
            val response = try {
                api.getBrowseBatch(rail.definition, page, refreshRanking = reset && attempts == 0)
            } catch (error: Exception) {
                failure = error
                break
            }
            response.videos.forEach { if (seen.add(it.bvid)) batch += it }
            page++
            attempts++
            successfulPages++
            exhausted = response.exhausted || response.videos.isEmpty()
        }
        if (successfulPages == 0 && failure == null && alreadyBuffered == 0) exhausted = true
        return RailLoadResult(batch, page, exhausted, successfulPages, failure)
    }

    private fun replaceItems(rail: Rail, updated: List<VideoSummary>) {
        val previous = rail.items.toList()
        val next = updated.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous[oldItemPosition].bvid == next[newItemPosition].bvid
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous[oldItemPosition] == next[newItemPosition]
        }, false)
        rail.items.clear()
        rail.items.addAll(next)
        diff.dispatchUpdatesTo(rail.adapter)
    }

    private fun restoreRefreshFocus(rail: Rail) {
        if (!rail.focusAfterRefresh) return
        rail.focusAfterRefresh = false
        val position = rail.focusIndex.coerceAtMost((rail.items.size - 1).coerceAtLeast(0))
        val scrollY = rail.refreshScrollY
        scroll.post {
            if (!active || disposed) return@post
            scroll.scrollTo(0, scrollY)
            if (rail.items.isNotEmpty()) focusCard(rail, position, force = true)
            else {
                status(rail)
                if (rail.status.isFocusable) rail.status.requestFocus()
            }
        }
    }

    private fun status(rail: Rail) {
        rail.status.text = when {
            rail.loading -> if (rail.items.isEmpty()) "加载中…" else "${rail.items.size} 条 · 准备下一批…"
            rail.error != null -> rail.error
            rail.items.isEmpty() && rail.buffer.exhausted -> when (rail.definition.source) {
                BrowseSource.REGION_RECOMMEND -> "暂无更多内容 · 按确定重试"
                BrowseSource.KEYWORD_SEARCH -> "暂无匹配内容 · 按确定重试"
                else -> "暂无内容 · 按确定重试"
            }
            rail.buffer.exhausted -> if (rail.definition.source == BrowseSource.ALL_RANKING ||
                rail.definition.source == BrowseSource.REGION_RANKING) {
                "${rail.items.size} 条 · 当前榜单结果已到底"
            } else "${rail.items.size} 条 · 已到末尾"
            rail.buffer.availableCount >= RailBuffer.BATCH_SIZE -> "${rail.items.size} 条 · 下一批已就绪"
            rail.items.isEmpty() -> "暂无内容 · 按确定重试"
            else -> "${rail.items.size} 条 · 每次追加 12 条"
        }
        rail.retry.visibility = if (rail.error != null) VISIBLE else GONE
        rail.status.isFocusable = rail.items.isEmpty()
    }

    private fun failureStatus(error: Throwable, partial: Boolean = false): String {
        val apiError = generateSequence(error) { it.cause }.filterIsInstance<BiliApiException>().firstOrNull()
        val detail = apiError?.let { "API ${it.apiCode}" } ?: "网络错误"
        val prefix = if (partial) "部分加载" else "加载失败"
        return "$prefix · $detail · 确定重试"
    }

    private fun focusEmptyStatusIfPending(rail: Rail) {
        if (!active || rail.items.isNotEmpty() || !rail.focusWhenReady) return
        rail.focusWhenReady = false
        rail.status.requestFocus()
    }

    private fun moveVertical(rail: Rail, delta: Int) {
        val target = rails.getOrNull(rails.indexOf(rail) + delta)
        if (target == null) {
            if (delta < 0) hero?.requestFocus() ?: leaveToNavigation()
            return
        }
        focusRail = target
        rails.forEach { it.focusWhenReady = false }
        if (target.items.isNotEmpty()) focusCard(target, target.focusIndex) else {
            target.status.requestFocus()
            target.focusWhenReady = true
            fetch(target, demand = true)
        }
    }

    private fun focusCard(rail: Rail, index: Int, force: Boolean = false) {
        if (!active || rail.items.isEmpty()) return
        focusRail = rail
        lastFocusedRail = rail
        val position = index.coerceIn(0, rail.items.lastIndex)
        rail.focusIndex = position
        if (rail.list.findViewHolderForAdapterPosition(position) == null) {
            (rail.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
        }
        fun request(attempt: Int) {
            if (!active || !force && focusRail !== rail) return
            val card = rail.list.findViewHolderForAdapterPosition(position)?.itemView
            if (card != null) card.requestFocus()
            else if (attempt < 4) rail.list.postOnAnimation { request(attempt + 1) }
        }
        rail.list.post { request(0) }
    }

    private fun addHero(video: VideoSummary) {
        heroImage?.let(CoverLoader::cancel)
        val card = FrameLayout(context).apply {
            isFocusable = true
            isClickable = true
            background = Ui.panel(context)
            setOnClickListener { context.startActivity(Intent(context, VideoDetailActivity::class.java).putExtra(VideoDetailActivity.EXTRA_BVID, video.bvid)) }
            setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    rails.firstOrNull()?.let { focusCard(it, it.focusIndex) }; true
                } else if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_UP) { leaveToNavigation(); true } else false
            }
        }
        val image = Ui.poster(context)
        card.addView(image, FrameLayout.LayoutParams(-1, -1))
        val label = Ui.text(context, "本期推荐 · ${video.title}", 14f).apply {
            setBackgroundColor(0xB00B0E12.toInt()); gravity = Gravity.CENTER_VERTICAL; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        card.addView(label, FrameLayout.LayoutParams(-1, -1))
        heroImage = image
        heroVideo = video
        CoverLoader.load(image, video.coverUrl, 1024, coverScope,
            if (image.isAttachedToWindow) CoverLoader.Priority.VISIBLE else CoverLoader.Priority.NORMAL)
        Ui.focusHighlight(card)
        hero = card
        heroHost.removeAllViews()
        heroHost.addView(card, FrameLayout.LayoutParams(-1, -1))
    }

    private inner class Cards(private val rail: Rail) : RecyclerView.Adapter<CardHolder>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int): Long = rail.items[position].aid.takeIf { it != 0L }
            ?: rail.items[position].bvid.fold(0L) { hash, char -> hash * 31 + char.code }
        override fun getItemCount() = rail.items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = VideoCard(parent.context, grid)
            card.layoutParams = RecyclerView.LayoutParams(if (grid) -1 else cardWidth, if (grid) -2 else Ui.cardHeight(context, cardWidth)).apply {
                marginEnd = Ui.dp(context, 8); bottomMargin = Ui.dp(context, 4)
            }
            return CardHolder(card)
        }
        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val card = holder.itemView as VideoCard
            if (!grid) card.layoutParams = (card.layoutParams as RecyclerView.LayoutParams).apply {
                width = cardWidth; height = Ui.cardHeight(context, cardWidth)
            }
            card.bind(rail.items[position], coverScope)
            card.setOnFocusChangeListener { view, focused ->
                Ui.applyFocusHighlight(view, focused)
                val index = holder.bindingAdapterPosition
                if (focused && index != RecyclerView.NO_POSITION) {
                    rail.focusIndex = index; focusRail = rail; lastFocusedRail = rail
                    rails.filter { it !== rail }.forEach { it.focusWhenReady = false }
                    if (index >= rail.items.size - 3) prefetch(rail)
                }
            }
            card.setOnKeyListener { _, key, event ->
                val index = holder.bindingAdapterPosition
                if (event.action != KeyEvent.ACTION_DOWN || index == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0) { if (event.repeatCount == 0) refresh(rail); true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (index == rail.items.lastIndex) { if (event.repeatCount == 0) append(rail); true } else false
                    KeyEvent.KEYCODE_DPAD_UP -> if (!grid || index < settings.gridColumns) { moveVertical(rail, -1); true } else false
                    KeyEvent.KEYCODE_DPAD_DOWN -> when {
                        !grid -> { moveVertical(rail, 1); true }
                        index >= rail.items.size - settings.gridColumns -> { if (event.repeatCount == 0) append(rail); true }
                        else -> false
                    }
                    else -> false
                }
            }
        }
        override fun onViewRecycled(holder: CardHolder) {
            (holder.itemView as? VideoCard)?.unbind()
            super.onViewRecycled(holder)
        }
    }
    private class CardHolder(view: View) : RecyclerView.ViewHolder(view)
}
