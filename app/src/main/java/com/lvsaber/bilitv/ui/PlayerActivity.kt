package com.lvsaber.bilitv.ui

import android.content.Intent
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
import androidx.media3.ui.AspectRatioFrameLayout
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.AppSettings
import com.lvsaber.bilitv.data.AudioTrack
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.PlaySource
import com.lvsaber.bilitv.data.PlaybackDeviceCapabilities
import com.lvsaber.bilitv.data.PlaybackDiagnosticsSnapshot
import com.lvsaber.bilitv.data.PlaybackDiagnosticsStore
import com.lvsaber.bilitv.data.PlaybackQuality
import com.lvsaber.bilitv.data.PlaybackRouteSet
import com.lvsaber.bilitv.data.PlayerInfo
import com.lvsaber.bilitv.data.RouteAwareDataSource
import com.lvsaber.bilitv.data.SubtitleTrack
import com.lvsaber.bilitv.data.ProgressRepository
import com.lvsaber.bilitv.data.WatchProgress
import com.lvsaber.bilitv.data.VideoSummary
import com.lvsaber.bilitv.data.PgcCategory
import com.lvsaber.bilitv.data.PgcEpisode
import com.lvsaber.bilitv.data.PgcPlayableRef
import com.lvsaber.bilitv.data.PgcProgress
import com.lvsaber.bilitv.data.PgcSeasonDetail
import androidx.media3.datasource.HttpDataSource
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class PlayerActivity : AppCompatActivity() {
    private lateinit var pageRoot: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var danmakuOverlay: DanmakuOverlay
    private lateinit var subtitleOverlay: SubtitleOverlay
    private lateinit var danmakuButton: android.widget.Button
    private var danmakuState = "加载中"
    private var danmakuLoading = false
    private lateinit var api: BiliApiClient
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var errorView: LinearLayout? = null
    private var errorMessageView: android.widget.TextView? = null
    private var retryButton: android.widget.Button? = null
    private var lowerQualityButton: android.widget.Button? = null
    private var routeButton: android.widget.Button? = null
    private var codecFallbackButton: android.widget.Button? = null
    private lateinit var actionRail: LinearLayout
    private var chapterButton: android.widget.Button? = null
    private var pgcEpisodeButton: android.widget.Button? = null
    private var pgcPicker: PgcEpisodePickerView? = null
    private lateinit var controls: PlaybackControls
    private lateinit var deviceCapabilities: PlaybackDeviceCapabilities
    private var currentBvid = ""
    private var currentCid = 0L
    private var currentTitle = ""
    private var currentPgc: PgcPlayableRef? = null
    private var currentPgcDetail: PgcSeasonDetail? = null
    private var pendingPlaybackState: Boolean? = null
    private var pickerWasPlaying = false
    private var pgcContextGeneration = 0
    private var currentSource: PlaySource? = null
    private var resumePosition = 0L
    private var sourceReady = false
    private var loadGeneration = 0
    private var lastCheckpoint = 0L
    private var playbackAccount = "guest"
    private var playerInfo = PlayerInfo()
    private var subtitleLoading = false
    private var subtitleRequestGeneration = 0
    private var danmakuRequestGeneration = 0
    private var resumeOnForeground = true
    private var sourceRefreshAttempted = false
    private val routeRecoveryAttempted = linkedSetOf<String>()
    private var routeSwitchCount = 0
    private var lastRouteToastAt = 0L
    private var firstFrameStartedAt = 0L
    private var bufferingStartedAt = 0L
    private var bufferingCount = 0
    private var bufferingMs = 0L
    private var lastPlaybackState = Player.STATE_IDLE
    private val tickerHandler = Handler(Looper.getMainLooper())
    private val danmakuTicker = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                if (::danmakuOverlay.isInitialized) danmakuOverlay.positionMs = player?.currentPosition ?: 0L
                tickerHandler.postDelayed(this, 16L)
            }
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (::subtitleOverlay.isInitialized) subtitleOverlay.update(player?.currentPosition ?: 0L)
            updatePlaybackUi()
            if (android.os.SystemClock.uptimeMillis() - lastCheckpoint >= 5000) checkpoint()
            tickerHandler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        api = BiliApiClient(this)
        deviceCapabilities = PlaybackDeviceCapabilities.detect(this)
        val restored = savedInstanceState
        val bvid = restored?.getString(STATE_BVID) ?: intent.getStringExtra(EXTRA_BVID).orEmpty()
        val cid = restored?.getLong(STATE_CID, intent.getLongExtra(EXTRA_CID, 0L)) ?: intent.getLongExtra(EXTRA_CID, 0L)
        val title = restored?.getString(STATE_TITLE) ?: intent.getStringExtra(EXTRA_TITLE).orEmpty()
        currentBvid = bvid
        currentCid = cid
        currentTitle = title
        val pgcSeasonId = restored?.getLong(STATE_PGC_SEASON_ID, intent.getLongExtra(EXTRA_PGC_SEASON_ID, 0L))
            ?: intent.getLongExtra(EXTRA_PGC_SEASON_ID, 0L)
        val pgcEpisodeId = restored?.getLong(STATE_PGC_EPISODE_ID, intent.getLongExtra(EXTRA_PGC_EPISODE_ID, 0L))
            ?: intent.getLongExtra(EXTRA_PGC_EPISODE_ID, 0L)
        val pgcAreas = restored?.getStringArrayList(STATE_PGC_AREAS) ?: intent.getStringArrayListExtra(EXTRA_PGC_AREAS).orEmpty()
        currentPgc = if (pgcSeasonId > 0 && pgcEpisodeId > 0 && cid > 0) {
            PgcPlayableRef(pgcSeasonId, pgcEpisodeId, cid,
                restored?.getLong(STATE_PGC_AID, intent.getLongExtra(EXTRA_PGC_AID, 0L)) ?: intent.getLongExtra(EXTRA_PGC_AID, 0L), bvid,
                restored?.getString(STATE_PGC_SEASON_TITLE) ?: intent.getStringExtra(EXTRA_PGC_SEASON_TITLE).orEmpty(),
                restored?.getString(STATE_PGC_EPISODE_TITLE) ?: intent.getStringExtra(EXTRA_PGC_EPISODE_TITLE).orEmpty(),
                restored?.getString(STATE_COVER) ?: intent.getStringExtra("cover").orEmpty(),
                PgcCategory.fromSeasonType(restored?.getInt(STATE_PGC_CATEGORY, intent.getIntExtra(EXTRA_PGC_CATEGORY, 1))
                    ?: intent.getIntExtra(EXTRA_PGC_CATEGORY, 1)) ?: PgcCategory.JAPANESE_ANIME, pgcAreas)
        } else null
        resumePosition = restored?.getLong(STATE_POSITION) ?: intent.getLongExtra("start_ms", 0)
        playbackAccount = restored?.getString(STATE_ACCOUNT) ?: intent.getStringExtra("account") ?: ProgressRepository.get(this).account
        setContentView(buildPage(currentTitle))
        applyResizeMode()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pgcPicker != null) closePgcPicker(restorePlayback = true)
                else if (errorView?.visibility == View.VISIBLE) finish()
                else if (controls.controlsVisible) controls.hideControls() else finish()
            }
        })
        applyDanmakuSettings()
        updateDanmakuButton()
        if ((bvid.isBlank() && currentPgc == null) || cid == 0L) {
            showError("缺少播放参数")
        } else {
            loadSource(bvid, cid)
            loadDanmaku(cid)
            loadPlayerInfoForCurrent()
            if (currentPgc != null) loadPgcContext(currentPgc!!.seasonId, currentPgc!!.episodeId)
        }
    }

    private fun buildPage(title: String): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = true; isFocusableInTouchMode = true
            contentDescription = "视频画面"
        }
        pageRoot = root
        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            contentDescription = title
            keepScreenOn = true
            isFocusable = false
            setOnClickListener { controls.toggleVisibility() }
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDanmakuViewport()
        }
        danmakuOverlay = DanmakuOverlay(this)
        root.addView(danmakuOverlay, FrameLayout.LayoutParams(-1, -1))
        subtitleOverlay = SubtitleOverlay(this)
        root.addView(subtitleOverlay, FrameLayout.LayoutParams(-1, -1))
        actionRail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 4), Ui.dp(context, 12), Ui.dp(context, 4))
            setBackgroundColor(0x990B0E12.toInt())
        }
        actionRail.addView(actionButton("画质") { showQualityChooser() })
        pgcEpisodeButton = actionButton("选集") { showPgcEpisodePicker() }.also { it.visibility = if (currentPgc == null) View.GONE else View.VISIBLE }
        actionRail.addView(pgcEpisodeButton)
        actionRail.addView(actionButton("音轨") { showAudioChooser() })
        actionRail.addView(actionButton("倍速") { showSpeedChooser() })
        actionRail.addView(actionButton("字幕") { showSubtitleChooser() })
        val chapterAction = actionButton("章节") { showChapterChooser() }.apply { visibility = View.GONE }
        chapterButton = chapterAction
        actionRail.addView(chapterAction)
        actionRail.addView(actionButton("画面") { showResizeChooser() })
        actionRail.addView(actionButton("线路") { showLineInfo() })
        danmakuButton = actionButton("弹幕 · 加载中") {
            if (danmakuState == "失败" && danmakuOverlay.overlayEnabled) {
                loadDanmaku(currentCid)
                return@actionButton
            }
            danmakuOverlay.overlayEnabled = !danmakuOverlay.overlayEnabled
            AppSettings.load(this).copy(danmakuEnabled = danmakuOverlay.overlayEnabled).save(this)
            updateDanmakuButton()
            Toast.makeText(this, if (danmakuOverlay.overlayEnabled) "弹幕已开启" else "弹幕已关闭", Toast.LENGTH_SHORT).show()
        }
        actionRail.addView(danmakuButton)
        actionRail.addView(actionButton("弹幕设置") {
            startActivity(android.content.Intent(this, SettingsActivity::class.java).putExtra("section", "danmaku"))
        })
        actionRail.addView(actionButton("设置") {
            startActivity(android.content.Intent(this, SettingsActivity::class.java).putExtra("section", "playback"))
        })
        controls = PlaybackControls(this, title, actionRail,
            togglePlayback = {
                player?.let { exo ->
                    if (exo.playbackState == Player.STATE_ENDED) { exo.seekTo(0); exo.play() }
                    else exo.playWhenReady = !exo.playWhenReady
                }
                updatePlaybackUi()
            },
            seekPlayback = { target -> player?.seekTo(target); updatePlaybackUi() },
            focusVideo = { root.requestFocus() })
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        root.post { if (errorView?.visibility != View.VISIBLE && !isDestroyed) controls.showControls() }
        root.post { updateDanmakuViewport() }
        return root
    }

    private fun updateDanmakuViewport() {
        if (!::danmakuOverlay.isInitialized || !::playerView.isInitialized) return
        val surface = playerView.videoSurfaceView
        if (surface == null || surface.width <= 0 || surface.height <= 0) {
            danmakuOverlay.setVideoBounds(null)
            return
        }
        val overlayLocation = IntArray(2)
        val surfaceLocation = IntArray(2)
        danmakuOverlay.getLocationOnScreen(overlayLocation)
        surface.getLocationOnScreen(surfaceLocation)
        danmakuOverlay.setVideoBounds(android.graphics.RectF(
            (surfaceLocation[0] - overlayLocation[0]).toFloat(),
            (surfaceLocation[1] - overlayLocation[1]).toFloat(),
            (surfaceLocation[0] - overlayLocation[0] + surface.width).toFloat(),
            (surfaceLocation[1] - overlayLocation[1] + surface.height).toFloat(),
        ))
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (pgcPicker != null && event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode in listOf(
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
            )) {
            val picker = pgcPicker ?: return true
            val focused = currentFocus
            if (focused == null || !isDescendantOf(focused, picker)) {
                picker.focusInitial()
                return true
            }
        }
        if (pgcPicker == null && ::controls.isInitialized && errorView?.visibility != View.VISIBLE && controls.handleKey(event, currentFocus)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun updatePlaybackUi() {
        if (!::controls.isInitialized) return
        val exo = player
        controls.update(exo?.currentPosition ?: resumePosition, exo?.duration ?: 0,
            exo?.playWhenReady == true && exo.playbackState != Player.STATE_ENDED, sourceReady)
    }

    private fun loadSource(bvid: String, cid: Long, automaticRefresh: Boolean = false) {
        if (!automaticRefresh) {
            sourceRefreshAttempted = false
            routeRecoveryAttempted.clear()
        }
        checkpoint()
        val position = player?.currentPosition?.takeIf { sourceReady } ?: resumePosition
        val generation = ++loadGeneration
        val accepted = AppExecutors.playback {
            val settings = AppSettings.load(this)
            val currentPgc = currentPgc
            runCatching {
                if (currentPgc != null) api.getPgcPlaySource(currentPgc, settings, deviceCapabilities)
                else api.getPlaySource(bvid, cid, settings, deviceCapabilities)
            }
                .onSuccess { source -> runOnUiThread {
                    if (isDestroyed || generation != loadGeneration) return@runOnUiThread
                    currentSource = source
                    resumePosition = position
                    startPlayer(source)
                } }
                .onFailure { error -> runOnUiThread { if (!isDestroyed && generation == loadGeneration) showError(error.message ?: "播放地址获取失败") } }
        }
        if (!accepted) showError("播放请求繁忙，请稍后重试或返回")
    }

    private fun loadPlayerInfo(bvid: String, cid: Long) {
        AppExecutors.io.execute {
            val result = runCatching { api.getPlayerInfo(bvid, cid) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess(::setPlayerInfo)
            }
        }
    }

    private fun setPlayerInfo(info: PlayerInfo) {
        playerInfo = info
        chapterButton?.visibility = if (info.chapters.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadPlayerInfoForCurrent() {
        val pgc = currentPgc
        playerInfo = PlayerInfo()
        chapterButton?.visibility = View.GONE
        subtitleOverlay.overlayEnabled = false
        subtitleOverlay.submit(emptyList(), AppSettings.load(this).subtitleTextScale)
        subtitleRequestGeneration++
        subtitleLoading = false
        if (pgc == null) loadPlayerInfo(currentBvid, currentCid)
        else AppExecutors.io.execute {
            val result = runCatching { api.getPgcPlayerInfo(pgc) }
            runOnUiThread {
                if (!isDestroyed && currentPgc?.episodeId == pgc.episodeId) result.onSuccess(::setPlayerInfo)
            }
        }
    }

    private fun loadPgcContext(seasonId: Long, episodeId: Long) {
        val token = ++pgcContextGeneration
        AppExecutors.io.execute {
            val result = runCatching { api.getPgcSeasonDetail(seasonId) }
            runOnUiThread {
                if (isDestroyed || token != pgcContextGeneration || currentPgc?.seasonId != seasonId || currentPgc?.episodeId != episodeId) return@runOnUiThread
                result.onSuccess { currentPgcDetail = it }
            }
        }
    }

    private fun showPgcEpisodePicker() {
        val current = currentPgc ?: return
        if (pgcPicker != null || errorView?.visibility == View.VISIBLE) return
        pickerWasPlaying = player?.playWhenReady == true
        player?.pause()
        controls.beginDialog()
        val picker = PgcEpisodePickerView(this, api, current,
            onClose = { closePgcPicker(restorePlayback = true) },
            onSelect = { playable -> selectPgcEpisode(playable, pickerWasPlaying) })
        pgcPicker = picker
        pageRoot.addView(picker, FrameLayout.LayoutParams(-1, -1))
        pageRoot.requestLayout()
        picker.bringToFront()
        picker.requestFocus()
        picker.focusInitial()
    }

    private fun closePgcPicker(restorePlayback: Boolean) {
        val picker = pgcPicker ?: return
        pgcPicker = null
        picker.dispose()
        pageRoot.removeView(picker)
        controls.endDialog()
        pgcEpisodeButton?.requestFocus()
        if (restorePlayback && pickerWasPlaying) player?.play()
    }

    private fun selectPgcEpisode(playable: PgcPlayableRef, shouldPlay: Boolean) {
        checkpoint()
        pendingPlaybackState = shouldPlay
        currentPgc = playable
        currentBvid = playable.bvid
        currentCid = playable.cid
        currentTitle = playable.displayTitle
        resumePosition = ProgressRepository.get(this).findPgc(playable.episodeId)?.takeIf { it.canResume }?.positionMs ?: 0L
        sourceReady = false
        currentSource = null
        player?.pause()
        controls.updateTitle(currentTitle)
        pgcEpisodeButton?.visibility = View.VISIBLE
        closePgcPicker(restorePlayback = false)
        danmakuRequestGeneration++
        danmakuLoading = false
        danmakuOverlay.submit(emptyList())
        danmakuState = "加载中"
        subtitleOverlay.submit(emptyList(), AppSettings.load(this).subtitleTextScale)
        loadSource(currentBvid, currentCid)
        loadDanmaku(currentCid)
        loadPlayerInfoForCurrent()
        loadPgcContext(playable.seasonId, playable.episodeId)
    }

    private fun playNextPgcEpisode(): Boolean {
        val current = currentPgc ?: return false
        val episodes = currentPgcDetail?.takeIf { it.season.seasonId == current.seasonId }?.episodes.orEmpty()
        val index = episodes.indexOfFirst { it.episodeId == current.episodeId }
        val next = episodes.getOrNull(index + 1) ?: return false
        val season = currentPgcDetail?.season ?: return false
        selectPgcEpisode(PgcPlayableRef(season.seasonId, next.episodeId, next.cid, next.aid, next.bvid,
            season.title, next.displayTitle, next.coverUrl.ifBlank { season.coverUrl }, season.category, season.areas), shouldPlay = true)
        return true
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (node === ancestor) return true
            node = node.parent as? View
        }
        return false
    }

    private fun startPlayer(source: PlaySource) {
        if (sourceReady) resumePosition = player?.currentPosition ?: resumePosition
        val keepPlaying = pendingPlaybackState ?: player?.playWhenReady ?: resumeOnForeground
        pendingPlaybackState = null
        checkpoint()
        val settings = AppSettings.load(this)
        val restrictToSingleRoute = !settings.autoRouteSwitchEnabled || settings.routePolicy == AppSettings.RoutePolicy.PRIMARY_ONLY
        val activeRoutes = if (restrictToSingleRoute) {
            source.routeSet.selectedRoute?.let { source.routeSet.copy(routes = listOf(it), selectedRouteId = it.id) }
        } else {
            source.routeSet
        } ?: PlaybackRouteSet()
        val effectiveSource = source.copy(routeSet = activeRoutes)
        effectiveSource.routeSet.selectedRoute?.id?.let { routeRecoveryAttempted += it }
        val routeState = AtomicReference(activeRoutes)
        PlaybackDiagnosticsStore.setRouteSet(activeRoutes)
        PlaybackDiagnosticsStore.update(PlaybackDiagnosticsSnapshot(
            routeId = activeRoutes.selectedRoute?.id,
            routeHost = activeRoutes.selectedRoute?.displayHost ?: "未选择",
            bufferingCount = 0,
            bufferingMs = 0L,
            switchCount = routeSwitchCount,
        ))
        firstFrameStartedAt = android.os.SystemClock.elapsedRealtime()
        val delegateFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(CDN_USER_AGENT)
            .setConnectTimeoutMs(3_000)
            .setReadTimeoutMs(5_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.bilibili.com/",
                    "Origin" to "https://www.bilibili.com",
                    "Accept" to "*/*",
                ),
            )
        val dataSourceFactory = RouteAwareDataSource.Factory(
            delegateFactory,
            routeState,
            onRouteSwitched = { _, candidate, _ ->
                val route = routeState.get().routeForUrl(candidate)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    routeSwitchCount++
                    route?.let {
                        routeRecoveryAttempted += it.id
                        currentSource = currentSource?.copy(routeSet = currentSource?.routeSet?.select(it.id) ?: routeState.get().select(it.id))
                        PlaybackDiagnosticsStore.setRouteSet(currentSource?.routeSet ?: routeState.get())
                        PlaybackDiagnosticsStore.recordRoute(it.id, it.displayHost, routeSwitchCount)
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastRouteToastAt > 1_000L) {
                        lastRouteToastAt = now
                        Toast.makeText(this, "已切换至${route?.displayName ?: "备用线路"}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            promoteRoute = settings.routePolicy == AppSettings.RoutePolicy.AUTO,
        )
        player?.release()
        sourceReady = false
        trackSelector = DefaultTrackSelector(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        danmakuOverlay.videoAspectRatio = if (videoSize.height > 0) videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height else 0f
                        playerView.post { updateDanmakuViewport() }
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (lastPlaybackState != Player.STATE_BUFFERING && playbackState == Player.STATE_BUFFERING) {
                            bufferingCount++
                            bufferingStartedAt = android.os.SystemClock.elapsedRealtime()
                        } else if (lastPlaybackState == Player.STATE_BUFFERING && playbackState != Player.STATE_BUFFERING && bufferingStartedAt > 0L) {
                            bufferingMs += (android.os.SystemClock.elapsedRealtime() - bufferingStartedAt).coerceAtLeast(0L)
                            bufferingStartedAt = 0L
                        }
                        lastPlaybackState = playbackState
                        val active = routeState.get().selectedRoute
                        PlaybackDiagnosticsStore.update(PlaybackDiagnosticsStore.snapshot().copy(
                            routeId = active?.id,
                            routeHost = active?.displayHost ?: "未选择",
                            bufferingCount = bufferingCount,
                            bufferingMs = bufferingMs,
                            switchCount = routeSwitchCount,
                        ))
                        if (playbackState == Player.STATE_READY) {
                            sourceReady = true
                            sourceRefreshAttempted = false
                            val recovering = errorView?.visibility == View.VISIBLE
                            errorView?.visibility = View.GONE
                            if (recovering) controls.resumeControls()
                            if (exoPlayer.duration > 0 && exoPlayer.currentPosition >= exoPlayer.duration) exoPlayer.seekTo(0)
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            if (!playNextPgcEpisode()) {
                                checkpoint(completed = true)
                                if (currentPgc != null) Toast.makeText(this@PlayerActivity, "本季已结束，可打开选集面板选择下一季", Toast.LENGTH_LONG).show()
                            }
                        }
                        updatePlaybackUi()
                    }

                    override fun onRenderedFirstFrame() {
                        val elapsed = (android.os.SystemClock.elapsedRealtime() - firstFrameStartedAt).coerceAtLeast(0L)
                        val selected = routeState.get().selectedRoute
                        PlaybackDiagnosticsStore.update(PlaybackDiagnosticsSnapshot(
                            routeId = selected?.id,
                            routeHost = selected?.displayHost ?: "未选择",
                            firstFrameMs = elapsed,
                            bufferingCount = bufferingCount,
                            bufferingMs = bufferingMs,
                            switchCount = routeSwitchCount,
                        ))
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { updatePlaybackUi() }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) checkpoint(); updatePlaybackUi() }

                    override fun onPlayerError(error: PlaybackException) {
                        PlaybackDiagnosticsStore.update(PlaybackDiagnosticsSnapshot(
                            routeId = routeState.get().selectedRoute?.id,
                            routeHost = routeState.get().selectedRoute?.displayHost ?: "未选择",
                            firstFrameMs = PlaybackDiagnosticsStore.snapshot().firstFrameMs,
                            bufferingCount = bufferingCount,
                            bufferingMs = bufferingMs,
                            switchCount = routeSwitchCount,
                            lastError = "${error.errorCodeName}: ${error.message.orEmpty()}".take(180),
                        ))
                        val settings = AppSettings.load(this@PlayerActivity)
                        if (settings.autoRouteSwitchEnabled && isRouteRecoverableError(error)) {
                            if (tryNextRouteRecovery()) return
                            if (!sourceRefreshAttempted) {
                                sourceRefreshAttempted = true
                                Toast.makeText(this@PlayerActivity, "线路均不可用，正在刷新播放地址…", Toast.LENGTH_SHORT).show()
                                loadSource(currentBvid, currentCid, automaticRefresh = true)
                                return
                            }
                        }
                        val codec = settings.videoCodec
                        val codecHint = if (codec != AppSettings.VideoCodec.AUTO && !isNetworkPlaybackError(error)) {
                            "\n当前指定编码为${codec.label}，可切换为自动编码后重试。"
                        } else ""
                        showError("播放失败：${error.errorCodeName}$codecHint\n请重试或切换清晰度")
                    }
                })
                val mediaSource = createMediaSource(mediaSourceFactory, effectiveSource)
                if (mediaSource == null) {
                    showError("播放源为空，请切换画质或格式")
                    return@also
                }
                exoPlayer.setMediaSource(mediaSource)
                if (resumePosition > 0) exoPlayer.seekTo(resumePosition)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && keepPlaying
            }
        tickerHandler.removeCallbacks(ticker)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) tickerHandler.post(ticker)
    }

    private fun createMediaSource(factory: DefaultMediaSourceFactory, source: PlaySource): MediaSource? {
        val selectedRoute = source.routeSet.selectedRoute
        if (source.isDash && source.videoTrack != null && source.audioTrack != null) {
            val video = MediaItem.Builder()
                .setUri(selectedRoute?.videoUrl ?: source.videoTrack.url)
                .setMimeType(MimeTypes.VIDEO_MP4)
                .build()
            val audio = MediaItem.Builder()
                .setUri(selectedRoute?.audioUrl ?: source.audioTrack.url)
                .setMimeType(MimeTypes.AUDIO_MP4)
                .build()
            return MergingMediaSource(
                factory.createMediaSource(video),
                factory.createMediaSource(audio),
            )
        }
        val url = selectedRoute?.videoUrl ?: source.url
        if (url.isBlank()) return null
        return factory.createMediaSource(
            MediaItem.Builder().setUri(url).setMimeType(source.mimeType).build(),
        )
    }

    private fun actionButton(label: String, action: () -> Unit): android.widget.Button = Ui.chip(this, label, false) {}.apply {
        setOnClickListener { controls.beforeAction(this); action() }
        Ui.focusHighlight(this)
        layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(this@PlayerActivity, 38)).apply {
            marginEnd = Ui.dp(this@PlayerActivity, 6)
        }
    }

    private fun showQualityChooser() {
        val settings = AppSettings.load(this)
        val allOptions = currentSource?.availableQualities.orEmpty()
        // The player chooses resolution only. Experimental, unverified tracks are
        // shown only when the user explicitly selected a codec; AUTO must never
        // silently choose an AV1/software-only track just because it is larger.
        val options = allOptions.filter {
            it.deviceSupported || (settings.experimentalQualityEnabled && settings.videoCodec != AppSettings.VideoCodec.AUTO)
        }
        if (options.isEmpty()) {
            Toast.makeText(this, "当前播放源没有可切换画质，请先重试获取播放能力", Toast.LENGTH_SHORT).show()
            return
        }
        val current = currentSource?.videoTrack?.id ?: settings.defaultQuality
        val labels = options.map(::qualityOptionLabel).toTypedArray()
        showControlDialog(androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择当前画质")
            .setSingleChoiceItems(labels, options.indexOfFirst { it.id == current }.coerceAtLeast(0)) { dialog, which ->
                AppSettings.load(this).copy(defaultQuality = options[which].id).save(this)
                dialog.dismiss()
                loadSource(currentBvid, currentCid)
            }
            .create())
    }

    private fun qualityOptionLabel(option: PlaybackQuality): String {
        val tags = buildList {
            if (option.isDolbyVision) add("Dolby Vision") else if (option.isHdr) add("HDR")
            if (!option.deviceSupported) add("实验性")
            if (option.needsVip) add("大会员") else if (option.needsLogin) add("需登录")
        }
        val base = option.label.ifBlank { "画质 ${option.id}" }
        return if (tags.isEmpty()) base else "$base · ${tags.joinToString("/")}"
    }

    private fun showAudioChooser() {
        val tracks = currentSource?.availableAudio.orEmpty()
        if (tracks.isEmpty()) {
            Toast.makeText(this, "当前播放源没有可切换音轨", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = tracks.map { audioLabel(it) }.toTypedArray()
        val currentId = currentSource?.audioTrack?.id
        showControlDialog(androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择音轨")
            .setSingleChoiceItems(labels, tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)) { dialog, which ->
                val source = currentSource
                val selected = tracks[which]
                if (source != null && source.videoTrack != null) {
                    val routeSet = PlaybackRouteSet.fromUrls(
                        listOf(source.videoTrack.url) + source.videoTrack.backupUrls,
                        listOf(selected.url) + selected.backupUrls,
                    ).select(source.routeSet.selectedRoute?.id ?: "route-0")
                    currentSource = source.copy(audioTrack = selected, routeSet = routeSet)
                }
                currentSource?.let(::startPlayer)
                dialog.dismiss()
            }
            .create())
    }

    private fun audioLabel(track: AudioTrack): String {
        val kbps = if (track.bandwidth > 0) " · ${track.bandwidth / 1000} kbps" else ""
        return "${track.codecs.ifBlank { "音频" }}$kbps"
    }

    private fun showSpeedChooser() {
        val options = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = options.map { "${it}x" }.toTypedArray()
        showControlDialog(androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setItems(labels) { _, which -> player?.setPlaybackSpeed(options[which]) }
            .create())
    }

    private fun showSubtitleChooser() {
        val tracks = playerInfo.subtitles
        if (tracks.isEmpty()) {
            Toast.makeText(this, "当前视频没有可用字幕", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = listOf("关闭字幕") + tracks.map { subtitleLabel(it) }
        val selected = if (subtitleOverlay.overlayEnabled) 1 else 0
        showControlDialog(androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("字幕")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                if (which == 0) {
                    subtitleOverlay.overlayEnabled = false
                    dialog.dismiss()
                } else {
                    loadSubtitle(tracks[which - 1])
                    dialog.dismiss()
                }
            }
            .create())
    }

    private fun subtitleLabel(track: SubtitleTrack): String =
        track.label.ifBlank { track.language.ifBlank { "字幕" } }

    private fun loadSubtitle(track: SubtitleTrack) {
        if (subtitleLoading) return
        val token = ++subtitleRequestGeneration
        subtitleLoading = true
        Toast.makeText(this, "正在加载${subtitleLabel(track)}…", Toast.LENGTH_SHORT).show()
        AppExecutors.io.execute {
            val result = runCatching { api.getSubtitleCues(track.url) }
            runOnUiThread {
                if (isDestroyed || token != subtitleRequestGeneration) return@runOnUiThread
                subtitleLoading = false
                result.onSuccess { cues ->
                    subtitleOverlay.submit(cues, AppSettings.load(this).subtitleTextScale)
                    subtitleOverlay.overlayEnabled = cues.isNotEmpty()
                    Toast.makeText(this, if (cues.isEmpty()) "字幕内容为空" else "字幕已开启", Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(this, "字幕加载失败", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showChapterChooser() {
        val chapters = playerInfo.chapters
        if (chapters.isEmpty()) {
            Toast.makeText(this, "当前视频没有章节或看点", Toast.LENGTH_SHORT).show()
            return
        }
        showControlDialog(androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("章节 / 看点")
            .setItems(chapters.map { "${WatchProgress.formatPosition(it.startMs)}  ${it.title}" }.toTypedArray()) { _, which ->
                player?.seekTo(chapters[which].startMs)
                updatePlaybackUi()
            }
            .create())
    }

    private fun showResizeChooser() {
        val options = AppSettings.VideoResizeMode.entries.toList()
        val current = AppSettings.load(this).videoResizeMode
        showControlDialog(androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("画面比例")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                val selected = options[which]
                AppSettings.load(this).copy(videoResizeMode = selected).save(this)
                applyResizeMode(selected)
                dialog.dismiss()
            }
            .create())
    }

    private fun applyResizeMode(mode: AppSettings.VideoResizeMode = AppSettings.load(this).videoResizeMode) {
        playerView.resizeMode = when (mode) {
            AppSettings.VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AppSettings.VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AppSettings.VideoResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    private fun showLineInfo() {
        val source = currentSource
        val routes = source?.routeSet?.routes.orEmpty()
        val settings = AppSettings.load(this)
        val visibleRoutes = if (settings.routePolicy == AppSettings.RoutePolicy.PRIMARY_ONLY) routes.take(1) else routes
        val currentId = source?.routeSet?.selectedRoute?.id
        val probes = PlaybackDiagnosticsStore.probes()
        val labels = visibleRoutes.map { route ->
            val probe = probes[route.id]
            val status = probe?.let { " · ${if (it.success) it.video.grade else "失败"}" }.orEmpty()
            "${route.displayName}$status${if (route.id == currentId) " · 当前" else ""}"
        }.toTypedArray()
        val builder = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("播放线路")
            .setNegativeButton("网络诊断") { _, _ -> startActivity(android.content.Intent(this, NetworkDiagnosticsActivity::class.java)) }
            .setPositiveButton("关闭", null)
        if (visibleRoutes.isEmpty()) {
            builder.setMessage("当前播放源没有返回可用线路。\n\n可打开网络诊断查看本次播放会话，或重试获取新的播放地址。")
        } else {
            builder.setSingleChoiceItems(labels, visibleRoutes.indexOfFirst { it.id == currentId }.coerceAtLeast(0)) { dialog, which ->
                val selected = visibleRoutes[which]
                val current = currentSource
                if (current != null) {
                    sourceRefreshAttempted = false
                    currentSource = current.copy(routeSet = current.routeSet.select(selected.id))
                    PlaybackDiagnosticsStore.setRouteSet(currentSource!!.routeSet)
                    startPlayer(currentSource!!)
                }
                dialog.dismiss()
            }
        }
        showControlDialog(builder.create())
    }

    private fun isNetworkPlaybackError(error: PlaybackException): Boolean {
        if (error.errorCode in setOf(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            )) return true
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.HttpDataSourceException ||
                cause is SocketTimeoutException || cause is UnknownHostException || cause is ConnectException
            ) return true
            cause = cause.cause
        }
        return false
    }

    /** Route-level recovery also covers a malformed segment/manifest from one CDN, but never decoder errors. */
    private fun isRouteRecoverableError(error: PlaybackException): Boolean =
        isNetworkPlaybackError(error) ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

    private fun tryNextRouteRecovery(): Boolean {
        val source = currentSource ?: return false
        val routes = source.routeSet.routes
        if (routes.size < 2) return false
        val currentId = source.routeSet.selectedRoute?.id
        currentId?.let { routeRecoveryAttempted += it }
        val next = routes.firstOrNull { it.id != currentId && it.id !in routeRecoveryAttempted } ?: return false
        routeRecoveryAttempted += next.id
        currentSource = source.copy(routeSet = source.routeSet.select(next.id))
        Toast.makeText(this, "当前线路异常，正在切换至${next.displayName}…", Toast.LENGTH_SHORT).show()
        startPlayer(currentSource!!)
        return true
    }

    private fun showControlDialog(dialog: androidx.appcompat.app.AlertDialog) {
        controls.beginDialog()
        dialog.setOnDismissListener { if (!isDestroyed) controls.endDialog() }
        dialog.show()
    }

    private fun loadDanmaku(cid: Long) {
        val token = ++danmakuRequestGeneration
        danmakuLoading = true; danmakuState = "加载中"; updateDanmakuButton()
        val accepted = AppExecutors.playback {
            val result = runCatching { DanmakuOverlay.parseXml(api.getDanmakuXml(cid)) }
            runOnUiThread {
                if (isDestroyed || isFinishing || token != danmakuRequestGeneration || cid != currentCid) return@runOnUiThread
                danmakuLoading = false
                result.onSuccess { items ->
                    danmakuOverlay.submit(items)
                    danmakuState = if (items.isEmpty()) "暂无" else "开"
                }.onFailure {
                    danmakuState = "失败"
                    Toast.makeText(this, "弹幕加载失败，点击弹幕按钮重试", Toast.LENGTH_LONG).show()
                }
                updateDanmakuButton()
            }
        }
        if (!accepted && token == danmakuRequestGeneration) { danmakuLoading = false; danmakuState = "失败"; updateDanmakuButton() }
    }

    private fun updateDanmakuButton() {
        if (!::danmakuButton.isInitialized) return
        val enabled = danmakuOverlay.overlayEnabled
        danmakuButton.text = "弹幕 · ${if (enabled) danmakuState else "关"}"
        danmakuButton.contentDescription = if (enabled && danmakuState == "失败") "弹幕加载失败，点击重试" else danmakuButton.text
        danmakuButton.background = Ui.roundedBackground(if (enabled) Ui.blue else Ui.surfaceRaised, Ui.dp(this, 8))
        danmakuButton.setTextColor(if (enabled) Ui.text else Ui.muted)
        danmakuButton.isSelected = enabled
    }

    private fun applyDanmakuSettings() {
        val settings = AppSettings.load(this)
        danmakuOverlay.overlayEnabled = settings.danmakuEnabled
        danmakuOverlay.displaySettings = settings.danmaku
        if (!subtitleOverlay.overlayEnabled) subtitleOverlay.submit(emptyList(), settings.subtitleTextScale)
    }

    private fun showError(message: String) {
        controls.suspendControls()
        val root = playerView.parent as? FrameLayout ?: return
        val panel = errorView ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xE611151B.toInt())
            setPadding(Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24))
            errorView = this
            errorMessageView = Ui.text(this@PlayerActivity, "", 17f, android.graphics.Color.WHITE).apply {
                gravity = android.view.Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
            addView(errorMessageView, LinearLayout.LayoutParams(-1, -2))
            val actions = LinearLayout(this@PlayerActivity).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, Ui.dp(context, 18), 0, 0)
            }
            retryButton = Ui.chip(this@PlayerActivity, "重试", true) {
                visibility = View.GONE
                controls.resumeControls()
                loadSource(currentBvid, currentCid)
            }.also { button ->
                Ui.focusHighlight(button)
                actions.addView(button, LinearLayout.LayoutParams(Ui.dp(this@PlayerActivity, 128), Ui.dp(this@PlayerActivity, 46)).apply {
                    marginEnd = Ui.dp(this@PlayerActivity, 12)
                })
            }
            lowerQualityButton = Ui.chip(this@PlayerActivity, "切换低一级", false) {
                val fallback = lowerQualityOption()
                if (fallback == null) {
                    Toast.makeText(this@PlayerActivity, "没有检测到更低的可用画质", Toast.LENGTH_SHORT).show()
                } else {
                    AppSettings.load(this@PlayerActivity).copy(defaultQuality = fallback.id).save(this@PlayerActivity)
                    visibility = View.GONE
                    controls.resumeControls()
                    loadSource(currentBvid, currentCid)
                }
            }.also { button ->
                Ui.focusHighlight(button)
                actions.addView(button, LinearLayout.LayoutParams(Ui.dp(this@PlayerActivity, 160), Ui.dp(this@PlayerActivity, 46)))
            }
            routeButton = Ui.chip(this@PlayerActivity, "选择线路", false) {
                showLineInfo()
            }.also { button ->
                Ui.focusHighlight(button)
                actions.addView(button, LinearLayout.LayoutParams(Ui.dp(this@PlayerActivity, 128), Ui.dp(this@PlayerActivity, 46)))
            }
            codecFallbackButton = Ui.chip(this@PlayerActivity, "切换自动编码", false) {
                AppSettings.load(this@PlayerActivity).copy(videoCodec = AppSettings.VideoCodec.AUTO).save(this@PlayerActivity)
                visibility = View.GONE
                controls.resumeControls()
                loadSource(currentBvid, currentCid)
            }.also { button ->
                Ui.focusHighlight(button)
                actions.addView(button, LinearLayout.LayoutParams(Ui.dp(this@PlayerActivity, 172), Ui.dp(this@PlayerActivity, 46)))
            }
            addView(actions, LinearLayout.LayoutParams(-1, -2))
            root.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
        errorMessageView?.text = "$message\n\n可重试当前画质；如果画面黑屏或解码失败，请切换到低一级画质。返回键退出播放。"
        lowerQualityButton?.visibility = if (lowerQualityOption() == null) View.GONE else View.VISIBLE
        routeButton?.visibility = if (currentSource?.routeSet?.hasAlternates == true) View.VISIBLE else View.GONE
        codecFallbackButton?.visibility = if (AppSettings.load(this).videoCodec != AppSettings.VideoCodec.AUTO) View.VISIBLE else View.GONE
        panel.visibility = View.VISIBLE
        retryButton?.requestFocus()
    }

    private fun lowerQualityOption(): PlaybackQuality? {
        val source = currentSource
        val currentId = source?.videoTrack?.id ?: AppSettings.load(this).defaultQuality
        val available = source?.availableQualities.orEmpty()
            .filter { it.deviceSupported && (currentId <= 0 || it.id != currentId) }
            .sortedWith(compareByDescending<PlaybackQuality> { it.height }.thenByDescending { it.id })
        available.firstOrNull { currentId <= 0 || it.id < currentId }?.let { return it }
        val fallbackIds = listOf(120, 116, 112, 80, 74, 64, 32, 16, 6)
        return fallbackIds.firstOrNull { id -> currentId > id }
            ?.let { id -> PlaybackQuality(id, "画质 $id") }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    private fun checkpoint(completed: Boolean = false) {
        val exo = player ?: return
        if (!sourceReady || exo.duration <= 0) return
        lastCheckpoint = android.os.SystemClock.uptimeMillis()
        resumePosition = exo.currentPosition.coerceAtLeast(0)
        currentPgc?.let { ref ->
            ProgressRepository.get(this).savePgc(PgcProgress(ref.copy(cid = currentCid, bvid = currentBvid), resumePosition, exo.duration, System.currentTimeMillis(),
                completed || exo.playbackState == Player.STATE_ENDED || WatchProgress.nearEnd(resumePosition, exo.duration)), playbackAccount)
            return
        }
        val video = VideoSummary(currentBvid, intent.getLongExtra("aid", 0), currentCid, currentTitle,
            intent.getStringExtra("cover").orEmpty(), intent.getStringExtra("author").orEmpty(), WatchProgress.formatPosition(exo.duration), "", 0, 0, 0)
        ProgressRepository.get(this).save(WatchProgress(video, currentCid, intent.getIntExtra("part", 1), resumePosition, exo.duration,
            System.currentTimeMillis(), completed || exo.playbackState == Player.STATE_ENDED || WatchProgress.nearEnd(resumePosition, exo.duration)), playbackAccount)
    }

    override fun onPause() {
        controls.suspendControls()
        checkpoint()
        resumeOnForeground = player?.playWhenReady ?: true
        player?.pause()
        tickerHandler.removeCallbacks(ticker)
        tickerHandler.removeCallbacks(danmakuTicker)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        applyDanmakuSettings()
        applyResizeMode()
        updateDanmakuButton()
        tickerHandler.removeCallbacks(ticker)
        tickerHandler.post(ticker)
        tickerHandler.removeCallbacks(danmakuTicker)
        tickerHandler.postDelayed(danmakuTicker, 16L)
        if (resumeOnForeground) player?.play()
        updatePlaybackUi()
        if (errorView?.visibility != View.VISIBLE) controls.resumeControls() else controls.suspendControls()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_POSITION, player?.currentPosition ?: resumePosition)
        outState.putString(STATE_BVID, currentBvid)
        outState.putLong(STATE_CID, currentCid)
        outState.putString(STATE_TITLE, currentTitle)
        outState.putString(STATE_ACCOUNT, playbackAccount)
        currentPgc?.let { playable ->
            outState.putLong(STATE_PGC_SEASON_ID, playable.seasonId)
            outState.putLong(STATE_PGC_EPISODE_ID, playable.episodeId)
            outState.putLong(STATE_PGC_AID, playable.aid)
            outState.putString(STATE_PGC_SEASON_TITLE, playable.seasonTitle)
            outState.putString(STATE_PGC_EPISODE_TITLE, playable.episodeTitle)
            outState.putInt(STATE_PGC_CATEGORY, playable.category.seasonType)
            outState.putStringArrayList(STATE_PGC_AREAS, ArrayList(playable.areas))
            outState.putString(STATE_COVER, playable.coverUrl)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadGeneration++
        pgcContextGeneration++
        pgcPicker?.dispose()
        pgcPicker?.let(pageRoot::removeView)
        pgcPicker = null
        controls.dispose()
        tickerHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun finish() {
        currentPgc?.let { playable ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PGC_SEASON_ID, playable.seasonId)
                .putExtra(EXTRA_PGC_EPISODE_ID, playable.episodeId))
        }
        super.finish()
    }

    companion object {
        private const val CDN_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val EXTRA_BVID = "bvid"
        const val EXTRA_CID = "cid"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PGC_SEASON_ID = "pgc_season_id"
        const val EXTRA_PGC_EPISODE_ID = "pgc_episode_id"
        const val EXTRA_PGC_AID = "pgc_aid"
        const val EXTRA_PGC_SEASON_TITLE = "pgc_season_title"
        const val EXTRA_PGC_EPISODE_TITLE = "pgc_episode_title"
        const val EXTRA_PGC_CATEGORY = "pgc_category"
        const val EXTRA_PGC_AREAS = "pgc_areas"
        private const val STATE_POSITION = "player_position"
        private const val STATE_BVID = "player_bvid"
        private const val STATE_CID = "player_cid"
        private const val STATE_TITLE = "player_title"
        private const val STATE_ACCOUNT = "player_account"
        private const val STATE_PGC_SEASON_ID = "player_pgc_season_id"
        private const val STATE_PGC_EPISODE_ID = "player_pgc_episode_id"
        private const val STATE_PGC_AID = "player_pgc_aid"
        private const val STATE_PGC_SEASON_TITLE = "player_pgc_season_title"
        private const val STATE_PGC_EPISODE_TITLE = "player_pgc_episode_title"
        private const val STATE_PGC_CATEGORY = "player_pgc_category"
        private const val STATE_PGC_AREAS = "player_pgc_areas"
        private const val STATE_COVER = "player_cover"
    }
}
