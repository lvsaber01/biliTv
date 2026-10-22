package com.lvsaber.blblrebuild.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

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
    var positionMs: Long = 0L
        set(value) {
            field = value
            invalidate()
        }
    var overlayEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    fun submit(newItems: List<DanmakuItem>) {
        items = newItems.sortedBy { it.timeMs }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayEnabled || width <= 0 || height <= 0) return
        val laneHeight = max(Ui.dp(context, 28), (height / 9).coerceAtLeast(1))
        val visibleWindow = 6_000L
        items.asSequence()
            .filter { positionMs >= it.timeMs && positionMs - it.timeMs < visibleWindow }
            .take(40)
            .forEach { item ->
                val elapsed = (positionMs - item.timeMs).coerceAtLeast(0L)
                val textWidth = paint.measureText(item.text)
                val x = width - (elapsed.toFloat() / visibleWindow) * (width + textWidth)
                val lane = (item.text.hashCode().ushr(1) % 8)
                val y = laneHeight * (lane + 1).toFloat()
                paint.color = item.color
                canvas.drawText(item.text, x, y, paint)
            }
    }

    companion object {
        fun parseXml(xml: String): List<DanmakuItem> {
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
                    if (seconds >= 0f && text.isNotBlank()) {
                        result += DanmakuItem(seconds.toLong() * 1000L, text, color or 0xFF000000.toInt())
                    }
                }
                event = parser.next()
            }
            return result
        }
    }
}
