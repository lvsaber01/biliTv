package com.lvsaber.bilitv.data

/** Separates fetched data from visible cards, so prefetch never grows the UI. */
class RailBuffer {
    companion object { const val BATCH_SIZE = 12 }
    private val items = mutableListOf<VideoSummary>()
    private val ids = mutableSetOf<String>()
    var visibleCount = 0
        private set
    var nextPage = 1
    var exhausted = false
    val availableCount get() = items.size - visibleCount
    val visible: List<VideoSummary> get() = items.take(visibleCount)
    val knownIds: Set<String> get() = ids.toSet()

    fun add(videos: List<VideoSummary>) {
        videos.forEach { if (it.bvid.isNotBlank() && ids.add(it.bvid)) items += it }
    }

    fun reveal(): List<VideoSummary> {
        val start = visibleCount
        visibleCount = minOf(visibleCount + BATCH_SIZE, items.size)
        return items.subList(start, visibleCount).toList()
    }
}

/** Ranking endpoints return a finite list rather than a pageable feed. */
object BrowseBatchSlicer {
    fun slice(items: List<VideoSummary>, page: Int, batchSize: Int = RailBuffer.BATCH_SIZE): BrowseFetchResult {
        require(page > 0) { "页码必须大于 0" }
        require(batchSize > 0) { "批次大小必须大于 0" }
        val start = (page - 1) * batchSize
        if (start >= items.size) return BrowseFetchResult(emptyList(), exhausted = true)
        val end = (start + batchSize).coerceAtMost(items.size)
        return BrowseFetchResult(items.subList(start, end), exhausted = end >= items.size)
    }
}
