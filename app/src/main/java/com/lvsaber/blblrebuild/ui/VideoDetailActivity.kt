package com.lvsaber.blblrebuild.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.media3.common.util.UnstableApi
import com.lvsaber.blblrebuild.core.AppExecutors
import com.lvsaber.blblrebuild.data.BiliApiClient
import com.lvsaber.blblrebuild.data.BiliApiException
import com.lvsaber.blblrebuild.data.VideoDetail

@UnstableApi
class VideoDetailActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var body: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = BiliApiClient(this)
        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        setContentView(buildPage(bvid))
        if (bvid.isBlank()) status.text = "缺少视频 BV 号" else load(bvid)
    }

    private fun buildPage(bvid: String): View {
        val scroll = NestedScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Ui.background)
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 22), Ui.dp(context, 12), Ui.dp(context, 22), Ui.dp(context, 28))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.chip(this, "‹ 返回", false) { finish() }, LinearLayout.LayoutParams(-2, Ui.dp(this, 38)))
        top.addView(Ui.text(this, "视频详情", 22f, Ui.text).apply {
            setPadding(Ui.dp(context, 14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        body.addView(top)
        status = Ui.text(this, "正在加载 $bvid…", 14f, Ui.muted).apply {
            setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 12))
        }
        body.addView(status)
        scroll.addView(body)
        return scroll
    }

    private fun load(bvid: String) {
        AppExecutors.io.execute {
            runCatching { api.getVideoDetail(bvid) }
                .onSuccess { detail -> runOnUiThread { render(detail) } }
                .onFailure { error -> runOnUiThread { status.text = friendlyMessage(error) } }
        }
    }

    private fun render(detail: VideoDetail) {
        body.removeAllViews()
        val summary = detail.summary
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.chip(this, "‹ 返回", false) { finish() }, LinearLayout.LayoutParams(-2, Ui.dp(this, 38)))
        top.addView(Ui.text(this, "视频详情", 22f, Ui.text).apply { setPadding(Ui.dp(context, 14), 0, 0, 0) }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        body.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12))
        }
        val poster = Ui.poster(this)
        hero.addView(poster, LinearLayout.LayoutParams(Ui.dp(this, 350), ViewGroup.LayoutParams.WRAP_CONTENT))
        CoverLoader.load(poster, summary.coverUrl)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 18), 0, Ui.dp(context, 4), 0)
        }
        info.addView(Ui.text(this, summary.title, 24f, Ui.text).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, Ui.dp(context, 8))
        })
        info.addView(Ui.text(this, summary.author.ifBlank { "未知 UP 主" }, 15f, Ui.blue).apply { setPadding(0, 0, 0, Ui.dp(context, 6)) })
        info.addView(Ui.text(this, "${summary.bvid}  ·  ${summary.duration}", 13f, Ui.muted).apply { setPadding(0, 0, 0, Ui.dp(context, 6)) })
        info.addView(Ui.text(this, "播放 ${formatCount(summary.views)}   弹幕 ${formatCount(summary.danmaku)}   点赞 ${formatCount(summary.likes)}", 13f, Ui.muted).apply {
            setPadding(0, 0, 0, Ui.dp(context, 10))
        })
        info.addView(Ui.button(this, "播放第一 P") {
            detail.pages.firstOrNull()?.let { page -> openPlayer(summary.bvid, page.cid, summary.title) }
        }, LinearLayout.LayoutParams(-2, Ui.dp(this, 46)))
        hero.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(hero, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(this@VideoDetailActivity, 16)
        })

        val descriptionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }
        descriptionPanel.addView(Ui.text(this, "简介", 18f, Ui.text).apply { setPadding(0, 0, 0, Ui.dp(context, 6)) })
        descriptionPanel.addView(Ui.text(this, detail.description.ifBlank { "暂无简介" }, 15f, Ui.text).apply {
            gravity = Gravity.TOP
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, 0)
        })
        body.addView(descriptionPanel, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(this@VideoDetailActivity, 16)
        })

        if (detail.pages.size > 1) {
            body.addView(Ui.text(this, "分 P 选集", 20f, Ui.text).apply { setPadding(0, 0, 0, Ui.dp(context, 8)) })
            detail.pages.forEach { page ->
                val button = Ui.chip(this, "${page.page}. ${page.title}", false) {
                    openPlayer(summary.bvid, page.cid, "${summary.title} · ${page.title}")
                }
                button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                body.addView(button, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)).apply {
                    bottomMargin = Ui.dp(this@VideoDetailActivity, 7)
                })
                Ui.focusHighlight(button)
            }
        }
    }

    private fun openPlayer(bvid: String, cid: Long, title: String) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_BVID, bvid)
            .putExtra(PlayerActivity.EXTRA_CID, cid)
            .putExtra(PlayerActivity.EXTRA_TITLE, title))
    }

    private fun formatCount(value: Long): String = when {
        value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000f)
        value >= 10_000 -> "%.1f万".format(value / 10_000f)
        else -> value.toString()
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is BiliApiException -> "接口 ${error.apiCode}：${error.message}"
        else -> error.message ?: "加载失败"
    }

    companion object {
        const val EXTRA_BVID = "bvid"
    }
}
