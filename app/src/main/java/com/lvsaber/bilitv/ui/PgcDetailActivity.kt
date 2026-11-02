package com.lvsaber.bilitv.ui

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.BiliApiException
import com.lvsaber.bilitv.data.PgcEpisode
import com.lvsaber.bilitv.data.PgcPlayableRef
import com.lvsaber.bilitv.data.PgcProgress
import com.lvsaber.bilitv.data.PgcSeasonDetail
import com.lvsaber.bilitv.data.PgcSeasonRef
import com.lvsaber.bilitv.data.ProgressRepository

private class PgcEpisodeHolder(val label: TextView) : RecyclerView.ViewHolder(label)

@UnstableApi
class PgcDetailActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var root: LinearLayout
    private lateinit var seasonStrip: LinearLayout
    private lateinit var status: TextView
    private lateinit var episodesList: RecyclerView
    private lateinit var episodesAdapter: EpisodeTiles
    private lateinit var rangeStrip: LinearLayout
    private lateinit var mainPlay: Button
    private lateinit var followButton: Button
    private var detail: PgcSeasonDetail? = null
    private var selectedSeasonId = 0L
    private var requestedEpisodeId = 0L
    private var selectedEpisodeId = 0L
    private var shownRange = 0
    private var generation = 0
    private var expandedDescription = false
    private var launching = false

    private val playerResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val seasonId = result.data!!.getLongExtra(EXTRA_SEASON_ID, 0)
        val episodeId = result.data!!.getLongExtra(EXTRA_EPISODE_ID, 0)
        if (seasonId > 0) {
            requestedEpisodeId = episodeId
            selectedEpisodeId = episodeId
            if (seasonId != selectedSeasonId) loadSeason(seasonId, focusSeason = true)
            else detail?.let { render(it, focusSeason = true) }
        }
    }

    private val repository by lazy { ProgressRepository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        api = BiliApiClient(this)
        selectedSeasonId = savedInstanceState?.getLong(STATE_SEASON) ?: intent.getLongExtra(EXTRA_SEASON_ID, 0)
        requestedEpisodeId = savedInstanceState?.getLong(STATE_EPISODE) ?: intent.getLongExtra(EXTRA_EPISODE_ID, 0)
        selectedEpisodeId = savedInstanceState?.getLong(STATE_SELECTED_EPISODE) ?: requestedEpisodeId
        setContentView(buildPage())
        if (selectedSeasonId <= 0) status.text = "缺少番剧 seasonId" else loadSeason(selectedSeasonId, focusSeason = false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_SEASON, selectedSeasonId)
        outState.putLong(STATE_EPISODE, requestedEpisodeId)
        outState.putLong(STATE_SELECTED_EPISODE, selectedEpisodeId)
        super.onSaveInstanceState(outState)
    }

    private fun buildPage(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 22), Ui.dp(context, 10), Ui.dp(context, 22), Ui.dp(context, 10))
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(Ui.chip(this, "‹ 返回", false) { finish() })
        heading.addView(Ui.text(this, "番剧详情", 22f, Ui.text).apply { setPadding(Ui.dp(context, 12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
        root.addView(heading)
        status = Ui.text(this, "正在加载作品信息…", 14f, Ui.muted).apply { setPadding(Ui.dp(context, 4), 0, 0, 0) }
        status.isFocusable = true
        status.setOnClickListener { if (selectedSeasonId > 0) loadSeason(selectedSeasonId, focusSeason = false) }
        Ui.focusHighlight(status)
        root.addView(status, LinearLayout.LayoutParams(-1, Ui.dp(this, 28)))
        root.addView(buildHero(), LinearLayout.LayoutParams(-1, Ui.dp(this, 218)))
        seasonStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(seasonStrip, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)))
        rangeStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(rangeStrip, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)))
        episodesList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PgcDetailActivity, 8)
            itemAnimator = null
            clipToPadding = false
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 8))
        }
        episodesAdapter = EpisodeTiles { episode -> playEpisode(episode, restart = false) }
        episodesList.adapter = episodesAdapter
        root.addView(episodesList, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private lateinit var coverView: ImageView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var expandButton: Button

    private fun buildHero(): View {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        }
        coverView = Ui.poster(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        hero.addView(coverView, LinearLayout.LayoutParams(Ui.dp(this, 142), -1).apply { marginEnd = Ui.dp(this@PgcDetailActivity, 14) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        titleView = Ui.text(this, "", 22f, Ui.text).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, Ui.dp(context, 2)) }
        subtitleView = Ui.text(this, "", 13f, Ui.muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, Ui.dp(context, 4)) }
        descriptionView = Ui.text(this, "", 13f, Ui.text).apply { gravity = Gravity.TOP; maxLines = 2; setLineSpacing(0f, 1.12f); setPadding(0, 0, 0, 0) }
        expandButton = Ui.chip(this, "展开简介", false) {
            expandedDescription = !expandedDescription
            descriptionView.maxLines = if (expandedDescription) 8 else 2
            expandButton.text = if (expandedDescription) "收起简介" else "展开简介"
        }
        val actionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        mainPlay = Ui.button(this, "继续观看") { detail?.let { launchSelected(it, restart = false) } }.apply { minWidth = 0 }
        val restart = Ui.chip(this, "从头播放", false) { detail?.let { launchSelected(it, restart = true) } }.apply { minWidth = 0 }
        followButton = Ui.chip(this, "追番", false) { toggleFollow() }
        actionRow.addView(mainPlay, LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f).apply { marginEnd = Ui.dp(this@PgcDetailActivity, 6) })
        actionRow.addView(restart, LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f).apply { marginStart = Ui.dp(this@PgcDetailActivity, 6); marginEnd = Ui.dp(this@PgcDetailActivity, 6) })
        actionRow.addView(followButton, LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f).apply { marginStart = Ui.dp(this@PgcDetailActivity, 6) })
        info.addView(titleView)
        info.addView(subtitleView)
        info.addView(descriptionView, LinearLayout.LayoutParams(-1, 0, 1f))
        info.addView(expandButton, LinearLayout.LayoutParams(-2, Ui.dp(this, 32)))
        info.addView(actionRow, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)))
        hero.addView(info, LinearLayout.LayoutParams(0, -1, 1f))
        return hero
    }

    private fun loadSeason(seasonId: Long, focusSeason: Boolean) {
        val token = ++generation
        status.text = "正在加载季与正片集数…"
        status.setOnClickListener(null)
        AppExecutors.io.execute {
            val result = runCatching { api.getPgcSeasonDetail(seasonId) }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { loaded ->
                    selectedSeasonId = loaded.season.seasonId
                    detail = loaded
                    render(loaded, focusSeason)
                }.onFailure {
                    status.text = "${friendlyMessage(it)} · 点击重试"
                    status.setOnClickListener { loadSeason(seasonId, focusSeason) }
                }
            }
        }
    }

    private fun render(value: PgcSeasonDetail, focusSeason: Boolean) {
        val season = value.season
        CoverLoader.load(coverView, season.coverUrl)
        titleView.text = season.title
        subtitleView.text = listOf(season.category.label, season.areas.joinToString(" / "), season.score.takeIf(String::isNotBlank)?.let { "评分 $it" },
            if (season.isFinished) "已完结" else "连载中", season.indexShow, season.badge.takeIf(String::isNotBlank))
            .filterNotNull().filter(String::isNotBlank).distinct().joinToString(" · ")
        descriptionView.text = season.description.ifBlank { "暂无简介" }
        expandedDescription = false; descriptionView.maxLines = 2; expandButton.text = "展开简介"
        followButton.text = if (season.followStatus > 0) "已追番" else "追番"
        followButton.contentDescription = if (season.followStatus > 0) "已追番，点击取消追番" else "追番"
        renderSeasons(value.seasons)
        renderEpisodes(value.episodes)
        val selected = value.episodes.firstOrNull { it.episodeId == selectedEpisodeId }
            ?: value.episodes.firstOrNull { it.episodeId == requestedEpisodeId }
            ?: repository.recentPgc().firstOrNull { it.playable.seasonId == season.seasonId && it.canResume }
                ?.let { recent -> value.episodes.firstOrNull { it.episodeId == recent.playable.episodeId } }
            ?: value.episodes.firstOrNull()
        selected?.let {
            selectedEpisodeId = it.episodeId
            updateSelectedEpisodeLabel(it)
            if (focusSeason) focusEpisode(it.episodeId)
        }
        status.text = when {
            value.episodes.isEmpty() -> "暂无可播放正片${if (value.extraSections.isNotEmpty()) " · 可查看花絮/其他" else ""}"
            value.seasons.size > 1 -> "${value.episodes.size} 集正片 · ${value.seasons.size} 季 · 按接口顺序展示"
            else -> "${value.episodes.size} 集正片${if (value.extraSections.isNotEmpty()) " · 含花絮/其他" else ""}"
        }
        status.setOnClickListener(null)
        mainPlay.post { if (!isFinishing && !focusSeason) mainPlay.requestFocus() }
    }

    private fun renderSeasons(seasons: List<PgcSeasonRef>) {
        seasonStrip.removeAllViews()
        if (seasons.size <= 1) {
            seasonStrip.visibility = View.GONE
            return
        }
        seasonStrip.visibility = View.VISIBLE
        seasonStrip.addView(Ui.text(this, "选择季", 14f, Ui.muted).apply { setPadding(0, 0, Ui.dp(context, 8), 0) })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        seasons.forEachIndexed { index, season ->
            val button = Ui.chip(this, season.title.ifBlank { "第 ${index + 1} 季" }, season.seasonId == selectedSeasonId) {
                selectedEpisodeId = 0; shownRange = 0
                loadSeason(season.seasonId, focusSeason = true)
            }
            button.tag = "pgc-season-${season.seasonId}"
            row.addView(button, LinearLayout.LayoutParams(-2, Ui.dp(this, 36)).apply { marginEnd = Ui.dp(this@PgcDetailActivity, 6) })
        }
        seasonStrip.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun renderEpisodes(episodes: List<PgcEpisode>) {
        val ranges = (episodes.indices step 24).toList()
        rangeStrip.removeAllViews()
        if (episodes.size > 24) {
            rangeStrip.addView(Ui.text(this, "集数", 14f, Ui.muted).apply { setPadding(0, 0, Ui.dp(context, 6), 0) })
            ranges.forEachIndexed { rangeIndex, start ->
                val end = minOf(start + 24, episodes.size)
                rangeStrip.addView(Ui.chip(this, "${start + 1}–$end", shownRange == rangeIndex) {
                    shownRange = rangeIndex
                    episodesAdapter.submit(episodes.subList(start, end), selectedEpisodeId, repository.recentPgc())
                    episodesList.scrollToPosition(0)
                })
            }
        }
        val start = ranges.getOrNull(shownRange) ?: 0
        val end = minOf(start + 24, episodes.size)
        episodesAdapter.submit(episodes.subList(start, end), selectedEpisodeId, repository.recentPgc())
        episodesAdapter.onEpisode = { episode -> selectedEpisodeId = episode.episodeId; updateSelectedEpisodeLabel(episode); playEpisode(episode, restart = false) }
        if (detail?.extraSections?.isNotEmpty() == true) rangeStrip.addView(Ui.chip(this, "PV / 花絮", false) { detail?.let(::showExtras) })
    }

    private fun updateSelectedEpisodeLabel(episode: PgcEpisode) {
        val p = repository.findPgc(episode.episodeId)
        mainPlay.text = if (p?.canResume == true) "继续观看 · ${episode.index.ifBlank { episode.title }}" else "播放 · ${episode.index.ifBlank { episode.title }}"
    }

    private fun playEpisode(episode: PgcEpisode, restart: Boolean) {
        val value = detail ?: return
        if (episode.requiresVip && repository.account == "guest") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("可能需要大会员")
                .setMessage("该集标记为会员内容。播放权限由 B 站接口确认；可以先登录后重试。")
                .setNegativeButton("取消", null)
                .setPositiveButton("登录") { _, _ -> startActivity(android.content.Intent(this, LoginActivity::class.java)) }
                .show()
            return
        }
        selectedEpisodeId = episode.episodeId
        launchEpisode(value, episode, restart)
    }

    private fun launchSelected(value: PgcSeasonDetail, restart: Boolean) {
        val episode = value.episodes.firstOrNull { it.episodeId == selectedEpisodeId }
            ?: value.episodes.firstOrNull() ?: run {
                Toast.makeText(this, "没有可播放的正片集数", Toast.LENGTH_SHORT).show(); return
            }
        launchEpisode(value, episode, restart)
    }

    private fun launchEpisode(value: PgcSeasonDetail, episode: PgcEpisode, restart: Boolean) {
        if (launching) return
        launching = true
        val season = value.season
        val ref = PgcPlayableRef(season.seasonId, episode.episodeId, episode.cid, episode.aid, episode.bvid,
            season.title, episode.displayTitle, episode.coverUrl.ifBlank { season.coverUrl }, season.category, season.areas)
        val progress = repository.findPgc(episode.episodeId)
        val position = progress?.takeIf { !restart && it.canResume }?.positionMs ?: 0L
        try {
            playerResult.launch(PgcPlaybackLauncher.createIntent(this, ref, position))
            mainPlay.post { launching = false }
        } catch (error: Throwable) {
            launching = false
            Toast.makeText(this, "无法启动播放：${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleFollow() {
        val season = detail?.season ?: return
        val follow = season.followStatus <= 0
        if (!api.hasSession()) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("登录后追番")
                .setMessage("追番状态会写入当前 B 站账号。")
                .setNegativeButton("取消", null)
                .setPositiveButton("登录") { _, _ -> startActivity(android.content.Intent(this, LoginActivity::class.java)) }
                .show()
            return
        }
        if (!follow) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("取消追番？")
                .setMessage("将从当前 B 站账号的追番列表中移除该作品。")
                .setNegativeButton("保留", null)
                .setPositiveButton("取消追番") { _, _ -> submitFollow(season.seasonId, false) }
                .show()
        } else submitFollow(season.seasonId, true)
    }

    private fun submitFollow(seasonId: Long, follow: Boolean) {
        followButton.isEnabled = false
        AppExecutors.io.execute {
            val result = runCatching { api.modifyPgcFollow(seasonId, follow) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                followButton.isEnabled = true
                result.onSuccess {
                    detail = detail?.let { it.copy(season = it.season.copy(followStatus = if (follow) 1 else 0)) }
                    followButton.text = if (follow) "已追番" else "追番"
                    Toast.makeText(this, if (follow) "追番成功" else "已取消追番", Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(this, friendlyMessage(it), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showExtras(value: PgcSeasonDetail) {
        val entries = value.extraSections.flatMap { section -> section.episodes.map { section.title to it } }
        if (entries.isEmpty()) {
            Toast.makeText(this, "当前季没有单独返回花絮分组", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { (section, episode) -> "$section · ${episode.displayTitle}" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("PV / 花絮 / 其他")
            .setItems(labels) { _, which -> entries.getOrNull(which)?.second?.let { playEpisode(it, restart = false) } }
            .setNegativeButton("关闭", null).show()
    }

    private fun focusEpisode(episodeId: Long) {
        val episode = detail?.episodes?.indexOfFirst { it.episodeId == episodeId } ?: -1
        if (episode < 0) return
        shownRange = episode / 24
        detail?.let { renderEpisodes(it.episodes) }
        episodesList.post { episodesList.scrollToPosition(episode % 24); episodesList.post { episodesList.findViewHolderForAdapterPosition(episode % 24)?.itemView?.requestFocus() } }
    }

    private inner class EpisodeTiles(initial: (PgcEpisode) -> Unit) : RecyclerView.Adapter<PgcEpisodeHolder>() {
        var onEpisode: (PgcEpisode) -> Unit = initial
        private var items = emptyList<PgcEpisode>()
        private var focusedEpisodeId = 0L
        private var progress = emptyMap<Long, PgcProgress>()
        fun submit(data: List<PgcEpisode>, selected: Long, progressList: List<PgcProgress>) {
            items = data
            focusedEpisodeId = selected
            progress = progressList.associateBy { it.playable.episodeId }
            notifyDataSetChanged()
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PgcEpisodeHolder = PgcEpisodeHolder(Ui.chip(parent.context, "", false) {}.apply {
            layoutParams = RecyclerView.LayoutParams(-1, Ui.dp(parent.context, 58)).apply { setMargins(4, 4, 4, 4) }
            gravity = Gravity.CENTER
            maxLines = 2
            textSize = 14f
        })
        override fun onBindViewHolder(holder: PgcEpisodeHolder, position: Int) {
            val episode = items[position]
            val watched = progress[episode.episodeId]
            holder.label.text = buildString {
                append(episode.index.ifBlank { episode.title.ifBlank { "第 ${position + 1} 集" } })
                if (episode.longTitle.isNotBlank()) append("\n${episode.longTitle}")
                if (watched?.canResume == true) append(" · 续播") else if (watched?.completed == true) append(" · 已看")
                if (episode.requiresVip) append(" · 大会员")
            }
            holder.label.contentDescription = holder.label.text
            holder.label.background = Ui.roundedBackground(if (episode.episodeId == focusedEpisodeId) 0x5533AADD else Ui.surfaceRaised, Ui.dp(this@PgcDetailActivity, 8))
            Ui.focusHighlight(holder.label)
            holder.label.setOnClickListener { focusedEpisodeId = episode.episodeId; notifyItemRangeChanged(0, itemCount); onEpisode(episode) }
            holder.label.setOnFocusChangeListener { view, focused ->
                Ui.applyFocusHighlight(view, focused)
                if (focused) focusedEpisodeId = episode.episodeId
            }
            holder.label.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || keyCode !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return@setOnKeyListener false
                val columns = (episodesList.layoutManager as? GridLayoutManager)?.spanCount ?: 8
                val target = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> position - 1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> position + 1
                    KeyEvent.KEYCODE_DPAD_UP -> position - columns
                    else -> position + columns
                }
                if (target in items.indices) {
                    episodesList.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                    if (episodesList.findViewHolderForAdapterPosition(target) == null) episodesList.post { episodesList.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
                    true
                } else false
            }
        }
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is BiliApiException -> when (error.apiCode) {
            -101 -> "登录状态失效，请重新登录"
            -404 -> "番剧或剧集已不可用"
            -412, -352 -> "B 站暂时限制了请求，请稍后重试"
            else -> error.message
        }
        else -> error.message ?: "网络暂不可用"
    }

    companion object {
        const val EXTRA_SEASON_ID = "pgc_season_id"
        const val EXTRA_EPISODE_ID = "pgc_episode_id"
        private const val STATE_SEASON = "state_pgc_season"
        private const val STATE_EPISODE = "state_pgc_episode"
        private const val STATE_SELECTED_EPISODE = "state_pgc_selected_episode"
    }
}
