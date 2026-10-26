package com.lvsaber.bilitv.ui

import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.ProgressRepository

@UnstableApi
class LibraryActivity : AppCompatActivity() {
    private lateinit var page: LibraryPage
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.background)
            setPadding(Ui.dp(context, 20), Ui.dp(context, 12), Ui.dp(context, 20), Ui.dp(context, 12))
        }
        root.addView(Ui.chip(this, "‹ 返回", false) { if (!page.backToFolders()) finish() })
        val mode = intent.getStringExtra(EXTRA_MODE)
        page = LibraryPage(this, BiliApiClient(this), favorites = mode == MODE_FAVORITES, watchLater = mode == MODE_WATCH_LATER)
        root.addView(page, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!page.backToFolders()) finish() }
        })
    }
    override fun onResume() {
        super.onResume()
        ProgressRepository.get(this).refreshAccount { changed ->
            if (!isDestroyed) { if (changed || page.accountKey != ProgressRepository.get(this).account) page.refresh() else page.refreshLocal(); VideoCard.refreshVisible(page) }
        }
    }
    override fun onDestroy() { page.dispose(); super.onDestroy() }
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_HISTORY = "history"
        const val MODE_FAVORITES = "favorites"
        const val MODE_WATCH_LATER = "watch_later"
    }
}
