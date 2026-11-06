package com.lvsaber.bilitv.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.lvsaber.bilitv.data.SubtitleCue

/** Lightweight TV subtitle layer backed by Bilibili's JSON subtitle cues. */
class SubtitleOverlay(context: Context) : FrameLayout(context) {
    private val text = TextView(context).apply {
        setTextColor(Color.WHITE)
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        maxLines = 3
        textSize = 20f
        includeFontPadding = false
        visibility = GONE
        setPadding(Ui.dp(context, 18), Ui.dp(context, 6), Ui.dp(context, 18), Ui.dp(context, 6))
    }
    private var cues: List<SubtitleCue> = emptyList()
    private var scale = 1f
    private var activeCueIndex = -2
    var overlayEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                activeCueIndex = -2
                text.visibility = GONE
            }
        }

    init {
        isClickable = false
        addView(text, LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = Ui.dp(context, 48)
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val params = text.layoutParams as? LayoutParams
        if (width > 0 && params != null) {
            val targetWidth = (width * 0.84f).toInt().coerceAtMost(width)
            if (params.width != targetWidth || params.gravity != (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)) {
                params.width = targetWidth
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.leftMargin = 0
                params.rightMargin = 0
                text.layoutParams = params
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun submit(next: List<SubtitleCue>, textScale: Float) {
        cues = next.sortedBy { it.fromMs }
        scale = textScale.coerceIn(0.8f, 1.4f)
        text.textSize = 20f * scale
        activeCueIndex = -2
        text.text = ""
        text.visibility = GONE
    }

    fun update(positionMs: Long) {
        if (!overlayEnabled || cues.isEmpty()) {
            text.visibility = GONE
            return
        }
        val cueIndex = cues.indexOfLast { it.fromMs <= positionMs && positionMs <= it.toMs }
        if (cueIndex == activeCueIndex) return
        activeCueIndex = cueIndex
        text.text = cues.getOrNull(cueIndex)?.content.orEmpty()
        text.visibility = if (cueIndex < 0) GONE else VISIBLE
    }
}
