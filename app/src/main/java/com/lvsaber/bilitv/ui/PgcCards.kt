package com.lvsaber.bilitv.ui

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lvsaber.bilitv.data.PgcEpisode
import com.lvsaber.bilitv.data.PgcProgress
import com.lvsaber.bilitv.data.PgcSeason
import com.lvsaber.bilitv.data.ProgressRepository

class PgcCardView(
    context: android.content.Context,
    private val style: Style = Style.CHANNEL,
) : LinearLayout(context) {
    enum class Style { CHANNEL, EPISODE }

    private val poster = Ui.poster(context)
    private val title = Ui.text(context, size = if (style == Style.CHANNEL) 13f else 14f, color = Ui.text)
    private val subtitle = Ui.text(context, size = if (style == Style.CHANNEL) 10f else 12f, color = Ui.muted)
    private val bar = View(context)
    private var boundKey = ""

    init {
        val compact = Ui.isCompactWidth(context)
        val channelCard = style == Style.CHANNEL
        orientation = VERTICAL
        gravity = Gravity.TOP
        isFocusable = true
        isClickable = true
        background = Ui.roundedBackground(Ui.surface, Ui.dp(context, 10))
        clipToOutline = true
        if (channelCard) setPadding(Ui.dp(context, 6), Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2))
        val posterHeight = if (channelCard) -2 else Ui.dp(context, if (compact) 152 else 204)
        addView(poster, LayoutParams(-1, posterHeight))
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        if (channelCard) {
            title.setPadding(Ui.dp(context, 2), 0, Ui.dp(context, 2), 0)
            title.setLineSpacing(Ui.dp(context, 1).toFloat(), 1.15f)
            title.minHeight = Ui.dp(context, 40)
        }
        addView(title, LayoutParams(-1, if (channelCard) -2 else Ui.dp(context, if (compact) 42 else 46)).apply {
            topMargin = Ui.dp(context, if (channelCard) 2 else 5)
            if (!channelCard) {
                marginStart = Ui.dp(context, 6)
                marginEnd = Ui.dp(context, 6)
            }
        })
        subtitle.maxLines = 1
        subtitle.ellipsize = android.text.TextUtils.TruncateAt.END
        if (channelCard) subtitle.setPadding(Ui.dp(context, 2), 0, Ui.dp(context, 2), 0)
        addView(subtitle, LayoutParams(-1, Ui.dp(context, if (channelCard) 14 else if (compact) 26 else 28)).apply {
            if (!channelCard) {
                marginStart = Ui.dp(context, 6)
                marginEnd = Ui.dp(context, 6)
            }
        })
        bar.setBackgroundColor(Ui.blue)
        addView(bar, LayoutParams(if (channelCard) -1 else 0, Ui.dp(context, 3)))
        Ui.focusHighlight(this)
    }

    fun bind(season: PgcSeason, progress: PgcProgress? = null, extra: String = "") {
        boundKey = season.seasonId.toString()
        title.text = season.title
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        CoverLoader.load(poster, season.coverUrl)
        val meta = listOf(season.indexShow, season.newestEpisode, season.score.takeIf { it.isNotBlank() }?.let { "评分 $it" },
            season.badge.takeIf { it.isNotBlank() }, extra).filterNotNull().filter(String::isNotBlank).distinct()
        subtitle.text = progress?.let { "${it.playable.episodeTitle} · ${it.label}" } ?: meta.joinToString(" · ").ifBlank { season.category.label }
        bar.visibility = if (progress == null) View.GONE else View.VISIBLE
        post {
            if (progress != null && this@PgcCardView.width > 0) bar.layoutParams = bar.layoutParams.apply {
                width = (this@PgcCardView.width * progress.fraction / 1000).coerceAtLeast(Ui.dp(context, 3))
            }
        }
        contentDescription = "${season.title}，${subtitle.text}"
    }

    fun bindEpisode(episode: PgcEpisode) {
        boundKey = "${episode.seasonId}/${episode.episodeId}"
        title.text = episode.index.ifBlank { episode.title }.ifBlank { "第 ${episode.episodeId} 集" }
        subtitle.text = listOf(episode.longTitle, episode.badge).filter(String::isNotBlank).joinToString(" · ")
        CoverLoader.load(poster, episode.coverUrl)
        bar.layoutParams = bar.layoutParams.apply { width = 0 }
        contentDescription = "${title.text}，${subtitle.text}"
    }

    fun setClick(action: () -> Unit) = setOnClickListener { action() }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        boundKey = ""
    }
}

