package com.lvsaber.blblrebuild.core

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small bounded worker pool for network and decoding work.
 * The queue is deliberately bounded so a slow connection cannot grow memory
 * without limit when users press a remote-control button repeatedly.
 */
object AppExecutors {
    private val playbackPool = ThreadPoolExecutor(
        2, 3, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(12),
        ThreadFactory { task -> Thread(task, "bili-playback").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    /** Playback requests must never fall back to network IO on the main thread. */
    fun playback(task: () -> Unit): Boolean = try {
        playbackPool.execute(task)
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) { false }

    private val threadNumber = AtomicInteger(1)
    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "blbl-io-${threadNumber.getAndIncrement()}").apply {
            isDaemon = true
        }
    }

    val io: ThreadPoolExecutor = ThreadPoolExecutor(
        2,
        4,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(32),
        threadFactory,
        ThreadPoolExecutor.CallerRunsPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    /** Rapid channel switches must never run overflow network tasks on the UI thread. */
    val browse: ThreadPoolExecutor = ThreadPoolExecutor(
        2, 3, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(24),
        ThreadFactory { runnable -> Thread(runnable, "bili-browse-${threadNumber.getAndIncrement()}") },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
}

