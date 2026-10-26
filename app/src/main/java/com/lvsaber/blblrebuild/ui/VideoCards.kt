package com.lvsaber.blblrebuild.ui

import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.view.Gravity
import android.view.View
import com.lvsaber.blblrebuild.data.ProgressRepository
import com.lvsaber.blblrebuild.data.VideoSummary
import androidx.media3.common.util.UnstableApi

/** A holder is reused; appending does not rebind existing cards. */
@UnstableApi
class VideoCard(context: Context, @Suppress("UNUSED_PARAMETER") grid: Boolean = false) : LinearLayout(context) {
    private val poster = Ui.poster(context)
    private val title = Ui.text(context, size = 13f)
    private val subtitle = Ui.text(context, size = 10f, color = Ui.muted)
    private val stats = Ui.text(context, size = 10f, color = Ui.muted)
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Ui.blue)
        visibility = View.GONE
    }
    private var boundVideo: VideoSummary? = null
    private var coverScope: CoverLoader.Scope? = null

    init {
        orientation = VERTICAL
        background = Ui.panel(context)
        isClickable = true
        isFocusable = true
        setPadding(Ui.dp(context, 6), Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2))
        val cover = FrameLayout(context)
        cover.addView(poster, FrameLayout.LayoutParams(-1, -2))
        cover.addView(progressBar, FrameLayout.LayoutParams(-1, Ui.dp(context, 5), Gravity.BOTTOM))
        addView(cover, LayoutParams(-1, -2))
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setPadding(2, 0, 2, 0)
        title.setLineSpacing(Ui.dp(context, 1).toFloat(), 1.15f)
        title.minHeight = Ui.dp(context, 40)
        addView(title, LayoutParams(-1, -2))
        listOf(subtitle, stats).forEach { label ->
            label.maxLines = 1
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            label.setPadding(2, 0, 2, 0)
            addView(label, LayoutParams(-1, Ui.dp(context, 14)))
        }
        Ui.focusHighlight(this)
    }

    fun bind(video: VideoSummary, scope: CoverLoader.Scope? = null) {
        boundVideo = video
        coverScope = scope
        title.text = video.title
        subtitle.text = "${video.author} · ${video.duration}"
        stats.text = "${publishDate(video.publishTimeSeconds)?.let { "$it · " }.orEmpty()}播放 ${count(video.views)} · 弹幕 ${count(video.danmaku)}"
        contentDescription = "${video.title}，${video.author}"
        loadCover(video)
        refreshProgress()
        setOnClickListener {
            context.startActivity(Intent(context, VideoDetailActivity::class.java).putExtra(VideoDetailActivity.EXTRA_BVID, video.bvid))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        boundVideo?.let(::loadCover)
        refreshProgress()
    }

    override fun onDetachedFromWindow() {
        CoverLoader.cancel(poster)
        super.onDetachedFromWindow()
    }

    fun unbind() {
        CoverLoader.cancel(poster)
        boundVideo = null
        coverScope = null
        poster.tag = null
        poster.setImageDrawable(null)
        setOnClickListener(null)
        setOnFocusChangeListener(null)
        setOnKeyListener(null)
    }

    private fun loadCover(video: VideoSummary) {
        val priority = if (isAttachedToWindow) CoverLoader.Priority.VISIBLE else CoverLoader.Priority.PREFETCH
        CoverLoader.load(poster, video.coverUrl, scope = coverScope, priority = priority)
    }

    fun refreshProgress() {
        val video = boundVideo ?: return
        val progress = ProgressRepository.get(context).find(video.bvid)
        progressBar.visibility = if (progress != null && (progress.positionMs > 0 || progress.completed)) View.VISIBLE else View.GONE
        progressBar.progress = progress?.fraction ?: 0
        val datePrefix = publishDate(video.publishTimeSeconds)?.let { "$it · " }.orEmpty()
        stats.text = if (progress != null) "$datePrefix${progress.label}"
        else "${datePrefix}播放 ${count(video.views)} · 弹幕 ${count(video.danmaku)}"
    }

    fun showWatchTime(time: Long) {
        val video = boundVideo ?: return
        val pattern = if (android.text.format.DateUtils.isToday(time)) "HH:mm" else "MM-dd HH:mm"
        subtitle.text = "${video.author} · ${java.text.SimpleDateFormat(pattern, java.util.Locale.CHINA).format(java.util.Date(time))}"
    }

    companion object {
        fun refreshVisible(root: View) {
            if (root is VideoCard) root.refreshProgress()
            else if (root is android.view.ViewGroup) for (index in 0 until root.childCount) refreshVisible(root.getChildAt(index))
        }
    }

    private fun count(value: Long) = if (value >= 10000) "%.1f万".format(value / 10000f) else value.toString()

    private fun publishDate(seconds: Long?): String? {
        if (seconds == null || seconds <= 0L) return null
        val date = java.util.Date(seconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L)
        val locale = java.util.Locale.CHINA
        val year = java.text.SimpleDateFormat("yyyy", locale).format(date)
        val currentYear = java.text.SimpleDateFormat("yyyy", locale).format(java.util.Date())
        val pattern = if (year == currentYear) "MM-dd" else "yyyy-MM-dd"
        return java.text.SimpleDateFormat(pattern, locale).format(date)
    }
}