class PgcSeasonAdapter(
    private val onClick: (PgcSeason) -> Unit,
    private var progress: Map<Long, PgcProgress> = emptyMap(),
    private val itemWidthDp: Int? = null,
) : ListAdapter<PgcSeason, PgcSeasonAdapter.Holder>(DIFF) {
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    fun setProgress(values: List<PgcProgress>) {
        progress = values.groupBy { it.playable.seasonId }.mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }
        notifyItemRangeChanged(0, itemCount)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(PgcCardView(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(itemWidthDp?.let { Ui.dp(parent.context, it) } ?: -1, -2).apply { setMargins(6, 6, 6, 10) }
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val season = getItem(position)
        (holder.itemView as PgcCardView).apply {
            tag = "pgc-season/${season.seasonId}"
            bind(season, progress[season.seasonId]); setClick { onClick(season) }
        }
    }
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PgcSeason>() {
            override fun areItemsTheSame(oldItem: PgcSeason, newItem: PgcSeason) = oldItem.seasonId == newItem.seasonId
            override fun areContentsTheSame(oldItem: PgcSeason, newItem: PgcSeason) = oldItem == newItem
        }
    }
}

class PgcEpisodeAdapter(private val onClick: (PgcEpisode) -> Unit) : ListAdapter<PgcEpisode, PgcEpisodeAdapter.Holder>(DIFF) {
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(PgcCardView(parent.context, PgcCardView.Style.EPISODE).apply {
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(6, 6, 6, 10) }
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        (holder.itemView as PgcCardView).apply { bindEpisode(item); setClick { onClick(item) } }
    }
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PgcEpisode>() {
            override fun areItemsTheSame(oldItem: PgcEpisode, newItem: PgcEpisode) = oldItem.episodeId == newItem.episodeId
            override fun areContentsTheSame(oldItem: PgcEpisode, newItem: PgcEpisode) = oldItem == newItem
        }
    }
}

data class PgcTimelineItemRow(val dayLabel: String = "", val season: PgcSeason? = null, val episode: PgcEpisode? = null, val time: String = "") {
    val key: String get() = if (season == null) "day:$dayLabel" else "ep:${season.seasonId}:${episode?.episodeId}"
}

class PgcTimelineAdapter(private val onClick: (PgcSeason, PgcEpisode) -> Unit) :
    ListAdapter<PgcTimelineItemRow, PgcTimelineAdapter.Holder>(DIFF) {
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    var onCardKey: (adapterPosition: Int, keyCode: Int) -> Boolean = { _, _ -> false }

    override fun getItemViewType(position: Int) = if (getItem(position).season == null) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = if (viewType == 1) Ui.text(parent.context, size = 18f, color = Ui.text).apply {
            isFocusable = false
            isClickable = false
        }
        else PgcCardView(parent.context)
        view.layoutParams = RecyclerView.LayoutParams(-1, if (viewType == 1) Ui.dp(parent.context, 46) else -2).apply {
            setMargins(Ui.dp(parent.context, 5), Ui.dp(parent.context, 5), Ui.dp(parent.context, 5), Ui.dp(parent.context, 8))
        }
        return Holder(view)
    }

    fun focusRows(spanCount: Int): List<List<Int>> = PgcTimelineFocusPolicy.buildRows(
        itemCount = itemCount,
        dayHeaderPositions = currentList.indices.filter { currentList[it].season == null }.toSet(),
        spanCount = spanCount,
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        if (row.season == null) (holder.itemView as TextView).text = row.dayLabel
        else {
            val season = row.season
            val episode = row.episode ?: return
            (holder.itemView as PgcCardView).apply {
                tag = "pgc-timeline/${season.seasonId}/${episode.episodeId}"
                bind(season, extra = "${episode.displayTitle}${row.time.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}")
                setClick { onClick(season, episode) }
                setOnKeyListener { _, keyCode, event ->
                    event.action == KeyEvent.ACTION_DOWN && onCardKey(holder.bindingAdapterPosition, keyCode)
                }
            }
        }
    }
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PgcTimelineItemRow>() {
            override fun areItemsTheSame(oldItem: PgcTimelineItemRow, newItem: PgcTimelineItemRow) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: PgcTimelineItemRow, newItem: PgcTimelineItemRow) = oldItem == newItem
        }
    }
}

