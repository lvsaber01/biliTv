package com.lvsaber.bilitv.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.*

@UnstableApi
object PlaybackLauncher {
    private val pending = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
    fun launch(activity: Activity, detail: VideoDetail, cid: Long? = null, restart: Boolean = false) {
        val repository = ProgressRepository.get(activity)
        val progress = repository.find(detail.summary.bvid, cid)
        val plan = ResumePlan.resolve(detail, progress, cid, restart) ?: return
        val page = plan.page
        val resume = plan.positionMs
        activity.startActivity(Intent(activity, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_BVID, detail.summary.bvid).putExtra(PlayerActivity.EXTRA_CID, page.cid)
            .putExtra(PlayerActivity.EXTRA_TITLE, detail.summary.title).putExtra("start_ms", resume)
            .putExtra("part", page.page).putExtra("cover", detail.summary.coverUrl).putExtra("author", detail.summary.author)
            .putExtra("aid", detail.summary.aid).putExtra("account", repository.account))
    }

    fun replay(activity: Activity, video: VideoSummary) {
        if (!pending.add(activity)) return
        Toast.makeText(activity, "正在准备播放…", Toast.LENGTH_SHORT).show()
        val accepted = AppExecutors.playback {
            val result = runCatching { BiliApiClient(activity).getVideoDetail(video.bvid) }
            activity.runOnUiThread {
                pending.remove(activity)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { launch(activity, it) }.onFailure { Toast.makeText(activity, "无法打开视频，请稍后重试", Toast.LENGTH_SHORT).show() }
            }
        }
        if (!accepted) {
            pending.remove(activity)
            Toast.makeText(activity, "播放请求繁忙，请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }
}
