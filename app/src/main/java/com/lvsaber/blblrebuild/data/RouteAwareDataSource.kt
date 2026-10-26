package com.lvsaber.blblrebuild.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Media3 DataSource wrapper that tries the selected CDN URL and then the
 * remaining candidates when a network request cannot be opened or read.
 * It never retries decoder errors because those are reported above this layer.
 */
@UnstableApi
class RouteAwareDataSource private constructor(
    private val delegateFactory: DataSource.Factory,
    private val routeState: AtomicReference<PlaybackRouteSet>,
    private val onRouteSwitched: (String, String, Int) -> Unit,
    private val promoteRoute: Boolean,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null
    private var originalSpec: DataSpec? = null
    private var candidateUrls: List<String> = emptyList()
    private var candidateIndex = 0
    private var currentPosition = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        originalSpec = dataSpec
        currentPosition = dataSpec.position
        val initialUrl = dataSpec.uri.toString()
        candidateUrls = routeState.get().urlsFor(initialUrl).ifEmpty { listOf(initialUrl) }
        candidateIndex = 0
        return openCandidate(dataSpec)
    }

    @Throws(IOException::class)
    private fun openCandidate(dataSpec: DataSpec): Long {
        var lastError: IOException? = null
        while (candidateIndex < candidateUrls.size) {
            val candidate = candidateUrls[candidateIndex]
            val source = delegateFactory.createDataSource()
            listeners.forEach(source::addTransferListener)
            try {
                val spec = dataSpec.buildUpon().setUri(Uri.parse(candidate)).setPosition(currentPosition).build()
                val length = source.open(spec)
                delegate = source
                opened = true
                if (candidateIndex > 0) {
                    notifyRouteSwitch(candidate)
                }
                return length
            } catch (error: IOException) {
                lastError = error
                runCatching { source.close() }
                candidateIndex++
            }
        }
        throw lastError ?: IOException("没有可用的播放线路")
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) return C.RESULT_END_OF_INPUT
        return try {
            val count = delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
            if (count > 0) currentPosition += count
            count
        } catch (error: IOException) {
            if (candidateIndex + 1 >= candidateUrls.size) throw error
            delegate?.let { runCatching { it.close() } }
            delegate = null
            candidateIndex++
            val spec = originalSpec ?: throw error
            val consumed = (currentPosition - spec.position).coerceAtLeast(0L)
            val remaining = if (spec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else (spec.length - consumed).coerceAtLeast(0L)
            openCandidate(spec.buildUpon().setPosition(currentPosition).setLength(remaining).build())
            val count = delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
            if (count > 0) currentPosition += count
            count
        }
    }

    override fun getUri(): Uri? = delegate?.uri ?: originalSpec?.uri

    @Throws(IOException::class)
    override fun close() {
        opened = false
        delegate?.close()
        delegate = null
        originalSpec = null
        candidateUrls = emptyList()
        candidateIndex = 0
    }

    private fun notifyRouteSwitch(candidate: String) {
        val original = candidateUrls.firstOrNull().orEmpty()
        val route = routeState.get().routeForUrl(candidate)
        if (promoteRoute) route?.let { routeState.set(routeState.get().select(it.id)) }
        onRouteSwitched(original, candidate, candidateIndex)
    }

    @UnstableApi
    class Factory(
        private val delegateFactory: DataSource.Factory,
        private val routeState: AtomicReference<PlaybackRouteSet>,
        private val onRouteSwitched: (String, String, Int) -> Unit,
        private val promoteRoute: Boolean = true,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RouteAwareDataSource(
            delegateFactory,
            routeState,
            onRouteSwitched,
            promoteRoute,
        )
    }
}
