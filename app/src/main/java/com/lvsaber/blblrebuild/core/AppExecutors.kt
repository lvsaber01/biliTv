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
}

