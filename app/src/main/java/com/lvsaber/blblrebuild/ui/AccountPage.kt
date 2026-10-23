package com.lvsaber.blblrebuild.ui

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.lvsaber.blblrebuild.core.AppExecutors
import com.lvsaber.blblrebuild.data.BiliApiClient
import com.lvsaber.blblrebuild.data.ProgressRepository

class AccountPage(host: Activity, openHistory: () -> Unit, openFavorites: () -> Unit, openWatchLater: () -> Unit, openFollowing: () -> Unit, openSettings: () -> Unit) : NestedScrollView(host) {
    init {
        val body = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(context, 18), 0, Ui.dp(context, 28))
        }
        body.addView(Ui.text(host, "我的空间", 30f))
        body.addView(Ui.text(host, "继续喜欢的内容，管理你的观看体验", 14f, Ui.muted))
        val account = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL; background = Ui.panel(context)
            setPadding(Ui.dp(context, 22), Ui.dp(context, 22), Ui.dp(context, 22), Ui.dp(context, 22))
        }
        val avatar = Ui.poster(host)
        account.addView(avatar, LinearLayout.LayoutParams(Ui.dp(host, 88), Ui.dp(host, 88)))
        val labels = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val name = Ui.text(host, "欢迎来到 biliTv", 25f)
        val status = Ui.text(host, "登录后同步历史与收藏；本机进度自动保存", 14f, Ui.muted)
        labels.addView(name); labels.addView(status)
        account.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        account.addView(Ui.chip(host, "二维码登录", false) { host.startActivity(Intent(host, LoginActivity::class.java)) }.apply { tag = "account/login" })
        body.addView(account, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(host, 20); bottomMargin = Ui.dp(host, 24) })
        val actions = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val firstRow = LinearLayout(host)
        val secondRow = LinearLayout(host).apply { setPadding(0, Ui.dp(host, 12), 0, 0) }
        fun tile(key: String, title: String, subtitle: String, action: () -> Unit) {
            val button = Ui.button(host, "$title\n$subtitle", action).apply {
                tag = "account/$key"
                setTextSize(18f); gravity = Gravity.START or Gravity.CENTER_VERTICAL
                background = Ui.panel(context); Ui.focusHighlight(this)
                setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 12), 0)
            }
            val target = if (key == "favorites" || key == "history" || key == "watch-later") firstRow else secondRow
            target.addView(button, LinearLayout.LayoutParams(0, Ui.dp(host, 112), 1f).apply { marginEnd = Ui.dp(host, 12) })
        }
        tile("favorites", "▣  我的收藏", "按收藏夹发现喜欢的视频", openFavorites)
        tile("history", "◷  观看历史", "回看与继续播放", openHistory)
        tile("watch-later", "▤  稍后再看", "把想看的内容集中管理", openWatchLater)
        tile("following", "◎  我的关注", "浏览已关注的 UP 主", openFollowing)
        tile("settings", "⚙  设置", "布局、频道、播放与账号", openSettings)
        actions.addView(firstRow, LinearLayout.LayoutParams(-1, Ui.dp(host, 112)))
        actions.addView(secondRow, LinearLayout.LayoutParams(-1, Ui.dp(host, 124)))
        body.addView(actions)
        body.addView(Ui.text(host, "biliTv · 为平板与遥控器设计", 12f, Ui.muted))
        addView(body)
        if (ProgressRepository.get(host).account != "guest") AppExecutors.io.execute {
            val result = runCatching { BiliApiClient(host).getNavigation() }
            post {
                if (host.isDestroyed) return@post
                result.onSuccess { data ->
                    if (data.optBoolean("isLogin")) {
                        name.text = data.optString("uname", "已登录")
                        status.text = "已登录 · 云端历史与收藏可用"
                        CoverLoader.load(avatar, data.optString("face"))
                    } else status.text = "登录已失效，请重新扫码"
                }.onFailure { status.text = "暂时无法连接账号服务，可继续浏览本机内容" }
            }
        }
    }
}
