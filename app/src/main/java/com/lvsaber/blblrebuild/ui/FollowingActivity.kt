package com.lvsaber.blblrebuild.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.util.UnstableApi
import com.lvsaber.blblrebuild.core.AppExecutors
import com.lvsaber.blblrebuild.data.BiliApiClient
import com.lvsaber.blblrebuild.data.BiliApiException
import com.lvsaber.blblrebuild.data.UserProfile

/** A compact, remote-friendly directory for followed UPs. */
@UnstableApi
class FollowingActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private lateinit var back: android.widget.Button
    private val adapter = Users()
    private val users = linkedMapOf<Long, UserProfile>()
    private var page = 0
    private var hasMore = true
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = BiliApiClient(this)
        setContentView(buildPage())
        loadNext()
    }

    private fun buildPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 20), Ui.dp(context, 14), Ui.dp(context, 20), Ui.dp(context, 12))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        back = Ui.chip(this, "‹ 返回", false) { finish() }
        top.addView(back, LinearLayout.LayoutParams(-2, Ui.dp(this, 42)))
        top.addView(Ui.text(this, "我的关注", 23f).apply { setPadding(Ui.dp(context, 16), 0, 0, 0) }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        top.addView(Ui.chip(this, "刷新", false) { refresh() }, LinearLayout.LayoutParams(-2, Ui.dp(this, 42)))
        root.addView(top, LinearLayout.LayoutParams(-1, Ui.dp(this, 54)))
        status = Ui.text(this, "正在加载关注列表…", 14f, Ui.muted)
        root.addView(status, LinearLayout.LayoutParams(-1, Ui.dp(this, 38)))
        val columns = (Ui.videoColumns(this) / 2).coerceIn(2, 3)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@FollowingActivity, columns)
            adapter = this@FollowingActivity.adapter
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0 && (layoutManager as GridLayoutManager).findLastVisibleItemPosition() >= this@FollowingActivity.adapter.itemCount - columns) loadNext()
                }
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        back.setOnKeyListener { _, key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (adapter.itemCount > 0) grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() else grid.requestFocus()
                true
            } else false
        }
        return root
    }

    private fun refresh() {
        users.clear(); page = 0; hasMore = true; adapter.submitList(emptyList()); loadNext()
    }

    private fun loadNext() {
        if (loading || !hasMore) return
        loading = true
        val nextPage = page + 1
        status.text = if (users.isEmpty()) "正在加载关注列表…" else "正在加载更多…"
        AppExecutors.io.execute {
            val result = runCatching { api.getFollowingProfiles(nextPage, PAGE_SIZE) }
            runOnUiThread {
                loading = false
                result.onSuccess { loaded ->
                    page = nextPage
                    loaded.forEach { users[it.mid] = it }
                    hasMore = loaded.size >= PAGE_SIZE
                    adapter.submitList(users.values.toList())
                    status.text = if (users.isEmpty()) "还没有关注 UP 主" else "已关注 ${users.size} 位 UP 主${if (hasMore) " · 下移继续加载" else ""}"
                }.onFailure { status.text = friendlyMessage(it) }
            }
        }
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is BiliApiException -> "接口 ${error.apiCode}：${error.message}"
        else -> error.message ?: "加载失败"
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view)

    private inner class Users : RecyclerView.Adapter<Holder>() {
        private var items: List<UserProfile> = emptyList()

        fun submitList(next: List<UserProfile>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(UserCard(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, Ui.dp(parent.context, 112)).apply {
                setMargins(Ui.dp(parent.context, 5), Ui.dp(parent.context, 5), Ui.dp(parent.context, 5), Ui.dp(parent.context, 5))
            }
        })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val card = holder.itemView as UserCard
            val user = items[position]
            card.bind(user)
            card.setOnClickListener {
                startActivity(Intent(this@FollowingActivity, UpProfileActivity::class.java).putExtra(UpProfileActivity.EXTRA_MID, user.mid))
            }
            card.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val manager = grid.layoutManager as GridLayoutManager
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (position < manager.spanCount) { back.requestFocus(); true } else false
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (position >= itemCount - manager.spanCount && hasMore) { loadNext(); true } else false
                    else -> false
                }
            }
        }
    }

    private class UserCard(context: android.content.Context) : LinearLayout(context) {
        private val avatar = Ui.avatar(context)
        private val name = Ui.text(context, size = 16f)
        private val detail = Ui.text(context, size = 12f, color = Ui.muted)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
            addView(avatar, LayoutParams(Ui.dp(context, 68), Ui.dp(context, 68)))
            val labels = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(Ui.dp(context, 12), 0, 0, 0)
            }
            labels.addView(name, LayoutParams(-1, Ui.dp(context, 32)))
            labels.addView(detail, LayoutParams(-1, Ui.dp(context, 32)))
            addView(labels, LayoutParams(0, -2, 1f))
            addView(Ui.chip(context, "已关注", false) {}, LayoutParams(-2, Ui.dp(context, 38)))
            isFocusable = true
            isClickable = true
            Ui.focusHighlight(this)
        }

        fun bind(user: UserProfile) {
            name.text = user.name
            detail.text = when {
                user.liveStatus -> "正在直播"
                user.signature.isNotBlank() -> user.signature
                else -> "已关注 · 点击查看空间"
            }
            detail.maxLines = 2
            detail.ellipsize = android.text.TextUtils.TruncateAt.END
            CoverLoader.load(avatar, user.avatarUrl)
            contentDescription = "${user.name}，已关注"
        }
    }

    companion object { private const val PAGE_SIZE = 24 }
}
