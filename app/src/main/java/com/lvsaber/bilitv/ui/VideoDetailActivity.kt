package com.lvsaber.bilitv.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.media3.common.util.UnstableApi
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.BiliApiException
import com.lvsaber.bilitv.data.VideoDetail
import com.lvsaber.bilitv.data.ProgressRepository
import com.lvsaber.bilitv.data.VideoSummary

@UnstableApi
class VideoDetailActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var currentDetail: VideoDetail? = null
    private var playButton: android.widget.Button? = null
    private var watchLaterButton: android.widget.Button? = null
    private var favoriteButton: android.widget.Button? = null
    private var upButton: android.widget.Button? = null
    private var openedUpProfile = false
    private var launching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = BiliApiClient(this)
        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        setContentView(buildPage(bvid))
        if (bvid.isBlank()) status.text = "缺少视频 BV 号" else load(bvid)
    }

    private fun buildPage(bvid: String): View {
        val scroll = NestedScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Ui.background)
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 22), Ui.dp(context, 12), Ui.dp(context, 22), Ui.dp(context, 28))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.chip(this, "‹ 返回", false) { finish() }, LinearLayout.LayoutParams(-2, Ui.dp(this, 38)))
        top.addView(Ui.text(this, "视频详情", 22f, Ui.text).apply {
            setPadding(Ui.dp(context, 14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        body.addView(top)
        status = Ui.text(this, "正在加载 $bvid…", 14f, Ui.muted).apply {
            setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 12))
        }
        body.addView(status)
        scroll.addView(body)
        return scroll
    }

    private fun load(bvid: String) {
        val accepted = AppExecutors.playback {
            runCatching { api.getVideoDetail(bvid) }
                .onSuccess { detail -> runOnUiThread { render(detail) } }
                .onFailure { error -> runOnUiThread { status.text = friendlyMessage(error) } }
        }
        if (!accepted) status.text = "请求繁忙，请返回后重试"
    }

    private fun render(detail: VideoDetail) {
        if (isDestroyed) return
        currentDetail = detail
        body.removeAllViews()
        val summary = detail.summary
        val focusRows = mutableListOf<List<View>>()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = Ui.chip(this, "‹ 返回", false) { finish() }
        top.addView(back, LinearLayout.LayoutParams(-2, Ui.dp(this, 38)))
        focusRows += listOf(back)
        top.addView(Ui.text(this, "视频详情", 22f, Ui.text).apply { setPadding(Ui.dp(context, 14), 0, 0, 0) }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        body.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }
        val poster = Ui.poster(this)
        // LayoutParams are in dp. Weight the poster against the measured hero
        // width instead of feeding physical display pixels into a dp slot.
        hero.addView(poster, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.34f).apply {
            marginEnd = Ui.dp(this@VideoDetailActivity, 14)
        })
        CoverLoader.load(poster, summary.coverUrl)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        info.addView(Ui.text(this, summary.title, 18f, Ui.text).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, Ui.dp(context, 8))
        })
        val authorButton = Ui.chip(this, "UP主  ${summary.author.ifBlank { "未知 UP 主" }}", false) {
            if (summary.authorMid > 0L) {
                openedUpProfile = true
                startActivity(Intent(this, UpProfileActivity::class.java).putExtra(UpProfileActivity.EXTRA_MID, summary.authorMid))
            } else {
                android.widget.Toast.makeText(this, "当前视频没有作者主页信息", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        upButton = authorButton
        info.addView(authorButton, LinearLayout.LayoutParams(-1, Ui.dp(this, 40)))
        // Keep remote navigation aligned with the actual visual rows: DPAD_UP
        // from playback goes straight to the UP action above it.
        focusRows += listOf(authorButton)
        info.addView(Ui.text(this, "${summary.bvid}  ·  ${summary.duration}", 13f, Ui.muted).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, Ui.dp(context, 6))
        })
        info.addView(Ui.text(this, "播放 ${formatCount(summary.views)}   弹幕 ${formatCount(summary.danmaku)}   点赞 ${formatCount(summary.likes)}", 13f, Ui.muted).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, Ui.dp(context, 10))
        })
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val primary = Ui.button(this, "播放") { launch(detail) }
        primary.minWidth = 0
        primary.isSingleLine = true
        primary.ellipsize = android.text.TextUtils.TruncateAt.END
        playButton = primary
        val restart = Ui.chip(this, "从头播放", false) { launch(detail, detail.pages.firstOrNull()?.cid, true) }
        restart.minWidth = 0
        restart.isSingleLine = true
        restart.ellipsize = android.text.TextUtils.TruncateAt.END
        actions.addView(primary, LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f).apply {
            marginEnd = Ui.dp(this@VideoDetailActivity, 6)
        })
        actions.addView(restart, LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f).apply {
            marginStart = Ui.dp(this@VideoDetailActivity, 6)
        })
        info.addView(actions)
        focusRows += listOf(primary, restart)
        val libraryActions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val watchLater = Ui.chip(this, "稍后再看", false) { addToWatchLater(summary) }
        val favorite = Ui.chip(this, "收藏", false) { showFavoriteChooser(summary) }
        watchLaterButton = watchLater
        favoriteButton = favorite
        watchLater.minWidth = 0
        favorite.minWidth = 0
        libraryActions.addView(watchLater, LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f))
        libraryActions.addView(favorite, LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f).apply { marginStart = Ui.dp(this@VideoDetailActivity, 10) })
        info.addView(libraryActions, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)).apply { topMargin = Ui.dp(this@VideoDetailActivity, 8) })
        focusRows += listOf(watchLater, favorite)
        updateProgress()
        hero.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(hero, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(this@VideoDetailActivity, 16)
        })

        val descriptionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }
        descriptionPanel.addView(Ui.text(this, "简介", 18f, Ui.text).apply { setPadding(0, 0, 0, Ui.dp(context, 6)) })
        descriptionPanel.addView(Ui.text(this, detail.description.ifBlank { "暂无简介" }, 15f, Ui.text).apply {
            gravity = Gravity.TOP
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, 0)
        })

        if (detail.pages.size > 1) {
            body.addView(Ui.text(this, "分 P 选集", 20f, Ui.text).apply { setPadding(0, 0, 0, Ui.dp(context, 8)) })
            detail.pages.forEach { page ->
                val button = Ui.chip(this, "${page.page}. ${page.title}", false) {
                    launch(detail, page.cid)
                }
                button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                body.addView(button, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)).apply {
                    bottomMargin = Ui.dp(this@VideoDetailActivity, 7)
                })
                Ui.focusHighlight(button)
                focusRows += listOf(button)
            }
        }
        val description = descriptionPanel.getChildAt(1) as TextView
        description.maxLines = 4
        var expanded = false
        val expand = Ui.chip(this, "展开 / 收起简介", false) {
            expanded = !expanded
            description.maxLines = if (expanded) Int.MAX_VALUE else 4
        }
        body.addView(expand)
        focusRows += listOf(expand)
        body.addView(descriptionPanel, LinearLayout.LayoutParams(-1, -2))
        focusRows.forEachIndexed { rowIndex, row -> row.forEachIndexed { column, view ->
            view.id = View.generateViewId()
            view.setOnKeyListener { _, key, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) false else {
                    val target = when (key) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> focusRows.getOrNull(rowIndex - 1)?.let { it[column.coerceAtMost(it.lastIndex)] }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> focusRows.getOrNull(rowIndex + 1)?.let { it[column.coerceAtMost(it.lastIndex)] }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> row.getOrNull(column - 1)
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> row.getOrNull(column + 1)
                        else -> return@setOnKeyListener false
                    }
                    target?.requestFocus(); true
                }
            }
        } }
        primary.post { if (!isFinishing && !isDestroyed) primary.requestFocus() }
    }

    private fun launch(detail: VideoDetail, cid: Long? = null, restart: Boolean = false) {
        if (launching) return
        if (detail.pages.isEmpty()) {
            android.widget.Toast.makeText(this, "视频没有可播放的分 P", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        launching = true
        runCatching { PlaybackLauncher.launch(this, detail, cid, restart) }.onFailure {
            launching = false
            android.widget.Toast.makeText(this, "无法启动播放：${it.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun addToWatchLater(video: VideoSummary) {
        if (ProgressRepository.get(this).account == "guest") {
            android.widget.Toast.makeText(this, "请先登录后使用稍后再看", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        watchLaterButton?.isEnabled = false
        AppExecutors.io.execute {
            val result = runCatching { api.addToWatchLater(video) }
            runOnUiThread {
                watchLaterButton?.isEnabled = true
                result.onSuccess {
                    watchLaterButton?.text = "已加入稍后再看"
                    android.widget.Toast.makeText(this, "已加入稍后再看", android.widget.Toast.LENGTH_SHORT).show()
                }.onFailure { error -> android.widget.Toast.makeText(this, error.message ?: "添加失败", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showFavoriteChooser(video: VideoSummary) {
        if (ProgressRepository.get(this).account == "guest") {
            android.widget.Toast.makeText(this, "请先登录后使用收藏", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        favoriteButton?.isEnabled = false
        AppExecutors.io.execute {
            val result = runCatching { api.getFavoriteFolders(video) }
            runOnUiThread {
                favoriteButton?.isEnabled = true
                result.onSuccess { folders ->
                    if (folders.isEmpty()) {
                        android.widget.Toast.makeText(this, "没有可用的收藏夹", android.widget.Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    val checked = folders.map { it.selected }.toBooleanArray()
                    val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("选择收藏夹")
                        .setMultiChoiceItems(folders.map { "${it.title}（${it.count}）" }.toTypedArray(), checked) { _, which, selected -> checked[which] = selected }
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存") { _, _ ->
                            val add = folders.indices.filter { checked[it] && !folders[it].selected }.map { folders[it].id }
                            val remove = folders.indices.filter { !checked[it] && folders[it].selected }.map { folders[it].id }
                            if (add.isEmpty() && remove.isEmpty()) return@setPositiveButton
                            AppExecutors.io.execute {
                                val saveResult = runCatching { api.updateFavorite(video, add, remove) }
                                runOnUiThread {
                                    saveResult.onSuccess {
                                        favoriteButton?.text = if (checked.any { it }) "已收藏" else "收藏"
                                        android.widget.Toast.makeText(this, "收藏已更新", android.widget.Toast.LENGTH_SHORT).show()
                                    }.onFailure { error -> android.widget.Toast.makeText(this, error.message ?: "收藏更新失败", android.widget.Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                        .create()
                    dialog.show()
                }.onFailure { error -> android.widget.Toast.makeText(this, error.message ?: "读取收藏夹失败", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        launching = false
        updateProgress()
        if (openedUpProfile) {
            openedUpProfile = false
            upButton?.post { if (!isFinishing && !isDestroyed) upButton?.requestFocus() }
        }
    }
    private fun updateProgress() {
        val detail = currentDetail ?: return
        val progress = ProgressRepository.get(this).find(detail.summary.bvid)
        playButton?.text = if (progress?.canResume == true && detail.pages.any { it.cid == progress.cid }) "继续播放 · ${progress.label}" else "播放第一 P"
    }

    private fun formatCount(value: Long): String = when {
        value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000f)
        value >= 10_000 -> "%.1f万".format(value / 10_000f)
        else -> value.toString()
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is BiliApiException -> "接口 ${error.apiCode}：${error.message}"
        else -> error.message ?: "加载失败"
    }

    companion object {
        const val EXTRA_BVID = "bvid"
    }
}
