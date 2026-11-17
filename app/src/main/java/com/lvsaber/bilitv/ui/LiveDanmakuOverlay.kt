package com.lvsaber.bilitv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import android.os.SystemClock
import com.lvsaber.bilitv.data.DanmakuSettings
import kotlin.math.ceil

/** Real-time overlay for live comments; independent from the VOD timestamp scheduler. */
class LiveDanmakuOverlay(context: Context) : View(context), Choreographer.FrameCallback {
    private data class Entry(val text: String, val width: Float, val lane: Int, val startedAtMs: Long, val speedPxPerSecond: Float, val durationMs: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
    }
    private val bounds = RectF()
    private val entries = ArrayList<Entry>()
    private var choreographer: Choreographer? = null
    private var frameScheduled = false
    var displaySettings: DanmakuSettings = DanmakuSettings()
        set(value) {
            field = value.normalized()
            paint.textSize = Ui.dp(context, 18) * field.textScale
            invalidate()
        }
    var overlayEnabled: Boolean = true
        set(value) {
            field = value
            if (value) scheduleFrame() else invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        paint.textSize = Ui.dp(context, 18).toFloat()
    }

    fun setVideoBounds(rect: RectF?) {
        if (rect == null) bounds.set(0f, 0f, width.toFloat(), height.toFloat()) else bounds.set(rect)
        invalidate()
    }

    fun addMessage(text: String) {
        if (!overlayEnabled || text.isBlank() || width <= 0 || height <= 0) return
        val safe = text.trim().take(MAX_TEXT_LENGTH)
        paint.textSize = Ui.dp(context, 18) * displaySettings.textScale
        val textWidth = paint.measureText(safe)
        val lineHeight = paint.textSize * 1.45f
        val laneCount = ceil(bounds.height() * displaySettings.areaPercent / 100f / lineHeight).toInt().coerceAtLeast(1)
        val now = SystemClock.elapsedRealtime()
        val lane = (0 until laneCount).firstOrNull { candidate ->
            val last = entries.lastOrNull { it.lane == candidate } ?: return@firstOrNull true
            val x = bounds.right - (now - last.startedAtMs).coerceAtLeast(0L) / 1000f * last.speedPxPerSecond
            x + last.width <= bounds.right - Ui.dp(context, 22)
        } ?: return
        if (entries.size >= displaySettings.maxVisible) {
            val oldest = entries.minByOrNull { it.startedAtMs } ?: return
            entries.remove(oldest)
        }
        val duration = displaySettings.durationMs.toFloat() / displaySettings.speed
        val speed = (bounds.width() + textWidth) / duration
        entries += Entry(safe, textWidth, lane, now, speed, duration)
        scheduleFrame()
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!isAttachedToWindow || !overlayEnabled) return
        val now = SystemClock.elapsedRealtime()
        entries.removeAll { now - it.startedAtMs >= it.durationMs }
        if (entries.isNotEmpty()) {
            invalidate()
            scheduleFrame()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayEnabled || bounds.width() <= 0f || bounds.height() <= 0f) return
        val now = SystemClock.elapsedRealtime()
        val coverageBottom = bounds.top + bounds.height() * displaySettings.areaPercent / 100f
        val save = canvas.save()
        canvas.clipRect(bounds.left, bounds.top, bounds.right, coverageBottom)
        paint.alpha = (255 * displaySettings.opacityPercent / 100).coerceIn(0, 255)
        val lineHeight = paint.textSize * 1.45f
        entries.forEach { entry ->
            val elapsed = (now - entry.startedAtMs).coerceAtLeast(0L)
            val x = bounds.right - elapsed / 1000f * entry.speedPxPerSecond
            val baseline = bounds.top + (entry.lane + 1) * lineHeight
            canvas.drawText(entry.text, x, baseline, paint)
        }
        canvas.restoreToCount(save)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        choreographer = Choreographer.getInstance()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        if (frameScheduled) choreographer?.removeFrameCallback(this)
        frameScheduled = false
        choreographer = null
        entries.clear()
        super.onDetachedFromWindow()
    }

    private fun scheduleFrame() {
        if (!overlayEnabled || !isAttachedToWindow || entries.isEmpty() || frameScheduled) return
        frameScheduled = true
        choreographer?.postFrameCallback(this)
    }

    companion object {
        private const val MAX_TEXT_LENGTH = 120
    }
}
