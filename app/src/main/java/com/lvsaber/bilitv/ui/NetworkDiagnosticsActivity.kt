package com.lvsaber.bilitv.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.PlaybackDiagnosticsStore
import com.lvsaber.bilitv.data.PlaybackRoute
import com.lvsaber.bilitv.data.ProbeTiming
import com.lvsaber.bilitv.data.RouteProbeClient
import com.lvsaber.bilitv.data.RouteProbeResult

class NetworkDiagnosticsActivity : AppCompatActivity() {
    private lateinit var routeHost: LinearLayout
    private lateinit var summary: TextView
    private lateinit var testButton: android.widget.Button
    private lateinit var backButton: android.widget.Button
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildPage())
        render()
        window.decorView.post { (if (testButton.isEnabled) testButton else backButton).requestFocus() }
    }

    private fun buildPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.pageBackground(context)
            setPadding(Ui.pageInset(context), Ui.dp(context, 14), Ui.pageInset(context), Ui.dp(context, 18))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        backButton = Ui.chip(this, "‹ 返回", false) { finish() }
        header.addView(backButton)
        header.addView(Ui.text(this, "网络与播放线路", 27f), LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1f))
        root.addView(header)

        val wide = !Ui.isCompactWidth(this) &&
            resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 720f
        val scroll = NestedScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 18))
        }
        val overview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val connectionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 18), Ui.dp(context, 14), Ui.dp(context, 18), Ui.dp(context, 14))
        }
        connectionCard.addView(Ui.text(this, "当前网络", 18f).apply { setPadding(0, 0, 0, Ui.dp(context, 4)) })
        connectionCard.addView(Ui.text(this, RouteProbeClient.networkLabel(this), 16f, Ui.blue).apply { setPadding(0, 0, 0, Ui.dp(context, 5)) })
        connectionCard.addView(Ui.text(this, "诊断仅对当前播放地址进行小范围 Range 请求；不保存完整签名 URL，也不会上传结果。", 13f, Ui.muted).apply {
            setPadding(0, 0, 0, 0)
        })
        overview.addView(connectionCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Ui.dp(this@NetworkDiagnosticsActivity, 10)
        })

        summary = Ui.text(this).apply {
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 18), Ui.dp(context, 12), Ui.dp(context, 18), Ui.dp(context, 12))
        }
        overview.addView(Ui.text(this, "播放会话", 18f).apply {
            setPadding(Ui.dp(context, 4), Ui.dp(context, 5), Ui.dp(context, 4), Ui.dp(context, 5))
        })
        overview.addView(summary, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Ui.dp(this@NetworkDiagnosticsActivity, 12)
        })

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        testButton = Ui.button(this, "测试全部线路") { testRoutes() }
        actions.addView(testButton)
        actions.addView(Ui.chip(this, "清除结果", false) {
            PlaybackDiagnosticsStore.clearProbes()
            render()
        }, LinearLayout.LayoutParams(-2, Ui.dp(this, 42)).apply { marginStart = Ui.dp(this@NetworkDiagnosticsActivity, 10) })
        overview.addView(actions)

        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        results.addView(Ui.text(this, "线路结果", 18f).apply {
            setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), Ui.dp(context, 5))
        })
        routeHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        results.addView(routeHost)
        results.addView(Ui.text(this, "测速仅代表当前网络、当前签名地址和当前时间；实际播放仍以首帧、缓冲和解码结果为准。", 13f, Ui.muted).apply {
            setPadding(Ui.dp(context, 4), Ui.dp(context, 10), Ui.dp(context, 4), 0)
        })

        if (wide) {
            body.addView(overview, LinearLayout.LayoutParams(0, -1, 4f).apply { marginEnd = Ui.dp(this@NetworkDiagnosticsActivity, 14) })
            body.addView(results, LinearLayout.LayoutParams(0, -1, 6f))
        } else {
            body.addView(overview, LinearLayout.LayoutParams(-1, -2))
            body.addView(results, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@NetworkDiagnosticsActivity, 14) })
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun render() {
        if (!::routeHost.isInitialized) return
        val snapshot = PlaybackDiagnosticsStore.snapshot()
        summary.text = buildString {
            append("当前线路：${snapshot.routeHost}\n")
            append("首帧：${snapshot.firstFrameMs?.let { "${it}ms" } ?: "未记录"}\n")
            append("缓冲：${snapshot.bufferingCount} 次 / ${snapshot.bufferingMs}ms\n")
            append("自动切线：${snapshot.switchCount} 次\n")
            append("最近错误：${snapshot.lastError ?: "无"}")
        }
        val routes = PlaybackDiagnosticsStore.routeSet().routes
        routeHost.removeAllViews()
        if (routes.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = Ui.dp(context, 220)
                background = Ui.panel(context)
                setPadding(Ui.dp(context, 22), Ui.dp(context, 20), Ui.dp(context, 22), Ui.dp(context, 20))
            }
            empty.addView(Ui.text(this, "开始播放后可测试线路", 18f, Ui.text).apply {
                gravity = Gravity.CENTER
            })
            empty.addView(Ui.text(this, "当前没有活动播放线路。可先播放一个视频，再从播放器打开诊断。", 14f, Ui.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(context, 5), 0, 0)
            })
            routeHost.addView(empty, LinearLayout.LayoutParams(-1, -2))
        } else {
            val probes = PlaybackDiagnosticsStore.probes()
            routes.forEach { route -> routeHost.addView(routeView(route, probes[route.id])) }
        }
        testButton.isEnabled = !testing && routes.isNotEmpty()
        testButton.text = if (testing) "测试中…" else "测试全部线路"
    }

    private fun routeView(route: PlaybackRoute, probe: RouteProbeResult?): View {
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8))
        }
        host.addView(Ui.text(this, "${route.displayName}${if (route.primary) " · 主线路" else ""}", 16f))
        host.addView(Ui.text(this, route.displayHost, 14f, Ui.blue))
        host.addView(Ui.text(this, probe?.let(::formatProbe) ?: "未测试", 13f, if (probe?.success == true) Ui.text else Ui.muted))
        return host.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@NetworkDiagnosticsActivity, 8) }
        }
    }

    private fun testRoutes() {
        val routes = PlaybackDiagnosticsStore.routeSet().routes
        if (testing || routes.isEmpty()) return
        testing = true
        render()
        val accepted = try {
            AppExecutors.diagnostics.execute {
                val results = routes.map { RouteProbeClient().probe(it) }
                runOnUiThread {
                    testing = false
                    PlaybackDiagnosticsStore.setProbes(results)
                    render()
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            testing = false
            render()
            false
        }
        if (!accepted) android.widget.Toast.makeText(this, "诊断请求繁忙，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun formatProbe(result: RouteProbeResult): String {
        fun timing(label: String, timing: ProbeTiming): String = "$label${timing.grade} · ${timing.firstByteMs}ms · ${timing.bytesRead / 1024}KB"
        return buildString {
            append(timing("视频：", result.video))
            result.audio?.let { append("\n"); append(timing("音频：", it)) }
            if (!result.success) result.video.error?.let { append("\n错误：$it") }
        }
    }
}
