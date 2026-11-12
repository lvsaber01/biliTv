package com.lvsaber.bilitv.ui

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lvsaber.bilitv.data.LiveLibraryRepository
import com.lvsaber.bilitv.data.LiveRoomSummary

class LiveRoomCard(context: Context) : LinearLayout(context) {
    private val poster = Ui.poster(context)
    private val title = Ui.text(context, size = 13f)
    private val details = Ui.text(context, size = 10f, color = Ui.muted)
    private val liveLabel = Ui.text(context, "LIVE", 10f, android.graphics.Color.WHITE).apply {
        gravity = Gravity.CENTER
        background = Ui.roundedBackground(Ui.pink, Ui.dp(context, 8))
        setPadding(Ui.dp(context, 7), 0, Ui.dp(context, 7), 0)
    }
    private val favorite = Button(context).apply {
        text = "☆"
        setTextSize(19f)
        setTextColor(android.graphics.Color.WHITE)
        isAllCaps = false
        minWidth = Ui.dp(context, 40)
        minHeight = Ui.dp(context, 36)
        background = Ui.roundedBackground(0xB3000000.toInt(), Ui.dp(context, 18))
        contentDescription = "收藏直播间"
        Ui.focusHighlight(this)
    }
    private var room: LiveRoomSummary? = null
    private var repository: LiveLibraryRepository? = null

    init {
        orientation = VERTICAL
        background = Ui.panel(context)
        isClickable = true
        isFocusable = true
        setPadding(Ui.dp(context, 6), Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2))
        val cover = FrameLayout(context)
        poster.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        cover.addView(poster, FrameLayout.LayoutParams(-1, -2))
        cover.addView(liveLabel, FrameLayout.LayoutParams(-2, Ui.dp(context, 22), Gravity.TOP or Gravity.START).apply {
            topMargin = Ui.dp(context, 7); leftMargin = Ui.dp(context, 7)
        })
        cover.addView(favorite, FrameLayout.LayoutParams(Ui.dp(context, 40), Ui.dp(context, 36), Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.dp(context, 4); rightMargin = Ui.dp(context, 4)
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

    fun bind(item: LiveRoomSummary, library: LiveLibraryRepository) {
        room = item
        repository = library
        tag = "live/room/${item.roomId}"
        title.text = item.title
        details.text = buildList {
            add(item.anchorName)
            (item.parentAreaName.takeIf(String::isNotBlank) ?: item.areaName).takeIf(String::isNotBlank)?.let(::add)
            if (item.online > 0L) add("${formatCount(item.online)} 人气")
        }.joinToString(" · ")
        contentDescription = "直播中，${item.title}，主播 ${item.anchorName}"
        val coverUrl = item.coverUrl
        if (coverUrl.isBlank()) poster.setImageDrawable(null) else CoverLoader.load(poster, coverUrl)
        updateFavorite()
        setOnClickListener {
            context.startActivity(Intent(context, LivePlayerActivity::class.java).putExtra(LivePlayerActivity.EXTRA_ROOM_ID, item.roomId))
        }
        favorite.setOnClickListener {
            this@LiveRoomCard.repository?.toggleFavorite(item) ?: return@setOnClickListener
            updateFavorite()
        }
    }

    private fun updateFavorite() {
        val saved = room?.let { repository?.isFavorite(it.roomId) } == true
        favorite.text = if (saved) "★" else "☆"
        favorite.contentDescription = if (saved) "取消收藏" else "收藏直播间"
        favorite.setTextColor(if (saved) Ui.pink else android.graphics.Color.WHITE)
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
