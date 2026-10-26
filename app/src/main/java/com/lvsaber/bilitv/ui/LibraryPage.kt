package com.lvsaber.bilitv.ui

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@UnstableApi
class LibraryPage(private val host: Activity, private val api: BiliApiClient, private val favorites: Boolean = false,
    private val watchLater: Boolean = false,
    private val navigateUp: (() -> Unit)? = null) : LinearLayout(host) {
    private val repository = ProgressRepository.get(host)
    private val status = Ui.text(host, "", 14f, Ui.muted)
    private val controls = LinearLayout(host).apply { orientation = VERTICAL }
    private val list = RecyclerView(host)
    private val adapter = Tiles()
    private val more = Ui.chip(host, "加载更多", false) { loadNext() }
    private var cursor: HistoryCursor? = null
    private var folderId: Long? = null
    private var folderPage = 1
    private var loading = false
    private var hasMore = true
    private var generation = 0
    private var owner = repository.account
    val accountKey get() = owner
    private var disposed = false
    private val entries = linkedMapOf<String, WatchProgress>()
    private val videos = linkedMapOf<String, VideoSummary>()
    private var preferredColumn = 0
    private var verticalFocus = false
    private var pendingNext: Pair<String, Int>? = null
    private var focusRequest = 0
    private val manager = GridLayoutManager(host, Ui.videoColumns(host))

    init {
        orientation = VERTICAL
        val heading = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(Ui.text(host, when { favorites -> "我的收藏"; watchLater -> "稍后再看"; else -> "观看历史" }, 20f), LayoutParams(0, Ui.dp(host, 40), 1f))
        heading.addView(Ui.chip(host, "刷新", false) { refresh() })
        addView(heading); addView(status); addView(controls)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.currentList.getOrNull(position)?.header != null) manager.spanCount else 1
        }
        list.layoutManager = manager; list.adapter = adapter; list.itemAnimator = null
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && manager.findLastVisibleItemPosition() >= adapter.itemCount - 4) loadNext()
            }
        })
        addView(list, LayoutParams(-1, 0, 1f)); addView(more, LayoutParams(-1, Ui.dp(host, 42)))
        refresh()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) manager.spanCount = Ui.videoColumns(host, w)
    }

    private fun rows() = VideoGridRows.rows(adapter.currentList.map { it.header != null }, manager.spanCount)

    private fun focusTile(position: Int, attempts: Int = 0, request: Int = ++focusRequest) {
        if (disposed || !isShown || position !in 0 until adapter.itemCount) return
        manager.scrollToPosition(position)
        list.postOnAnimation {
            if (disposed || !isShown || request != focusRequest) return@postOnAnimation
            val focused = rootView.findFocus()
            if (focused != null && !list.hasFocus() && focused !== list) return@postOnAnimation
            val view = list.findViewHolderForAdapterPosition(position)?.itemView
            if (view != null) {
                verticalFocus = true
                view.requestFocus()
                verticalFocus = false
            } else if (attempts < 5) focusTile(position, attempts + 1, request)
        }
    }

    private fun submitTiles(tiles: List<Tile>) {
        val holder = list.focusedChild?.let { list.findContainingViewHolder(it) }
        val focusedKey = holder?.bindingAdapterPosition?.let { adapter.currentList.getOrNull(it)?.key }
        adapter.submitList(tiles) {
            if (!isShown || disposed) return@submitList
            // Do not steal focus if the user moved to navigation while the request ran.
            val focus = rootView.findFocus()
            if (focus != null && focus !== list && !list.hasFocus()) { pendingNext = null; return@submitList }
            val pending = pendingNext
            if (pending != null) {
                pendingNext = null
                val anchor = tiles.indexOfFirst { it.key == pending.first }
                val groups = rows()
                val row = groups.indexOfFirst { anchor in it }
                val next = groups.getOrNull(row + 1)
                if (row >= 0 && next != null) focusTile(next[pending.second.coerceAtMost(next.lastIndex)])
                else if (anchor >= 0) focusTile(anchor)
            } else if (focusedKey != null) {
                val position = tiles.indexOfFirst { it.key == focusedKey }
                if (position >= 0) focusTile(position)
            }
        }
    }

    fun dispose() { disposed = true; generation++ }
    fun backToFolders(): Boolean {
        if (!favorites || folderId == null) return false
        folderId = null; refresh(); return true
    }
    fun refreshLocal() {
        if (!favorites && repository.account == owner && owner != "guest") {
            repository.recent().take(24).forEach { entry -> entries[entry.video.bvid] = WatchProgress.newer(entries[entry.video.bvid], entry) }
            renderHistory()
        }
    }
    fun refresh() {
        generation++; loading = false; owner = repository.account
        pendingNext = null; preferredColumn = 0
        focusRequest++
        cursor = null; folderPage = 1; hasMore = true
        entries.clear(); videos.clear(); controls.removeAllViews()
        if (owner == "guest") {
            adapter.submitList(emptyList()); more.visibility = GONE
            status.text = "登录后查看云端${when { favorites -> "收藏"; watchLater -> "稍后再看"; else -> "观看历史" }}，本机播放进度仍会独立保存。"
            controls.addView(Ui.button(host, "二维码登录") { host.startActivity(Intent(host, LoginActivity::class.java)) })
            return
        }
        if (favorites && folderId == null) loadFolders() else {
            if (!favorites && !watchLater) { repository.recent().take(24).forEach { entries[it.video.bvid] = it }; renderHistory() }
            loadNext()
        }
    }

    private fun loadFolders() {
        loading = true; status.text = "正在读取收藏夹…"; more.visibility = GONE
        val token = generation
        AppExecutors.io.execute {
            val result = runCatching { api.getFavoriteFolders() }
            post {
                if (disposed || token != generation || owner != repository.account) return@post
                loading = false
                result.onSuccess { folders ->
                    status.text = if (folders.isEmpty()) "还没有收藏夹" else "选择收藏夹"
                    submitTiles(folders.map { Tile(folder = it) })
                }.onFailure { fail(it) }
            }
        }
    }

    private fun loadNext() {
        if (disposed || owner == "guest" || loading || !hasMore || favorites && folderId == null) return
        loading = true; status.text = "正在加载…"; more.isEnabled = false
        val token = generation
        AppExecutors.io.execute {
            val result = runCatching {
                when {
                    watchLater -> api.getWatchLater()
                    favorites -> api.getFavoriteVideos(folderId!!, folderPage, 24)
                    else -> api.getHistoryPage(cursor, owner)
                }
            }
            post {
                if (disposed || token != generation || owner != repository.account) return@post
                loading = false; more.isEnabled = true
                result.onSuccess { data ->
                    if (data is HistoryPage) {
                        repository.merge(data.entries, owner)
                        data.entries.forEach { entry -> entries[entry.video.bvid] = WatchProgress.newer(entries[entry.video.bvid], repository.find(entry.video.bvid) ?: entry) }
                        cursor = data.next; hasMore = data.hasMore; renderHistory()
                    } else {
                        @Suppress("UNCHECKED_CAST") val incoming = data as List<VideoSummary>
                        incoming.forEach { videos[it.bvid] = it }; folderPage++; hasMore = incoming.size >= 24
                        if (watchLater) hasMore = false
                        submitTiles(videos.values.map { Tile(video = it, progress = repository.find(it.bvid)) })
                    }
                    val count = if (favorites) videos.size else entries.size
                    status.text = if (count == 0) "暂无视频" else "已加载 $count 个视频${if (hasMore) "" else " · 已到末尾"}${if (!favorites || watchLater) " · 确定续播，长按查看详情" else ""}"
                    more.visibility = if (hasMore) VISIBLE else GONE
                }.onFailure { fail(it) }
            }
        }
    }

    private fun fail(error: Throwable) {
        pendingNext = null
        more.isEnabled = true; more.visibility = VISIBLE; more.text = "重试"
        more.setOnClickListener { if (favorites && folderId == null) loadFolders() else loadNext() }
        if (error is BiliApiException && error.apiCode == -101) {
            status.text = "登录已失效，请重新登录"
            controls.removeAllViews()
            controls.addView(Ui.button(host, "重新登录") { host.startActivity(Intent(host, LoginActivity::class.java)) })
        } else status.text = "加载失败，已加载内容仍可浏览"
    }

    private fun renderHistory() {
        val tiles = mutableListOf<Tile>()
        var previous = ""
        entries.values.sortedByDescending { it.updatedAt }.forEach { progress ->
            val group = dateGroup(progress.updatedAt)
            if (group != previous) { tiles += Tile(header = group); previous = group }
            tiles += Tile(video = progress.video, progress = progress)
        }
        submitTiles(tiles)
    }

    private fun dateGroup(time: Long): String {
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return when {
            time >= today.timeInMillis -> "今天"
            time >= today.apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis -> "昨天"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(time))
        }
    }

    private data class Tile(val header: String? = null, val video: VideoSummary? = null, val progress: WatchProgress? = null, val folder: FavoriteFolder? = null) {
        val key get() = header?.let { "date:$it" } ?: folder?.let { "folder:${it.id}" } ?: video!!.bvid
    }
    private class Holder(view: View) : RecyclerView.ViewHolder(view)
    private inner class Tiles : ListAdapter<Tile, Holder>(object : DiffUtil.ItemCallback<Tile>() {
        override fun areItemsTheSame(oldItem: Tile, newItem: Tile) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: Tile, newItem: Tile) = oldItem == newItem
    }) {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = getItem(position).key.fold(0L) { hash, char -> hash * 31 + char.code }
        override fun getItemViewType(position: Int) = when { getItem(position).header != null -> 1; getItem(position).folder != null -> 2; else -> 0 }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = if (viewType == 0) VideoCard(host, grid = true) else Ui.text(host, size = if (viewType == 1) 20f else 22f)
            view.layoutParams = RecyclerView.LayoutParams(-1, if (viewType == 0) -2 else Ui.dp(host, if (viewType == 1) 30 else 80)).apply { setMargins(Ui.dp(host, 4), Ui.dp(host, 3), Ui.dp(host, 4), Ui.dp(host, 5)) }
            return Holder(view)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tile = getItem(position)
            if (tile.video != null) {
                val card = holder.itemView as VideoCard
                card.bind(tile.video)
                tile.progress?.let { card.showWatchTime(it.updatedAt) }
                if (!favorites || watchLater) {
                    holder.itemView.setOnClickListener { PlaybackLauncher.replay(host, tile.video) }
                    holder.itemView.setOnLongClickListener {
                        host.startActivity(Intent(host, VideoDetailActivity::class.java).putExtra(VideoDetailActivity.EXTRA_BVID, tile.video.bvid)); true
                    }
                }
            } else (holder.itemView as TextView).apply {
                text = tile.header ?: "▣  ${tile.folder!!.title}\n${tile.folder.count} 个视频"
                isFocusable = tile.folder != null
                background = if (tile.folder != null) Ui.panel(context) else null
                Ui.focusHighlight(this)
                if (tile.folder != null) setOnClickListener { folderId = tile.folder.id; refresh() }
                else { setOnClickListener(null); isClickable = false; isFocusable = false }
            }
            holder.itemView.setOnFocusChangeListener { view, focused ->
                Ui.applyFocusHighlight(view, focused)
                if (focused && !verticalFocus) {
                    val group = rows().find { holder.bindingAdapterPosition in it }
                    preferredColumn = group?.indexOf(holder.bindingAdapterPosition)?.coerceAtLeast(0) ?: 0
                }
            }
            holder.itemView.setOnKeyListener { _, key, event ->
                val position = holder.bindingAdapterPosition
                if (event.action != KeyEvent.ACTION_DOWN || position == RecyclerView.NO_POSITION) return@setOnKeyListener false
                if (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                    pendingNext = null; focusRequest++
                }
                val groups = rows()
                val rowIndex = groups.indexOfFirst { position in it }
                val row = groups.getOrNull(rowIndex) ?: return@setOnKeyListener false
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val column = row.indexOf(position) + if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        row.getOrNull(column)?.let { preferredColumn = column; focusTile(it) }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val delta = if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                        val next = groups.getOrNull(rowIndex + delta)
                        if (next != null) focusTile(next[preferredColumn.coerceAtMost(next.lastIndex)])
                        else if (delta < 0) {
                            if (navigateUp != null) navigateUp.invoke() else {
                                // Standalone library: the refresh action is the upper boundary.
                                (getChildAt(0) as? ViewGroup)?.getChildAt(1)?.requestFocus()
                            }
                        } else if (hasMore) {
                            pendingNext = getItem(position).key to preferredColumn
                            loadNext()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }
}
