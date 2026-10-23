package com.lvsaber.blblrebuild.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.lvsaber.blblrebuild.data.SubtitleCue

/** Lightweight TV subtitle layer backed by Bilibili's JSON subtitle cues. */
class SubtitleOverlay(context: Context) : FrameLayout(context) {
    private val text = TextView(context).apply {
        setTextColor(Color.WHITE)
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
        gravity = Gravity.CENTER
        maxLines = 3
        textSize = 20f
        visibility = GONE
        setPadding(Ui.dp(context, 18), Ui.dp(context, 6), Ui.dp(context, 18), Ui.dp(context, 6))
    }
    private var cues: List<SubtitleCue> = emptyList()
    private var scale = 1f
    var overlayEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) text.visibility = GONE
        }

    init {
        isClickable = false
        addView(text, LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = Ui.dp(context, 48)
            leftMargin = Ui.dp(context, 80)
            rightMargin = Ui.dp(context, 80)
        })
    }

    fun submit(next: List<SubtitleCue>, textScale: Float) {
        cues = next.sortedBy { it.fromMs }
        scale = textScale.coerceIn(0.8f, 1.4f)
        text.textSize = 20f * scale
    }

    fun update(positionMs: Long) {
        if (!overlayEnabled || cues.isEmpty()) {
            text.visibility = GONE
            return
        }
        val cue = cues.lastOrNull { it.fromMs <= positionMs && positionMs <= it.toMs }
        text.text = cue?.content.orEmpty()
        text.visibility = if (cue == null) GONE else VISIBLE
    }
}
