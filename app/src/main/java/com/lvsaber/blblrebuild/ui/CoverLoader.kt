package com.lvsaber.blblrebuild.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class CoverQueueOrder(val priority: Int, val sequence: Long) : Comparable<CoverQueueOrder> {
    override fun compareTo(other: CoverQueueOrder): Int =
        compareValuesBy(this, other, CoverQueueOrder::priority, CoverQueueOrder::sequence)
}

/** Bounded sampled covers, persistent disk cache, coalescing and lifecycle-aware requests. */
object CoverLoader {
    enum class Priority(internal val rank: Int) {
        VISIBLE(0), NORMAL(1), PREFETCH(2),
    }

    /** Requests made for a page can be canceled together when that page is hidden. */
    class Scope internal constructor() {
        internal val views = WeakHashMap<ImageView, Unit>()
        @Volatile internal var generation = 0L
    }

    private enum class JobState { QUEUED, RUNNING, DONE, CANCELLED }

    private interface Work : Runnable, Comparable<Work> {
        val order: CoverQueueOrder
        override fun compareTo(other: Work): Int = order.compareTo(other.order)
    }

    private data class Subscription(
        val key: String,
        val job: CoverJob,
        val scope: Scope?,
        val scopeGeneration: Long,
        val token: Long,
        var priority: Priority,
    )

    private data class RequestTarget(
        val view: ImageView,
        val token: Long,
        val scope: Scope?,
        val scopeGeneration: Long,
    )