/** Stable visual-row navigation for the date-grouped schedule grid. */
object PgcTimelineFocusPolicy {
    fun buildRows(itemCount: Int, dayHeaderPositions: Set<Int>, spanCount: Int): List<List<Int>> {
        require(spanCount > 0)
        val rows = mutableListOf<List<Int>>()
        var row = mutableListOf<Int>()
        fun finishRow() {
            if (row.isNotEmpty()) rows += row.toList()
            row = mutableListOf()
        }
        for (position in 0 until itemCount) {
            if (position in dayHeaderPositions) {
                finishRow()
                continue
            }
            row += position
            if (row.size == spanCount) finishRow()
        }
        finishRow()
        return rows
    }

    fun verticalTarget(rows: List<List<Int>>, adapterPosition: Int, direction: Int): Int? {
        require(direction == -1 || direction == 1)
        val rowIndex = rows.indexOfFirst { adapterPosition in it }
        if (rowIndex < 0) return null
        val adjacent = rows.getOrNull(rowIndex + direction) ?: return null
        val column = rows[rowIndex].indexOf(adapterPosition)
        return adjacent.getOrNull(column.coerceAtMost(adjacent.lastIndex))
    }

    fun horizontalTarget(rows: List<List<Int>>, adapterPosition: Int, direction: Int): Int? {
        require(direction == -1 || direction == 1)
        val row = rows.firstOrNull { adapterPosition in it } ?: return null
        val column = row.indexOf(adapterPosition)
        return row.getOrNull(column + direction)
    }
}

object PgcPlaybackLauncher {
    fun createIntent(context: android.content.Context, playable: com.lvsaber.bilitv.data.PgcPlayableRef, startMs: Long = 0L): android.content.Intent =
        android.content.Intent(context, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_BVID, playable.bvid)
            putExtra(PlayerActivity.EXTRA_CID, playable.cid)
            putExtra(PlayerActivity.EXTRA_TITLE, playable.displayTitle)
            putExtra(PlayerActivity.EXTRA_PGC_SEASON_ID, playable.seasonId)
            putExtra(PlayerActivity.EXTRA_PGC_EPISODE_ID, playable.episodeId)
            putExtra(PlayerActivity.EXTRA_PGC_AID, playable.aid)
            putExtra(PlayerActivity.EXTRA_PGC_SEASON_TITLE, playable.seasonTitle)
            putExtra(PlayerActivity.EXTRA_PGC_EPISODE_TITLE, playable.episodeTitle)
            putExtra(PlayerActivity.EXTRA_PGC_CATEGORY, playable.category.seasonType)
            putStringArrayListExtra(PlayerActivity.EXTRA_PGC_AREAS, ArrayList(playable.areas))
            putExtra("cover", playable.coverUrl)
            putExtra("start_ms", startMs)
            putExtra("account", ProgressRepository.get(context).account)
        }
    fun launch(context: android.content.Context, playable: com.lvsaber.bilitv.data.PgcPlayableRef, startMs: Long = 0L) {
        context.startActivity(createIntent(context, playable, startMs))
    }
}
