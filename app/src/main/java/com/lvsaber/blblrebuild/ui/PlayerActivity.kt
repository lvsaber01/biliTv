package com.lvsaber.blblrebuild.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.lvsaber.blblrebuild.core.AppExecutors
import com.lvsaber.blblrebuild.data.AppSettings
import com.lvsaber.blblrebuild.data.AudioTrack
import com.lvsaber.blblrebuild.data.BiliApiClient
import com.lvsaber.blblrebuild.data.PlaySource

@UnstableApi
class PlayerActivity : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var danmakuOverlay: DanmakuOverlay
    private lateinit var api: BiliApiClient
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var errorView: android.widget.TextView? = null
    private lateinit var actionRail: LinearLayout
    private var currentBvid = ""
    private var currentCid = 0L
    private var currentTitle = ""
    private var currentSource: PlaySource? = null
    private val tickerHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (::danmakuOverlay.isInitialized) danmakuOverlay.positionMs = player?.currentPosition ?: 0L
            tickerHandler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        api = BiliApiClient(this)
        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        val cid = intent.getLongExtra(EXTRA_CID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        currentBvid = bvid
        currentCid = cid
        currentTitle = title
        setContentView(buildPage(title))
        danmakuOverlay.overlayEnabled = AppSettings.load(this).danmakuEnabled
        if (bvid.isBlank() || cid == 0L) {
            showError("缺少播放参数")
        } else {
            loadSource(bvid, cid)
            loadDanmaku(cid)
        }
    }

    private fun buildPage(title: String): View {
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            contentDescription = title
            keepScreenOn = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        danmakuOverlay = DanmakuOverlay(this)
        root.addView(danmakuOverlay, FrameLayout.LayoutParams(-1, -1))
        val titleView = Ui.text(this, title, 15f, android.graphics.Color.WHITE).apply {
            setBackgroundColor(0x99000000.toInt())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
        }
        val titleParams = FrameLayout.LayoutParams(-1, Ui.dp(this, 42))
        titleParams.gravity = android.view.Gravity.TOP
        root.addView(titleView, titleParams)
        actionRail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 4), Ui.dp(context, 12), Ui.dp(context, 4))
            setBackgroundColor(0x990B0E12.toInt())
        }
        actionRail.addView(actionButton("画质") { showQualityChooser() })
        actionRail.addView(actionButton("编码") { showCodecChooser() })
        actionRail.addView(actionButton("音轨") { showAudioChooser() })
        actionRail.addView(actionButton("倍速") { showSpeedChooser() })
        actionRail.addView(actionButton("弹幕") {
            danmakuOverlay.overlayEnabled = !danmakuOverlay.overlayEnabled
            AppSettings.load(this).copy(danmakuEnabled = danmakuOverlay.overlayEnabled).save(this)
            Toast.makeText(this, if (danmakuOverlay.overlayEnabled) "弹幕已开启" else "弹幕已关闭", Toast.LENGTH_SHORT).show()
        })
        actionRail.addView(actionButton("设置") {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        })
        val railScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actionRail, FrameLayout.LayoutParams(-2, Ui.dp(this@PlayerActivity, 50)))
        }
        val railParams = FrameLayout.LayoutParams(-1, Ui.dp(this, 50)).apply {
            gravity = android.view.Gravity.TOP
            topMargin = Ui.dp(this@PlayerActivity, 44)
        }
        root.addView(railScroll, railParams)
        return root
    }

    private fun loadSource(bvid: String, cid: Long) {
        AppExecutors.io.execute {
            val settings = AppSettings.load(this)
            runCatching { api.getPlaySource(bvid, cid, settings) }
                .onSuccess { source -> runOnUiThread {
                    currentSource = source
                    startPlayer(source)
                } }
                .onFailure { error -> runOnUiThread { showError(error.message ?: "播放地址获取失败") } }
        }
    }

    private fun startPlayer(source: PlaySource) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(CDN_USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.bilibili.com/",
                    "Origin" to "https://www.bilibili.com",
                    "Accept" to "*/*",
                ),
            )
        player?.release()
        trackSelector = DefaultTrackSelector(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) errorView?.visibility = View.GONE
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        showError("播放失败：${error.errorCodeName}\n请重试或切换清晰度")
                    }
                })
                val mediaSource = createMediaSource(mediaSourceFactory, source)
                if (mediaSource == null) {
                    showError("播放源为空，请切换画质或格式")
                    return@also
                }
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        tickerHandler.removeCallbacks(ticker)
        tickerHandler.post(ticker)
    }

    private fun createMediaSource(factory: DefaultMediaSourceFactory, source: PlaySource): MediaSource? {
        if (source.isDash && source.videoTrack != null && source.audioTrack != null) {
            val video = MediaItem.Builder()
                .setUri(source.videoTrack.url)
                .setMimeType(MimeTypes.VIDEO_MP4)
                .build()
            val audio = MediaItem.Builder()
                .setUri(source.audioTrack.url)
                .setMimeType(MimeTypes.AUDIO_MP4)
                .build()
            return MergingMediaSource(
                factory.createMediaSource(video),
                factory.createMediaSource(audio),
            )
        }
        if (source.url.isBlank()) return null
        return factory.createMediaSource(
            MediaItem.Builder().setUri(source.url).setMimeType(source.mimeType).build(),
        )
    }

    private fun actionButton(label: String, action: () -> Unit): View = Ui.chip(this, label, false, action).apply {
        Ui.focusHighlight(this)
        layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(this@PlayerActivity, 38)).apply {
            marginEnd = Ui.dp(this@PlayerActivity, 6)
        }
    }

    private fun showQualityChooser() {
        val options = listOf(16 to "360P", 32 to "480P", 64 to "720P", 80 to "1080P")
        val current = AppSettings.load(this).defaultQuality
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择默认/当前画质")
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), options.indexOfFirst { it.first == current }.coerceAtLeast(0)) { dialog, which ->
                val settings = AppSettings.load(this).copy(defaultQuality = options[which].first)
                settings.save(this)
                dialog.dismiss()
                loadSource(currentBvid, currentCid)
            }
            .show()
    }

    private fun showCodecChooser() {
        val options = AppSettings.VideoCodec.entries.toList()
        val current = AppSettings.load(this).videoCodec
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择视频编码")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                AppSettings.load(this).copy(videoCodec = options[which]).save(this)
                dialog.dismiss()
                loadSource(currentBvid, currentCid)
            }
            .show()
    }

    private fun showAudioChooser() {
        val tracks = currentSource?.availableAudio.orEmpty()
        if (tracks.isEmpty()) {
            Toast.makeText(this, "当前播放源没有可切换音轨", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = tracks.map { audioLabel(it) }.toTypedArray()
        val currentId = currentSource?.audioTrack?.id
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择音轨")
            .setSingleChoiceItems(labels, tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)) { dialog, which ->
                currentSource = currentSource?.copy(audioTrack = tracks[which])
                currentSource?.let(::startPlayer)
                dialog.dismiss()
            }
            .show()
    }

    private fun audioLabel(track: AudioTrack): String {
        val kbps = if (track.bandwidth > 0) " · ${track.bandwidth / 1000} kbps" else ""
        return "${track.codecs.ifBlank { "音频" }}$kbps"
    }

    private fun showSpeedChooser() {
        val options = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = options.map { "${it}x" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setItems(labels) { _, which -> player?.setPlaybackSpeed(options[which]) }
            .show()
    }

    private fun loadDanmaku(cid: Long) {
        AppExecutors.io.execute {
            runCatching { DanmakuOverlay.parseXml(api.getDanmakuXml(cid)) }
                .onSuccess { items -> runOnUiThread { danmakuOverlay.submit(items) } }
        }
    }

    private fun showError(message: String) {
        val root = playerView.parent as? FrameLayout ?: return
        val error = errorView ?: Ui.text(this, message, 17f, android.graphics.Color.WHITE).apply {
            setBackgroundColor(0xE611151B.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24))
            errorView = this
            root.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
        error.text = message
        error.visibility = View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        tickerHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val CDN_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val EXTRA_BVID = "bvid"
        const val EXTRA_CID = "cid"
        const val EXTRA_TITLE = "title"
    }
}
