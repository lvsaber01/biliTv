package com.lvsaber.blblrebuild.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lvsaber.blblrebuild.core.AppExecutors
import com.lvsaber.blblrebuild.data.PreviewFrame
import com.lvsaber.blblrebuild.data.PreviewSprite
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.RejectedExecutionException

/** Loads one frame from Bilibili's preview sprite without blocking the player UI. */
object PreviewLoader {
    fun load(sprite: PreviewSprite, seconds: Int, targetWidth: Int, onReady: (Bitmap?) -> Unit) {
        val frame = sprite.frameAt(seconds)
        if (frame == null) {
            onReady(null)
            return
        }
        try {
            AppExecutors.io.execute {
                val bitmap = runCatching { readFrame(frame, targetWidth) }.getOrNull()
                android.os.Handler(android.os.Looper.getMainLooper()).post { onReady(bitmap) }
            }
        } catch (_: RejectedExecutionException) {
            onReady(null)
        }
    }

    private fun readFrame(frame: PreviewFrame, targetWidth: Int): Bitmap? {
        val connection = URL(frame.imageUrl).openConnection() as HttpURLConnection
        val bytes = try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV) AppleWebKit/537.36 Chrome/131 Safari/537.36")
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 5 * 1024 * 1024) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = (bounds.outWidth / 160).coerceAtLeast(1)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val sheet = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scaleX = sheet.width.toFloat() / bounds.outWidth.toFloat()
        val scaleY = sheet.height.toFloat() / bounds.outHeight.toFloat()
        val left = (frame.column * frame.width * scaleX).toInt().coerceIn(0, sheet.width - 1)
        val top = (frame.row * frame.height * scaleY).toInt().coerceIn(0, sheet.height - 1)
        val right = ((frame.column + 1) * frame.width * scaleX).toInt().coerceIn(left + 1, sheet.width)
        val bottom = ((frame.row + 1) * frame.height * scaleY).toInt().coerceIn(top + 1, sheet.height)
        val cropped = Bitmap.createBitmap(sheet, left, top, right - left, bottom - top)
        if (cropped !== sheet) sheet.recycle()
        val ratio = targetWidth.toFloat() / cropped.width.toFloat()
        return if (ratio < 1f) Bitmap.createScaledBitmap(cropped, targetWidth, (cropped.height * ratio).toInt().coerceAtLeast(1), true)
            .also { if (it !== cropped) cropped.recycle() }
        else cropped
    }
}
