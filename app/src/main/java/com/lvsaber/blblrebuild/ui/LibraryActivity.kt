package com.lvsaber.blblrebuild.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.media3.common.util.UnstableApi
import com.lvsaber.blblrebuild.core.AppExecutors
import com.lvsaber.blblrebuild.data.BiliApiClient
import com.lvsaber.blblrebuild.data.BiliApiException
import com.lvsaber.blblrebuild.data.FavoriteFolder
import com.lvsaber.blblrebuild.data.VideoSummary

@UnstableApi
class LibraryActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var body: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = BiliApiClient(this)
        setContentView(buildPage())
        if (intent.getStringExtra(EXTRA_MODE) == MODE_FAVORITES) loadFavorites() else loadHistory()
    }

    private fun buildPage(): NestedScrollView {
        val scroll = NestedScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 18), Ui.dp(context, 14), Ui.dp(context, 18), Ui.dp(context, 24))
        }
        body.addView(Ui.text(this, "我的", 28f, Ui.text))
        status = Ui.text(this, "正在加载…", 15f, Ui.muted)
        body.addView(status)
        scroll.addView(body)
        return scroll
    }

    private fun loadHistory() {
        status.text = "观看历史"
        AppExecutors.io.execute {
            runCatching { api.getHistory() }
                .onSuccess { videos -> runOnUiThread { renderVideos(videos) } }
                .onFailure { error -> runOnUiThread { showError(error) } }
        }
    }

    private fun loadFavorites() {
        status.text = "正在读取收藏夹…"
        AppExecutors.io.execute {
            runCatching { api.getFavoriteFolders() }
                .onSuccess { folders -> runOnUiThread { renderFolders(folders) } }
                .onFailure { error -> runOnUiThread { showError(error) } }
        }
    }

    private fun renderFolders(folders: List<FavoriteFolder>) {
        body.removeViews(2, (body.childCount - 2).coerceAtLeast(0))
        if (folders.isEmpty()) {
            body.addView(Ui.text(this, "没有可显示的收藏夹。", 16f, Ui.muted))
            return
        }
        folders.forEach { folder ->
            val button = Ui.button(this, "${folder.title}  ·  ${folder.count} 个视频") {
                loadFolder(folder)
            }
            body.addView(button, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)).apply {
                bottomMargin = Ui.dp(this@LibraryActivity, 8)
            })
            Ui.focusHighlight(button)
        }
    }

    private fun loadFolder(folder: FavoriteFolder) {
        status.text = "正在加载收藏夹：${folder.title}"
        AppExecutors.io.execute {
            runCatching { api.getFavoriteVideos(folder.id) }
                .onSuccess { videos -> runOnUiThread { renderVideos(videos) } }
                .onFailure { error -> runOnUiThread { showError(error) } }
        }
    }

    private fun renderVideos(videos: List<VideoSummary>) {
        body.removeViews(2, (body.childCount - 2).coerceAtLeast(0))
        if (videos.isEmpty()) {
            body.addView(Ui.text(this, "没有可显示的视频。", 16f, Ui.muted))
            return
        }
        videos.forEach { video ->
            val button = Ui.button(this, video.title) {
                startActivity(Intent(this, VideoDetailActivity::class.java).putExtra(VideoDetailActivity.EXTRA_BVID, video.bvid))
            }
            body.addView(button, LinearLayout.LayoutParams(-1, Ui.dp(this, 52)).apply {
                bottomMargin = Ui.dp(this@LibraryActivity, 8)
            })
        }
    }

    private fun showError(error: Throwable) {
        status.text = when (error) {
            is BiliApiException -> "接口 ${error.apiCode}：${error.message}"
            else -> error.message ?: "加载失败"
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_HISTORY = "history"
        const val MODE_FAVORITES = "favorites"
    }
}
