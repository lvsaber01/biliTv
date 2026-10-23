package com.lvsaber.blblrebuild.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bounded sampled covers, persistent disk cache and coalesced requests. */
object CoverLoader {
    private val diskLock = Any()
    @Volatile private var epoch = 0
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = maxOf(1, value.byteCount / 1024)
    }
    private val pending = mutableMapOf<String, MutableList<WeakReference<ImageView>>>()
    private val sequence = AtomicInteger()
    private val workers = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(128),
        ThreadFactory { Thread(it, "bili-cover-${sequence.incrementAndGet()}") }, ThreadPoolExecutor.AbortPolicy())
        .apply { allowCoreThreadTimeOut(true) }

    fun load(imageView: ImageView, rawUrl: String, targetWidth: Int = 512) {
        val url = rawUrl.replaceFirst("http://", "https://").let { if (it.startsWith("//")) "https:$it" else it }
        if (imageView.tag == url && imageView.drawable != null) return
        imageView.tag = url
        imageView.setImageDrawable(null)
        if (!url.startsWith("https://")) return
        val target = if (targetWidth > 512) 1024 else 512
        val key = "$target:$url"
        cache.get(key)?.let { imageView.setImageBitmap(it); return }
        val directory = File(imageView.context.applicationContext.cacheDir, "covers-v1")
        synchronized(pending) {
            pending[key]?.let { it += WeakReference(imageView); return }
            pending[key] = mutableListOf(WeakReference(imageView))
        }
        try {
            val generation = epoch
            workers.execute {
                val bitmap = runCatching { readOrDownload(directory, key, url, target, generation) }.getOrNull()
                if (bitmap != null && generation == epoch) cache.put(key, bitmap)
                val targets = synchronized(pending) { pending.remove(key).orEmpty() }
                targets.forEach { ref -> ref.get()?.let { view ->
                    view.post { if (view.tag == url && bitmap != null) view.setImageBitmap(bitmap) }
                } }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(pending) { pending.remove(key) }
        }
    }

    fun clear(context: android.content.Context, onComplete: (Boolean) -> Unit) {
        epoch++
        cache.evictAll()
        val directory = File(context.applicationContext.cacheDir, "covers-v1")
        try {
            workers.execute {
                var success = true
                synchronized(diskLock) {
                    directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.(jpg|tmp)")) }?.forEach { if (!it.delete() && it.exists()) success = false }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete(success) }
            }
        } catch (_: RejectedExecutionException) { android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete(false) } }
    }

    private fun readOrDownload(directory: File, key: String, url: String, target: Int, generation: Int): Bitmap? {
        directory.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$name.jpg")
        if (file.isFile) {
            BitmapFactory.decodeFile(file.path)?.let { file.setLastModified(System.currentTimeMillis()); return it }
            file.delete()
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        val bytes = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > 4 * 1024 * 1024) return null
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > target * 2) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val ratio = minOf(1f, target.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (ratio < 1f) Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * ratio).toInt()), maxOf(1, (decoded.height * ratio).toInt()), true)
            .also { if (it !== decoded) decoded.recycle() } else decoded
        runCatching { synchronized(diskLock) {
            if (generation != epoch) return@synchronized
            val temp = File(directory, "$name.tmp")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            temp.renameTo(file)
            prune(directory)
        } }
        return bitmap
    }

    @Synchronized private fun prune(directory: File) {
        val files = directory.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.lastModified() }.orEmpty()
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= 64L * 1024 * 1024) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }
}
