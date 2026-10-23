package com.lvsaber.blblrebuild.data

data class WatchProgress(
    val video: VideoSummary,
    val cid: Long,
    val part: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean = false,
) {
    val canResume: Boolean get() = !completed && cid > 0 && positionMs > 0 &&
        (durationMs <= 0 || positionMs < durationMs)
    val fraction: Int get() = if (completed) 1000 else if (durationMs > 0)
        (positionMs.toDouble() / durationMs * 1000).toInt().coerceIn(0, 1000) else 0
    val label: String get() = if (completed) "已看完 · 第 $part P" else
        "看到 ${formatPosition(positionMs)} · 第 $part P"

    companion object {
        fun formatPosition(ms: Long): String {
            val seconds = (ms / 1000).coerceAtLeast(0)
            return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
                else "%02d:%02d".format(seconds / 60, seconds % 60)
        }
        fun nearEnd(position: Long, duration: Long): Boolean = duration > 0 &&
            duration - position <= minOf(30_000L, duration / 20) && position >= duration * 0.95
        fun newer(local: WatchProgress?, incoming: WatchProgress): WatchProgress =
            if (local != null && local.updatedAt >= incoming.updatedAt) local else incoming
    }
}

data class HistoryCursor(val max: Long, val viewAt: Long, val business: String)
data class HistoryPage(val entries: List<WatchProgress>, val next: HistoryCursor?, val hasMore: Boolean)

data class ResumePlan(val page: VideoPage, val positionMs: Long) {
    companion object {
        fun resolve(detail: VideoDetail, progress: WatchProgress?, cid: Long? = null, restart: Boolean = false): ResumePlan? {
            val requested = cid ?: progress?.takeIf { it.canResume && !restart }?.cid
            val page = detail.pages.find { it.cid == requested } ?: detail.pages.firstOrNull() ?: return null
            val position = progress?.takeIf { !restart && it.canResume && it.cid == page.cid }?.positionMs ?: 0
            return ResumePlan(page, if (page.durationSeconds > 0 && position >= page.durationSeconds * 1000L) 0 else position)
        }
    }
}
