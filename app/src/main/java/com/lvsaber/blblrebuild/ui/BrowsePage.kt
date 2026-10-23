package com.lvsaber.blblrebuild.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.util.UnstableApi
import com.lvsaber.blblrebuild.core.AppExecutors
import com.lvsaber.blblrebuild.data.AppSettings
import com.lvsaber.blblrebuild.data.BiliApiClient
import com.lvsaber.blblrebuild.data.BrowseRail
import com.lvsaber.blblrebuild.data.ChannelCatalog
import com.lvsaber.blblrebuild.data.ChannelDefinition
import com.lvsaber.blblrebuild.data.RailBuffer
import com.lvsaber.blblrebuild.data.VideoSummary
import java.util.concurrent.RejectedExecutionException

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
        val adapter = Cards(this)
    }

    private val scroll = NestedScrollView(context)
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val rails = mutableListOf<Rail>()
    private var focusRail: Rail? = null
    private var active = false
    private var disposed = false
    private var hero: View? = null
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
        val definitions = if (grid) listOf(BrowseRail("featured", "热门精选"))
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
        verticalPosition = scroll.scrollY
        focusRail = null
        rails.forEach {
            it.focusWhenReady = false; it.focusAfterReveal = false
            it.layoutState = it.list.layoutManager?.onSaveInstanceState()
            it.list.adapter = null
            it.list.recycledViewPool.clear()
        }
    }

    fun dispose() { disposed = true; deactivate(); rails.forEach { it.version++ } }

    private fun addRail(definition: BrowseRail) {
        val rail = Rail(definition)
        rails += rail
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(context, definition.title, 15f).apply { setPadding(0, 0, 0, 0) }, LayoutParams(0, Ui.dp(context, 26), 1f))
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
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { fetch(rail, demand = true, reset = rail.failedReset); true }
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
        if (rail.loading || now - rail.lastRefresh < 500) return
        rail.lastRefresh = now
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
                val result = runCatching {
                    val batch = mutableListOf<VideoSummary>()
                    val seen = known.toMutableSet()
                    var page = firstPage
                    var exhausted = false
                    var attempts = 0
                    while (alreadyBuffered + batch.size < RailBuffer.BATCH_SIZE && attempts < 3 && !exhausted) {
                        val incoming = api.getBrowseVideos(rail.definition, page)
                        incoming.forEach { if (seen.add(it.bvid)) batch += it }
                        page++
                        attempts++
                        exhausted = incoming.isEmpty()
                    }
                    if (batch.isEmpty() && alreadyBuffered == 0) exhausted = true
                    Triple(batch.toList(), page, exhausted)
                }
                post {
                    if (disposed || rail.version != version) return@post
                    rail.loading = false
                    result.onSuccess { (batch, page, exhausted) ->
                        if (reset) {
                            rail.buffer = RailBuffer()
                            val oldSize = rail.items.size
                            rail.items.clear()
                            if (oldSize > 0) rail.adapter.notifyItemRangeRemoved(0, oldSize)
                            rail.focusIndex = 0
                            rail.focusWhenReady = active && (focusRail === rail || lastFocusedRail === rail)
                            rail.prefetchAt = -1
                        }
                        rail.buffer.add(batch)
                        rail.buffer.nextPage = page
                        rail.buffer.exhausted = exhausted
                        if (rail.revealWhenReady) reveal(rail, focusNew = rail.focusAfterReveal)
                        rail.focusAfterReveal = false
                        rail.revealWhenReady = false
                        status(rail)
                        loadNearby()
                    }.onFailure {
                        rail.error = "加载失败，按确定或重试"
                        rail.focusAfterReveal = false
                        rail.revealWhenReady = false
                        status(rail)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            rail.loading = false
            rail.error = "请求繁忙，请重试"
            status(rail)
        }
    }

    private fun status(rail: Rail) {
        rail.status.text = when {
            rail.loading -> if (rail.items.isEmpty()) "加载中…" else "${rail.items.size} 条 · 准备下一批…"
            rail.error != null -> rail.error
            rail.buffer.exhausted -> "${rail.items.size} 条 · 已到末尾"
            rail.buffer.availableCount >= RailBuffer.BATCH_SIZE -> "${rail.items.size} 条 · 下一批已就绪"
            rail.items.isEmpty() -> "暂无内容 · 按确定重试"
            else -> "${rail.items.size} 条 · 每次追加 12 条"
        }
        rail.retry.visibility = if (rail.error != null) VISIBLE else GONE
        rail.status.isFocusable = rail.items.isEmpty()
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

    private fun focusCard(rail: Rail, index: Int) {
        if (!active || rail.items.isEmpty()) return
        focusRail = rail
        lastFocusedRail = rail
        val position = index.coerceIn(0, rail.items.lastIndex)
        rail.focusIndex = position
        if (rail.list.findViewHolderForAdapterPosition(position) == null) {
            (rail.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
        }
        fun request(attempt: Int) {
            if (!active || focusRail !== rail) return
            val card = rail.list.findViewHolderForAdapterPosition(position)?.itemView
            if (card != null) card.requestFocus()
            else if (attempt < 4) rail.list.postOnAnimation { request(attempt + 1) }
        }
        rail.list.post { request(0) }
    }

    private fun addHero(video: VideoSummary) {
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
        CoverLoader.load(image, video.coverUrl, 1024)
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
            card.bind(rail.items[position])
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
    }
    private class CardHolder(view: View) : RecyclerView.ViewHolder(view)
}
