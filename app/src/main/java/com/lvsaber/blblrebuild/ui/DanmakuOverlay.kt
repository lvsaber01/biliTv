package com.lvsaber.blblrebuild.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.RectF
import android.view.View
import com.lvsaber.blblrebuild.data.DanmakuSettings

data class DanmakuItem(
    val timeMs: Long,
    val text: String,
    val color: Int,
)

/** Small, dependency-free scrolling danmaku layer for the local player. */
class DanmakuOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = Ui.dp(context, 18).toFloat()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
    }
    private var items: List<DanmakuItem> = emptyList()
    private data class Placed(val item: DanmakuItem, val lane: Int, val width: Float)
    private var placed: List<Placed> = emptyList()
    private val viewport = RectF()
    private val videoBounds = RectF()
    private var hasVideoBounds = false
    private var laneHeight = 0f
    private var baseline = 0f
    var displaySettings: DanmakuSettings = DanmakuSettings()
        set(value) {
            val normalized = value.normalized()
            if (field == normalized) return
            field = normalized
            rebuildLayout()
        }
    var videoAspectRatio: Float = 0f
        set(value) {
            val safe = value.takeIf { it.isFinite() && it > 0 } ?: 0f
            if (field == safe) return
            field = safe
            rebuildLayout()
        }

    /**
     * Uses the actual Media3 surface bounds when available. This avoids putting
     * danmaku in the full window's top-left letterbox area on non-16:9 videos.
     */
    fun setVideoBounds(bounds: RectF?) {
        if (bounds == null || bounds.width() <= 0f || bounds.height() <= 0f) {
            if (!hasVideoBounds) return
            hasVideoBounds = false
        } else {
            if (hasVideoBounds && videoBounds == bounds) return
            videoBounds.set(bounds)
            hasVideoBounds = true
        }
        rebuildLayout()
    }
    var positionMs: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            // Align drawing with display frames instead of invalidating at an
            // arbitrary 100ms timer cadence.
            postInvalidateOnAnimation()
        }
    var overlayEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    fun submit(newItems: List<DanmakuItem>) {
        items = newItems.sortedBy { it.timeMs }
        rebuildLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    /** Recompute only for new comments, preferences, or video bounds, never every frame. */
    private fun rebuildLayout() {
        viewport.set(0f, 0f, width.toFloat(), height.toFloat())
        if (width <= 0 || height <= 0) { placed = emptyList(); return }
        if (hasVideoBounds) {
            viewport.set(videoBounds)
        } else if (videoAspectRatio > 0) {
            if (width.toFloat() / height > videoAspectRatio) {
                val inset = (width - height * videoAspectRatio) / 2
                viewport.inset(inset, 0f)
            } else {
                val inset = (height - width / videoAspectRatio) / 2
                viewport.inset(0f, inset)
            }
        }
        paint.textSize = Ui.dp(context, 18) * displaySettings.textScale
        paint.setShadowLayer(3f, 1f, 1f, android.graphics.Color.argb((255 * displaySettings.opacityPercent / 100).coerceIn(0, 255), 0, 0, 0))
        laneHeight = paint.fontSpacing + Ui.dp(context, 6)
        val padding = Ui.dp(context, 8).toFloat()
        baseline = viewport.top + padding - paint.fontMetrics.ascent
        val coverage = viewport.height() * displaySettings.areaPercent / 100
        val lanes = ((coverage - 2 * padding) / laneHeight).toInt().coerceAtLeast(1)
        val previous = arrayOfNulls<Placed>(lanes)
        val gap = Ui.dp(context, 16)
        val duration = displaySettings.durationMs
        val output = ArrayList<Placed>()
        var nextLane = 0
        items.forEach { item ->
            val textWidth = paint.measureText(item.text)
            val lane = (0 until lanes).asSequence().map { (nextLane + it) % lanes }.firstOrNull { index ->
                val old = previous[index]
                if (old == null || item.timeMs - old.item.timeMs >= duration) true else {
                    val delta = item.timeMs - old.item.timeMs
                    val oldRight = viewport.width() - delta.toFloat() / duration * (viewport.width() + old.width) + old.width
                    val newLeftAtOldEnd = viewport.width() - (duration - delta).toFloat() / duration * (viewport.width() + textWidth)
                    oldRight + gap <= viewport.width() && newLeftAtOldEnd >= gap
                }
            }
            if (lane != null) {
                val entry = Placed(item, lane, textWidth)
                previous[lane] = entry; output += entry; nextLane = (lane + 1) % lanes
            }
        }
        placed = output
        invalidate()
    }

    internal data class FrameItem(val text: String, val x: Float, val baseline: Float, val color: Int)

    /** Binary search makes seek/rewind deterministic and avoids scanning the complete XML each tick. */
    internal fun frameAt(timeMs: Long): List<FrameItem> {
        val duration = displaySettings.durationMs
        var low = 0
        var high = placed.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (placed[middle].item.timeMs <= timeMs - duration) low = middle + 1 else high = middle
        }
        val frame = ArrayList<FrameItem>()
        var index = low
        while (index < placed.size && placed[index].item.timeMs <= timeMs && frame.size < displaySettings.maxVisible) {
            val entry = placed[index++]
            val elapsed = timeMs - entry.item.timeMs
            frame += FrameItem(entry.item.text, viewport.left + viewport.width() - elapsed.toFloat() / duration * (viewport.width() + entry.width),
                baseline + laneHeight * entry.lane, entry.item.color)
        }
        return frame
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayEnabled || width <= 0 || height <= 0) return
        val save = canvas.save()
        canvas.clipRect(viewport.left, viewport.top, viewport.right, viewport.top + viewport.height() * displaySettings.areaPercent / 100)
        val alpha = (255 * displaySettings.opacityPercent / 100).coerceIn(0, 255)
        val duration = displaySettings.durationMs
        var low = 0
        var high = placed.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (placed[middle].item.timeMs <= positionMs - duration) low = middle + 1 else high = middle
        }
        var index = low
        var visible = 0
        while (index < placed.size && placed[index].item.timeMs <= positionMs && visible < displaySettings.maxVisible) {
            val entry = placed[index++]
            val elapsed = positionMs - entry.item.timeMs
            val x = viewport.left + viewport.width() - elapsed.toFloat() / duration * (viewport.width() + entry.width)
            paint.color = entry.item.color
            paint.alpha = alpha
            canvas.drawText(entry.item.text, x, baseline + laneHeight * entry.lane, paint)
            visible++
        }
        canvas.restoreToCount(save)
    }

    companion object {
        fun parseXml(xml: String): List<DanmakuItem> {
            require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "不支持的弹幕 XML 声明" }
            val result = ArrayList<DanmakuItem>()
            val parser = android.util.Xml.newPullParser()
            parser.setInput(xml.reader())
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "d") {
                    val params = parser.getAttributeValue(null, "p").orEmpty().split(',')
                    val seconds = params.getOrNull(0)?.toFloatOrNull() ?: 0f
                    val color = params.getOrNull(3)?.toLongOrNull()?.toInt() ?: 0xFFFFFF
                    val text = parser.nextText().trim()
                    if (seconds.isFinite() && seconds >= 0f && text.isNotBlank()) {
                        result += DanmakuItem((seconds * 1000).toLong(), text, color or 0xFF000000.toInt())
                    }
                }
                event = parser.next()
            }
            return result
        }
    }
}
