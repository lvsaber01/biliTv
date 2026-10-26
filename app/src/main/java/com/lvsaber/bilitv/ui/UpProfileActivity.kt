package com.lvsaber.bilitv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.BiliApiException
import com.lvsaber.bilitv.data.ProgressRepository
import com.lvsaber.bilitv.data.UserProfile
import com.lvsaber.bilitv.data.VideoSummary

/**
 * TV-first UP profile. The header is kept outside the scrolling grid so the
 * identity, follow action and sort tabs remain reachable with the remote.
 */
@UnstableApi
class UpProfileActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var root: LinearLayout
    private lateinit var hero: LinearLayout
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private lateinit var latestTab: Button
    private lateinit var popularTab: Button
    private lateinit var followButton: Button
    private lateinit var backButton: Button
    private val adapter = Videos()
    private val videos = linkedMapOf<String, VideoSummary>()
    private var profile: UserProfile? = null
    private var mid = 0L
    private var order = "pubdate"
    private var page = 0
    private var hasMore = true
    private var loading = false
    private var generation = 0
    private var loginLaunched = false
    private var focusedBvid: String? = null
    private var focusedPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = BiliApiClient(this)
        mid = intent.getLongExtra(EXTRA_MID, 0L)
        setContentView(buildPage())
        if (mid <= 0L) {
            status.text = "缺少 UP 主信息"
            return
        }
        loadProfile()
    }

    override fun onPause() {
        saveFocusedVideo()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (loginLaunched) {
            loginLaunched = false
            loadProfile(refreshVideos = false)
        } else {
            restoreFocusedVideo()
        }
    }

    private fun buildPage(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 20), Ui.dp(context, 14), Ui.dp(context, 20), Ui.dp(context, 12))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        backButton = Ui.chip(this, "‹ 返回", false) { finish() }
        top.addView(backButton, LinearLayout.LayoutParams(-2, Ui.dp(this, 42)))
        top.addView(Ui.text(this, "UP 主空间", 23f).apply { setPadding(Ui.dp(context, 16), 0, 0, 0) }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        val refresh = Ui.chip(this, "刷新", false) { loadProfile() }
        top.addView(refresh, LinearLayout.LayoutParams(-2, Ui.dp(this, 42)))
        root.addView(top, LinearLayout.LayoutParams(-1, Ui.dp(this, 54)))

        hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14))
        }
        hero.addView(Ui.text(this, "正在读取 UP 主资料…", 15f, Ui.muted))
        root.addView(hero, LinearLayout.LayoutParams(-1, Ui.dp(this, 174)).apply { topMargin = Ui.dp(this@UpProfileActivity, 8) })

        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        latestTab = Ui.chip(this, "最新投稿", true) { switchOrder("pubdate") }
        popularTab = Ui.chip(this, "最多播放", false) { switchOrder("click") }
        tabs.addView(latestTab, LinearLayout.LayoutParams(-2, Ui.dp(this, 42)))
        tabs.addView(popularTab, LinearLayout.LayoutParams(-2, Ui.dp(this, 42)).apply { marginStart = Ui.dp(this@UpProfileActivity, 8) })
        root.addView(tabs, LinearLayout.LayoutParams(-1, Ui.dp(this, 52)).apply { topMargin = Ui.dp(this@UpProfileActivity, 8) })

        status = Ui.text(this, "", 13f, Ui.muted).apply { setPadding(Ui.dp(context, 4), 0, 0, 0) }
        root.addView(status, LinearLayout.LayoutParams(-1, Ui.dp(this, 28)))

        val columns = Ui.videoColumns(this)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@UpProfileActivity, columns)
            adapter = this@UpProfileActivity.adapter
            itemAnimator = null
            setHasFixedSize(false)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = layoutManager as? GridLayoutManager ?: return
                    if (dy > 0 && manager.findLastVisibleItemPosition() >= this@UpProfileActivity.adapter.itemCount - columns * 2) loadNext()
                }
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        Ui.focusHighlight(backButton)
        latestTab.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> focusFirstVideo()
                KeyEvent.KEYCODE_DPAD_UP -> followButton.requestFocus()
                else -> return@setOnKeyListener false
            }
            true
        }
        popularTab.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> focusFirstVideo()
                KeyEvent.KEYCODE_DPAD_UP -> if (::followButton.isInitialized) followButton.requestFocus()
                else -> return@setOnKeyListener false
            }
            true
        }
        backButton.setOnKeyListener { _, key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN) {
                followButton.requestFocus(); true
            } else false
        }
        return root
    }

    private fun loadProfile(refreshVideos: Boolean = true) {
        val token = ++generation
        status.text = "正在加载 UP 主资料…"
        AppExecutors.io.execute {
            val result = runCatching { api.getUserProfile(mid) }
            runOnUiThread {
                if (isDestroyed || token != generation) return@runOnUiThread
                result.onSuccess {
                    profile = it
                    renderProfile(it)
                    if (refreshVideos) {
                        videos.clear(); page = 0; hasMore = true; loadNext()
                    } else restoreFocusedVideo()
                }.onFailure { error -> status.text = friendlyMessage(error) }
            }
        }
    }

    private fun renderProfile(user: UserProfile) {
        hero.removeAllViews()
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val avatar = Ui.avatar(this)
        line.addView(avatar, LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 92)))
        CoverLoader.load(avatar, user.avatarUrl)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 12), 0)
        }
        val name = Ui.text(this, user.name, 22f).apply { setPadding(0, 0, 0, Ui.dp(context, 4)) }
        info.addView(name, LinearLayout.LayoutParams(-1, Ui.dp(this, 38)))
        val badge = buildString {
            if (user.verification.isNotBlank()) append("认证 · ${user.verification}")
            if (user.level > 0) {
                if (isNotEmpty()) append("   ")
                append("Lv.${user.level}")
            }
            if (user.liveStatus) {
                if (isNotEmpty()) append("   ")
                append("正在直播")
            }
        }.ifBlank { "哔哩哔哩用户" }
        info.addView(Ui.text(this, badge, 13f, if (user.liveStatus) Ui.pink else Ui.muted).apply { setPadding(0, 0, 0, Ui.dp(context, 4)) })
        info.addView(Ui.text(this, "关注 ${count(user.followingCount)}   粉丝 ${count(user.followerCount)}   投稿 ${count(user.videoCount)}", 13f, Ui.muted).apply {
            setPadding(0, 0, 0, Ui.dp(context, 4))
        })
        info.addView(Ui.text(this, user.signature.ifBlank { "这个 UP 主还没有填写简介" }, 13f, Ui.muted).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 0)
        })
        line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        followButton = Ui.button(this, followLabel(user)) { toggleFollow() }.apply {
            tag = "up/follow"
            minWidth = Ui.dp(context, 132)
            setTextSize(15f)
        }
        line.addView(followButton, LinearLayout.LayoutParams(Ui.dp(this, 142), Ui.dp(this, 48)))
        hero.addView(line, LinearLayout.LayoutParams(-1, -2, 1f))
        Ui.focusHighlight(followButton)
        followButton.setOnKeyListener { _, key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                backButton.requestFocus(); true
            } else if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN) {
                latestTab.requestFocus(); true
            } else false
        }
    }

    private fun switchOrder(next: String) {
        if (order == next && videos.isNotEmpty()) return
        order = next
        latestTab.background = Ui.roundedBackground(if (next == "pubdate") Ui.blue else Ui.surfaceRaised, Ui.dp(this, 17))
        popularTab.background = Ui.roundedBackground(if (next == "click") Ui.blue else Ui.surfaceRaised, Ui.dp(this, 17))
        videos.clear(); page = 0; hasMore = true; focusedBvid = null
        adapter.submitList(emptyList())
        loadNext()
    }

    private fun loadNext() {
        if (loading || !hasMore || mid <= 0L) return
        loading = true
        val requestedPage = page + 1
        val token = generation
        status.text = if (videos.isEmpty()) "正在读取投稿…" else "已加载 ${videos.size} 条，继续读取…"
        AppExecutors.io.execute {
            val result = runCatching { api.getUserVideos(mid, order, requestedPage, PAGE_SIZE) }
            runOnUiThread {
                if (isDestroyed || token != generation) return@runOnUiThread
                loading = false
                result.onSuccess { loaded ->
                    page = loaded.page
                    hasMore = loaded.hasMore
                    loaded.videos.forEach { videos[it.bvid] = it }
                    adapter.submitList(videos.values.toList()) {
                        restoreFocusedVideo()
                        if (videos.isEmpty()) focusFirstVideo()
                    }
                    status.text = if (videos.isEmpty()) "暂无投稿" else "${videos.size} 条投稿${if (hasMore) " · 下移继续加载" else " · 已到末尾"}"
                }.onFailure { error ->
                    status.text = if (videos.isEmpty()) friendlyMessage(error) else "加载失败，已加载内容仍可浏览"
                }
            }
        }
    }

    private fun toggleFollow() {
        val user = profile ?: return
        if (!api.hasSession() || ProgressRepository.get(this).account == "guest") {
            loginLaunched = true
            startActivity(Intent(this, LoginActivity::class.java))
            return
        }
        if (user.isFollowing) {
            AlertDialog.Builder(this)
                .setTitle("取消关注")
                .setMessage("确定不再关注 ${user.name}？")
                .setNegativeButton("保留关注", null)
                .setPositiveButton("取消关注") { _, _ -> updateFollow(false) }
                .show()
        } else updateFollow(true)
    }

    private fun updateFollow(follow: Boolean) {
        val user = profile ?: return
        followButton.isEnabled = false
        AppExecutors.io.execute {
            val result = runCatching { api.modifyRelation(user.mid, follow) }
            runOnUiThread {
                followButton.isEnabled = true
                result.onSuccess {
                    profile = user.copy(
                        isFollowing = follow,
                        followerCount = (user.followerCount + if (follow) 1 else -1).coerceAtLeast(0),
                    )
                    renderProfile(profile!!)
                    android.widget.Toast.makeText(this, if (follow) "已关注 ${user.name}" else "已取消关注", android.widget.Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    android.widget.Toast.makeText(this, friendlyMessage(error), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun focusFirstVideo() {
        if (adapter.itemCount == 0) return
        grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: grid.requestFocus() }
    }

    private fun saveFocusedVideo() {
        val holder = grid.takeIf { ::grid.isInitialized }?.findContainingViewHolder(grid.focusedChild ?: return)
        val position = holder?.bindingAdapterPosition ?: return
        val video = videos.values.elementAtOrNull(position) ?: return
        focusedBvid = video.bvid
        focusedPosition = position
    }

    private fun restoreFocusedVideo() {
        if (!::grid.isInitialized || adapter.itemCount == 0 || isFinishing || isDestroyed) return
        val position = focusedBvid?.let { bvid -> videos.keys.indexOf(bvid).takeIf { it >= 0 } } ?: focusedPosition
        if (position !in 0 until adapter.itemCount) return
        grid.post {
            (grid.layoutManager as? GridLayoutManager)?.scrollToPosition(position)
            grid.postOnAnimation { grid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
        }
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is BiliApiException -> "接口 ${error.apiCode}：${error.message}"
        else -> error.message ?: "加载失败"
    }

    private fun followLabel(user: UserProfile) = if (user.isFollowing) "已关注" else "＋ 关注"

    private fun count(value: Long): String = when {
        value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000f)
        value >= 10_000 -> "%.1f万".format(value / 10_000f)
        else -> value.toString()
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view)

    private inner class Videos : RecyclerView.Adapter<Holder>() {
        private var items: List<VideoSummary> = emptyList()

        fun submitList(next: List<VideoSummary>, onDone: () -> Unit = {}) {
            items = next
            notifyDataSetChanged()
            grid.post(onDone)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            VideoCard(parent.context, grid = true).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    setMargins(Ui.dp(parent.context, 5), Ui.dp(parent.context, 5), Ui.dp(parent.context, 5), Ui.dp(parent.context, 5))
                }
            },
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val bindingPosition = holder.bindingAdapterPosition
            if (bindingPosition == RecyclerView.NO_POSITION) return
            val card = holder.itemView as VideoCard
            val video = items[bindingPosition]
            card.bind(video)
            card.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val manager = grid.layoutManager as? GridLayoutManager
                val columns = manager?.spanCount ?: Ui.videoColumns(this@UpProfileActivity)
                val currentPosition = holder.bindingAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (currentPosition < columns) {
                        latestTab.requestFocus(); true
                    } else false
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (currentPosition >= itemCount - columns && hasMore) {
                        focusedPosition = currentPosition; loadNext(); true
                    } else false
                    else -> false
                }
            }
            card.setOnFocusChangeListener { view, focused ->
                Ui.applyFocusHighlight(view, focused)
                if (focused) {
                    focusedPosition = holder.bindingAdapterPosition
                    focusedBvid = video.bvid
                }
            }
        }
    }

    companion object {
        const val EXTRA_MID = "mid"
        private const val PAGE_SIZE = 24
    }
}
