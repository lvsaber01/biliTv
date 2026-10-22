package com.lvsaber.blblrebuild.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.lvsaber.blblrebuild.core.AppExecutors
import java.net.HttpURLConnection
import java.net.URL

/** Lightweight cover loader with a bounded in-memory cache and CDN-safe headers. */
object CoverLoader {
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun load(imageView: ImageView, rawUrl: String) {
        val url = rawUrl.toHttps()
        imageView.tag = url
        imageView.setImageDrawable(null)
        if (url.isBlank()) return
        cache.get(url)?.let {
            imageView.setImageBitmap(it)
            return
        }
        AppExecutors.io.execute {
            val bitmap = download(url)
            if (bitmap != null) cache.put(url, bitmap)
            imageView.post {
                if (imageView.tag == url && bitmap != null) imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun download(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.inputStream.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun String.toHttps(): String = replaceFirst("http://", "https://")
}