    private val lock = Any()
    private val diskLock = Any()
    @Volatile private var epoch = 0
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = maxOf(1, value.byteCount / 1024)
    }
    private val subscriptions = WeakHashMap<ImageView, Subscription>()
    private val viewTokens = WeakHashMap<ImageView, Long>()
    private val jobs = mutableMapOf<String, CoverJob>()
    private val sequence = AtomicInteger()
    private val requestSequence = AtomicLong()
    private const val MAX_TRACKED_JOBS = 128
    private const val VISIBLE_JOB_BURST = 32

    // Every queued work item implements Work, so the priority queue remains type-safe at runtime.
    private val workers = ThreadPoolExecutor(
        3, 3, 30, TimeUnit.SECONDS, PriorityBlockingQueue(),
        ThreadFactory { Thread(it, "bili-cover-${sequence.incrementAndGet()}") },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    private class CoverJob(
        val key: String,
        val url: String,
        val target: Int,
        val directory: File,
        val generation: Int,
        initialPriority: Priority,
    ) : Work {
        private val orderSequence = CoverLoader.requestSequence.incrementAndGet()
        val subscribers = WeakHashMap<ImageView, Unit>()
        @Volatile var state = JobState.QUEUED
        @Volatile var priority = initialPriority
        @Volatile var attempt = 0
        @Volatile private var connection: HttpURLConnection? = null

        override val order: CoverQueueOrder
            get() = CoverQueueOrder(priority.rank, orderSequence)

        fun attach(connection: HttpURLConnection): Boolean = synchronized(CoverLoader.lock) {
            if (state == JobState.CANCELLED) false else {
                this.connection = connection
                true
            }
        }

        fun detach(connection: HttpURLConnection) = synchronized(CoverLoader.lock) {
            if (this.connection === connection) this.connection = null
        }

        fun takeConnection(): HttpURLConnection? = synchronized(CoverLoader.lock) {
            connection.also { connection = null }
        }

        override fun run() {
            synchronized(CoverLoader.lock) {
                if (state != JobState.QUEUED) return
                state = JobState.RUNNING
            }

            var failure: Throwable? = null
            val bitmap = try {
                CoverLoader.readOrDownload(directory, key, url, target, generation, this)
            } catch (error: Exception) {
                failure = error
                null
            }

            var retry = false
            var targets = emptyList<RequestTarget>()
            synchronized(CoverLoader.lock) {
                if (state == JobState.CANCELLED) return
                val hasSubscribers = subscribers.keys.any { CoverLoader.subscriptions[it]?.job === this }
                if (bitmap == null && failure != null && attempt == 0 && hasSubscribers) {
                    attempt = 1
                    state = JobState.QUEUED
                    retry = true
                    // Enqueue while holding the registry lock so scope cancellation cannot
                    // remove the job just before it is submitted and leave a stale queue item.
                    CoverLoader.workers.execute(this)
                } else {
                    state = JobState.DONE
                    if (CoverLoader.jobs[key] === this) CoverLoader.jobs.remove(key)
                    targets = subscribers.keys.mapNotNull { view ->
                        val subscription = CoverLoader.subscriptions[view]?.takeIf { it.job === this } ?: return@mapNotNull null
                        CoverLoader.subscriptions.remove(view)
                        subscription.scope?.views?.remove(view)
                        RequestTarget(view, subscription.token, subscription.scope, subscription.scopeGeneration)
                    }
                    subscribers.clear()
                }
            }

            if (retry) return
            if (bitmap != null && generation == CoverLoader.epoch) CoverLoader.cache.put(key, bitmap)
            if (bitmap != null) targets.forEach { target ->
                target.view.post {
                    val tokenIsCurrent = synchronized(CoverLoader.lock) { CoverLoader.viewTokens[target.view] == target.token }
                    val scopeIsCurrent = target.scope == null || target.scope.generation == target.scopeGeneration
                    if (tokenIsCurrent && scopeIsCurrent && target.view.tag == url) {
                        target.view.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private class ClearWork(
        override val order: CoverQueueOrder,
        private val directory: File,
        private val onComplete: (Boolean) -> Unit,
    ) : Work {
        override fun run() {
            var success = true
            synchronized(CoverLoader.diskLock) {
                directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.(jpg|tmp)")) }
                    ?.forEach { if (!it.delete() && it.exists()) success = false }
            }
            Handler(Looper.getMainLooper()).post { onComplete(success) }
        }
    }

    fun newScope(): Scope = Scope()

    fun load(
        imageView: ImageView,
        rawUrl: String,
        targetWidth: Int = 512,
        scope: Scope? = null,
        priority: Priority = if (imageView.isAttachedToWindow) Priority.VISIBLE else Priority.NORMAL,
    ) {
        val url = rawUrl.replaceFirst("http://", "https://")
            .let { if (it.startsWith("//")) "https:$it" else it }
        val target = if (targetWidth > 512) 1024 else 512
        val key = "$target:$url"
        val scopeGeneration = scope?.generation ?: 0L

        if (imageView.tag == url && imageView.drawable != null) {
            cancel(imageView)
            return
        }

        val (token, connectionToClose) = synchronized(lock) {
            val current = subscriptions[imageView]
            if (current != null && current.key == key && current.scope === scope &&
                current.scopeGeneration == scopeGeneration && current.job.state in setOf(JobState.QUEUED, JobState.RUNNING)
            ) {
                current.priority = priority
                updatePriorityLocked(current.job)
                return
            }
            val oldConnection = removeSubscriptionLocked(imageView)
            nextTokenLocked(imageView) to oldConnection
        }
        connectionToClose?.disconnect()

        imageView.tag = url
        imageView.setImageDrawable(null)
        if (!url.startsWith("https://")) return

        cache.get(key)?.let { bitmap ->
            if (synchronized(lock) { viewTokens[imageView] == token } && imageView.tag == url) imageView.setImageBitmap(bitmap)
            return
        }

        val directory = File(imageView.context.applicationContext.cacheDir, "covers-v1")
        synchronized(lock) {
            // A load may have been superseded while the memory cache was checked.
            if (viewTokens[imageView] != token || imageView.tag != url) return

            var job = jobs[key]?.takeIf { it.state == JobState.QUEUED || it.state == JobState.RUNNING }
            val shouldStart = job == null
            if (job == null) {
                if (!makeRoomLocked(priority)) return
                job = CoverJob(key, url, target, directory, epoch, priority)
                jobs[key] = job
            }
            val activeJob = job
            activeJob.subscribers[imageView] = Unit
            subscriptions[imageView] = Subscription(key, activeJob, scope, scopeGeneration, token, priority)
            scope?.views?.set(imageView, Unit)
            updatePriorityLocked(activeJob)
            if (shouldStart) workers.execute(activeJob)
        }
    }

    /** Cancel this view's subscription without flushing either cache. */
    fun cancel(imageView: ImageView) {
        val connection = synchronized(lock) {
            nextTokenLocked(imageView)
            removeSubscriptionLocked(imageView)
        }
        connection?.disconnect()
    }

    /** Cancel all outstanding requests owned by a hidden page. */
    fun cancel(scope: Scope) {
        val connections = mutableListOf<HttpURLConnection>()
        synchronized(lock) {
            scope.generation++
            scope.views.keys.toList().forEach { view ->
                nextTokenLocked(view)
                removeSubscriptionLocked(view)?.let(connections::add)
            }
            scope.views.clear()
        }
        connections.forEach { it.disconnect() }
    }

    fun clear(context: android.content.Context, onComplete: (Boolean) -> Unit) {
        epoch++
        cache.evictAll()
        val directory = File(context.applicationContext.cacheDir, "covers-v1")
        runCatching {
            workers.execute(ClearWork(CoverQueueOrder(Priority.PREFETCH.rank + 1, requestSequence.incrementAndGet()), directory, onComplete))
        }.onFailure { Handler(Looper.getMainLooper()).post { onComplete(false) } }
    }

    private fun nextTokenLocked(view: ImageView): Long {
        val next = (viewTokens[view] ?: 0L) + 1L
        viewTokens[view] = next
        return next
    }

    /** Removes a subscriber and returns a running connection if the job became unneeded. */
    private fun removeSubscriptionLocked(view: ImageView): HttpURLConnection? {
        val subscription = subscriptions.remove(view) ?: return null
        subscription.scope?.views?.remove(view)
        val job = subscription.job
        job.subscribers.remove(view)
        if (job.subscribers.keys.any { subscriptions[it]?.job === job }) {
            updatePriorityLocked(job)
            return null
        }
        job.state = JobState.CANCELLED
        if (jobs[job.key] === job) jobs.remove(job.key)
        workers.remove(job)
        return job.takeConnection()
    }

    private fun updatePriorityLocked(job: CoverJob) {
        val newRank = job.subscribers.keys.mapNotNull { subscriptions[it]?.takeIf { sub -> sub.job === job } }
            .minOfOrNull { it.priority.rank } ?: return
        val updated = Priority.values().first { it.rank == newRank }
        if (job.priority == updated) return
        val wasQueued = job.state == JobState.QUEUED && workers.remove(job)
        job.priority = updated
        if (wasQueued) workers.execute(job)
    }

    /** Keep background work bounded; important requests may use a small burst after displacement. */
    private fun makeRoomLocked(incoming: Priority): Boolean {
        if (jobs.size < MAX_TRACKED_JOBS) return true
        val candidates = jobs.values
            .filter { it.state == JobState.QUEUED && it.priority == Priority.PREFETCH }
            .sortedWith(compareByDescending<CoverJob> { it.priority.rank }.thenByDescending { it.order.sequence })
        for (victim in candidates) {
            if (!workers.remove(victim)) continue
            victim.state = JobState.CANCELLED
            if (jobs[victim.key] === victim) jobs.remove(victim.key)
            victim.subscribers.keys.toList().forEach { view ->
                val subscription = subscriptions[view]?.takeIf { it.job === victim } ?: return@forEach
                subscriptions.remove(view)
                subscription.scope?.views?.remove(view)
                nextTokenLocked(view)
            }
            victim.subscribers.clear()
            return true
        }
        // Do not silently lose visible cards (or a detail poster) when every queued job is important.
        // The burst is still a hard ceiling; off-screen prefetches are expected to be rebound on attach.
        return incoming != Priority.PREFETCH && jobs.size < MAX_TRACKED_JOBS + VISIBLE_JOB_BURST
    }

    private fun readOrDownload(
        directory: File,
        key: String,
        url: String,
        target: Int,
        generation: Int,
        job: CoverJob,
    ): Bitmap? {
        if (job.state == JobState.CANCELLED) return null
        directory.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$name.jpg")
        if (file.isFile) {
            BitmapFactory.decodeFile(file.path)?.let { file.setLastModified(System.currentTimeMillis()); return it }
            file.delete()
        }
        if (job.state == JobState.CANCELLED) return null
        val connection = URL(url).openConnection() as HttpURLConnection
        if (!job.attach(connection)) {
            connection.disconnect()
            return null
        }
        val bytes = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    if (job.state == JobState.CANCELLED) return null
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > 4 * 1024 * 1024) return null
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            job.detach(connection)
            connection.disconnect()
        }
        if (job.state == JobState.CANCELLED) return null
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
            if (generation != epoch || job.state == JobState.CANCELLED) return@synchronized
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
