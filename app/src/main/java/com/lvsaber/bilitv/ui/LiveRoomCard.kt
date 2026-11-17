package com.lvsaber.bilitv.ui

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.lvsaber.bilitv.data.LiveRoomSummary

class LiveRoomCard(context: Context) : LinearLayout(context) {
    private val poster = Ui.poster(context)
    private val title = Ui.text(context, size = 13f)
    private val details = Ui.text(context, size = 10f, color = Ui.muted)
    private val liveLabel = Ui.text(context, "", 10f, android.graphics.Color.WHITE).apply {
        gravity = Gravity.CENTER
        background = Ui.roundedBackground(Ui.pink, Ui.dp(context, 8))
        setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
    }
    private var room: LiveRoomSummary? = null

    init {
        orientation = VERTICAL
        background = Ui.panel(context)
        isClickable = true
        isFocusable = true
        setPadding(Ui.dp(context, 6), Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2))
        val cover = FrameLayout(context)
        poster.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        cover.addView(poster, FrameLayout.LayoutParams(-1, -2))
        cover.addView(liveLabel, FrameLayout.LayoutParams(-2, Ui.dp(context, 24), Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.dp(context, 7); rightMargin = Ui.dp(context, 7)
        })
        addView(cover, LayoutParams(-1, -2))
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setPadding(2, 0, 2, 0)
        title.minHeight = Ui.dp(context, 40)
        title.setLineSpacing(Ui.dp(context, 1).toFloat(), 1.1f)
        addView(title, LayoutParams(-1, -2))
        details.maxLines = 1
        details.ellipsize = android.text.TextUtils.TruncateAt.END
        details.setPadding(2, 0, 2, 0)
        addView(details, LayoutParams(-1, Ui.dp(context, 14)))
        Ui.focusHighlight(this)
    }

    fun bind(item: LiveRoomSummary) {
        room = item
        tag = "live/room/${item.roomId}"
        title.text = item.title
        details.text = buildList {
            add(item.anchorName)
            (item.parentAreaName.takeIf(String::isNotBlank) ?: item.areaName).takeIf(String::isNotBlank)?.let(::add)
            if (item.online > 0L) add("${formatCount(item.online)} 人气")
        }.joinToString(" · ")
        val badge = when (item.liveStatus) {
            1 -> "直播中" to Ui.pink
            2 -> "轮播中" to Ui.blue
            0 -> "已下播" to 0xFF59616B.toInt()
            else -> "状态未知" to 0xFF59616B.toInt()
        }
        liveLabel.text = badge.first
        liveLabel.background = Ui.roundedBackground(badge.second, Ui.dp(context, 8))
        contentDescription = "${badge.first}，${item.title}，主播 ${item.anchorName}"
        val coverUrl = item.coverUrl
        if (coverUrl.isBlank()) poster.setImageDrawable(null) else CoverLoader.load(poster, coverUrl)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        room?.takeIf { it.coverUrl.isNotBlank() }?.let { CoverLoader.load(poster, it.coverUrl) }
    }

    override fun onDetachedFromWindow() {
        CoverLoader.cancel(poster)
        super.onDetachedFromWindow()
    }

    private fun formatCount(value: Long): String = if (value >= 10_000L) "%.1f万".format(value / 10_000f) else value.toString()
}
