package com.lvsaber.bilitv.ui

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.PgcEpisode
import com.lvsaber.bilitv.data.PgcPlayableRef
import com.lvsaber.bilitv.data.PgcSeasonDetail
import com.lvsaber.bilitv.data.PgcSeasonRef
import com.lvsaber.bilitv.data.ProgressRepository

/** Full-screen in-player PGC season/episode overlay; it never exits the player. */
class PgcEpisodePickerView(
    context: android.content.Context,
    private val api: BiliApiClient,
    initial: PgcPlayableRef,
    private val onClose: () -> Unit,
    private val onSelect: (PgcPlayableRef) -> Unit,
) : FrameLayout(context) {
    private val repository = ProgressRepository.get(context)
    private val seasonRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val episodeList = RecyclerView(context)
    private val status = Ui.text(context, "正在读取季集…", 14f, Ui.muted)
    private val retryButton = Ui.chip(context, "重试", false) { loadSeason(retrySeasonId, selectedEpisodeId) }.apply { visibility = View.GONE }
    private val headerTitle = Ui.text(context, initial.seasonTitle, 20f, Ui.text)
    private val closeButton = Ui.chip(context, "关闭", false) { onClose() }
    private val episodesAdapter = Episodes()
    private var detail: PgcSeasonDetail? = null
    private var generation = 0
    private var disposed = false
    private var selectedEpisodeId = initial.episodeId
    private var selectedSeasonId = initial.seasonId
    private var retrySeasonId = initial.seasonId

    init {
        setBackgroundColor(0xD9000000.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundedBackground(0xF2151A21.toInt(), Ui.dp(context, 18))
            setPadding(Ui.dp(context, 20), Ui.dp(context, 14), Ui.dp(context, 20), Ui.dp(context, 16))
            elevation = Ui.dp(context, 16).toFloat()
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, Ui.dp(context, 46), 1f))
        header.addView(closeButton)
        panel.addView(header)
        panel.addView(Ui.text(context, "选择季", 13f, Ui.muted).apply { setPadding(0, Ui.dp(context, 3), 0, Ui.dp(context, 3)) })
        val seasonScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(seasonRow) }
        panel.addView(seasonScroll, LinearLayout.LayoutParams(-1, Ui.dp(context, 48)))
        panel.addView(status, LinearLayout.LayoutParams(-1, Ui.dp(context, 30)))
        panel.addView(retryButton, LinearLayout.LayoutParams(-2, Ui.dp(context, 38)))
        episodeList.layoutManager = GridLayoutManager(context, 8)
        episodeList.adapter = episodesAdapter
        episodeList.itemAnimator = null
        episodeList.clipToPadding = false
        panel.addView(episodeList, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(panel, LayoutParams(-1, -1, Gravity.CENTER).apply {
            leftMargin = Ui.dp(context, 54); rightMargin = Ui.dp(context, 54)
            topMargin = Ui.dp(context, 38); bottomMargin = Ui.dp(context, 38)
        })
        loadSeason(initial.seasonId, initial.episodeId)
    }

    fun focusInitial() {
        closeButton.post {
            val current = episodeList.findViewHolderForAdapterPosition(episodesAdapter.items.indexOfFirst { it.episodeId == selectedEpisodeId })?.itemView
            (current ?: closeButton).requestFocus()
        }
    }

    fun dispose() { disposed = true; generation++ }

    private fun loadSeason(seasonId: Long, episodeId: Long) {
        retrySeasonId = seasonId
        val token = ++generation
        status.text = "正在加载本季剧集…"
        retryButton.visibility = View.GONE
        AppExecutors.io.execute {
            val result = runCatching { api.getPgcSeasonDetail(seasonId) }
            post {
                if (token != generation || disposed) return@post
                result.onSuccess { loaded ->
                    detail = loaded
                    selectedSeasonId = loaded.season.seasonId
                    selectedEpisodeId = episodeId.takeIf { id -> loaded.episodes.any { it.episodeId == id } }
                        ?: loaded.episodes.firstOrNull()?.episodeId ?: 0L
                    headerTitle.text = loaded.season.title
                    status.text = "${loaded.episodes.size} 集正片${if (loaded.seasons.size > 1) " · ${loaded.seasons.size} 季" else ""}"
                    retryButton.visibility = View.GONE
                    renderSeasons(loaded.seasons)
                    episodesAdapter.submit(loaded.episodes)
                    val index = loaded.episodes.indexOfFirst { it.episodeId == selectedEpisodeId }
                    episodeList.post {
                        if (loaded.episodes.isNotEmpty()) episodeList.scrollToPosition(index.coerceAtLeast(0))
                        episodeList.post {
                            val focusTarget = if (index >= 0) episodeList.findViewHolderForAdapterPosition(index)?.itemView else null
                            (focusTarget ?: seasonRow.findViewWithTag<View>("pgc-picker-season-$selectedSeasonId") ?: closeButton).requestFocus()
                        }
                    }
                }.onFailure { error ->
                    status.text = "本季集数加载失败：${error.message ?: "请重试"}"
                    retryButton.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun renderSeasons(seasons: List<PgcSeasonRef>) {
        seasonRow.removeAllViews()
        seasons.forEachIndexed { index, season ->
            val button = Ui.chip(context, season.title.ifBlank { "第 ${index + 1} 季" }, season.seasonId == selectedSeasonId) {
                val preferred = detail?.episodes?.firstOrNull { it.episodeId == selectedEpisodeId }?.episodeId ?: 0L
                loadSeason(season.seasonId, preferred)
            }.apply {
                tag = "pgc-picker-season-${season.seasonId}"
                setOnKeyListener { _, key, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (key) {
                        KeyEvent.KEYCODE_DPAD_UP -> { closeButton.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { episodesAdapter.focusFirst(); true }
                        else -> false
                    }
                }
            }
            seasonRow.addView(button, LinearLayout.LayoutParams(-2, Ui.dp(context, 38)).apply { marginEnd = Ui.dp(context, 7) })
        }
    }

    private inner class Episodes : RecyclerView.Adapter<Episodes.Holder>() {
        var items: List<PgcEpisode> = emptyList()
            private set
        inner class Holder(val tile: Button) : RecyclerView.ViewHolder(tile)
        fun submit(value: List<PgcEpisode>) { items = value; notifyDataSetChanged() }
        fun focusFirst() { episodeList.post { episodeList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() } }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(Ui.chip(parent.context, "", false) {}.apply {
            layoutParams = RecyclerView.LayoutParams(-1, Ui.dp(parent.context, 54)).apply { setMargins(4, 4, 4, 4) }
            maxLines = 2; textSize = 14f
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val episode = items[position]
            val progress = repository.findPgc(episode.episodeId)
            holder.tile.text = buildString {
                append(episode.index.ifBlank { episode.title.ifBlank { "第 ${position + 1} 集" } })
                if (episode.episodeId == selectedEpisodeId) append(" ✓")
                if (progress?.canResume == true) append("\n续播") else if (progress?.completed == true) append("\n已看")
                if (episode.requiresVip) append(" · VIP")
            }
            holder.tile.setOnClickListener {
                val season = detail?.season ?: return@setOnClickListener
                val ref = PgcPlayableRef(season.seasonId, episode.episodeId, episode.cid, episode.aid, episode.bvid,
                    season.title, episode.displayTitle, episode.coverUrl.ifBlank { season.coverUrl }, season.category, season.areas)
                onSelect(ref)
            }
            holder.tile.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val columns = (episodeList.layoutManager as? GridLayoutManager)?.spanCount ?: 8
                val target = when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> position - 1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> position + 1
                    KeyEvent.KEYCODE_DPAD_UP -> position - columns
                    KeyEvent.KEYCODE_DPAD_DOWN -> position + columns
                    else -> return@setOnKeyListener false
                }
                when {
                    target in items.indices -> {
                        episodeList.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                            ?: episodeList.post { episodeList.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
                        true
                    }
                    key == KeyEvent.KEYCODE_DPAD_UP && position < columns -> {
                        seasonRow.findViewWithTag<View>("pgc-picker-season-$selectedSeasonId")?.requestFocus()
                        true
                    }
                    key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_DOWN -> true
                    else -> false
                }
            }
        }
    }
}
