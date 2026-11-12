package com.lvsaber.bilitv.ui

import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.AppSettings
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.BiliApiException
import com.lvsaber.bilitv.data.LiveCodecPreference
import com.lvsaber.bilitv.data.LiveDanmakuClient
import com.lvsaber.bilitv.data.LiveDanmakuState
import com.lvsaber.bilitv.data.LiveLibraryRepository
import com.lvsaber.bilitv.data.LivePlaybackAttempt
import com.lvsaber.bilitv.data.LiveProtocolPreference
import com.lvsaber.bilitv.data.LiveQualityOption
import com.lvsaber.bilitv.data.LiveQualityPolicy
import com.lvsaber.bilitv.data.LiveRoomDetails
import com.lvsaber.bilitv.data.LiveRoomSummary
import com.lvsaber.bilitv.data.LiveStreamProfile
import com.lvsaber.bilitv.data.LiveRecoveryPolicy
import com.lvsaber.bilitv.data.PlaybackCodec
import com.lvsaber.bilitv.data.PlaybackDeviceCapabilities
import java.util.concurrent.atomic.AtomicInteger

class LivePlayerActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var library: LiveLibraryRepository
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var danmakuOverlay: LiveDanmakuOverlay
    private lateinit var header: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var headerMeta: TextView
    private lateinit var status: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var controls: LinearLayout
    private lateinit var playButton: Button
    private lateinit var danmakuButton: Button
    private lateinit var favoriteButton: Button
    private lateinit var qualityButton: Button
    private lateinit var codecButton: Button
    private lateinit var protocolButton: Button
    private lateinit var routeButton: Button
    private var player: ExoPlayer? = null
    private var room: LiveRoomDetails? = null
    private var danmakuClient: LiveDanmakuClient? = null
    private var danmakuState = LiveDanmakuState.CONNECTING
    private var settings = AppSettings()
    private var qualities: List<LiveQualityOption> = emptyList()
    private var profiles: List<LiveStreamProfile> = emptyList()
    private var attempts: List<LivePlaybackAttempt> = emptyList()
    private var currentAttemptIndex = 0
    private var automaticRetriesUsed = 0
    private var currentQualityId = 0
    private var controlsVisible = false
    private var firstFrameRendered = false
    private var sessionGeneration = 0
    private var streamGeneration = 0
    private var startTimeoutRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controlsTimeout = Runnable { hideControls() }
    private var danmakuSurface: View? = null
    private val surfaceListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateDanmakuViewport() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.keepLandscapeOnTelevision(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        api = BiliApiClient(this)
        library = LiveLibraryRepository.get(this)
        settings = AppSettings.load(this)
        val roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        if (roomId <= 0L) { finish(); return }
        setContentView(buildPlayerScreen())
        showControls(focusFirst = true)
        loadRoom(roomId)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_MENU,
            ) && !controlsVisible
        ) {
            showControls(focusFirst = true)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            showControls(focusFirst = true)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && controlsVisible && errorPanel.visibility != View.VISIBLE &&
            event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN) &&
            currentFocus?.let { isInside(controls, it) } == true
        ) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        releaseSession()
    }

    override fun onDestroy() {
        releaseSession()
        danmakuSurface?.removeOnLayoutChangeListener(surfaceListener)
        danmakuSurface = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildPlayerScreen(): View {
        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK); isFocusable = true; isFocusableInTouchMode = true }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = true
            setOnClickListener { if (controlsVisible) hideControls() else showControls(focusFirst = false) }
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        danmakuOverlay = LiveDanmakuOverlay(this).apply {
            overlayEnabled = settings.liveDanmakuEnabled
            displaySettings = settings.danmaku
        }
        root.addView(danmakuOverlay, FrameLayout.LayoutParams(-1, -1))

        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundedBackground(0xB8000000.toInt(), Ui.dp(context, 14))
            setPadding(Ui.dp(context, 10), Ui.dp(context, 5), Ui.dp(context, 14), Ui.dp(context, 5))
            visibility = View.GONE
        }
        headerTitle = Ui.text(this, "", 18f, Ui.text).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        headerMeta = Ui.text(this, "", 12f, Ui.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        header.addView(headerTitle)
        header.addView(headerMeta)
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = Ui.dp(this@LivePlayerActivity, 22); topMargin = Ui.dp(this@LivePlayerActivity, 22); rightMargin = Ui.dp(this@LivePlayerActivity, 22)
        })

        status = Ui.text(this, "正在连接直播间…", 15f, Ui.text).apply {
            gravity = Gravity.CENTER
            background = Ui.roundedBackground(0xB8000000.toInt(), Ui.dp(context, 12))
            setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.roundedBackground(0xE6161B22.toInt(), Ui.dp(context, 18))
            setPadding(Ui.dp(context, 22), Ui.dp(context, 16), Ui.dp(context, 22), Ui.dp(context, 18))
            visibility = View.GONE
        }
        errorText = Ui.text(this, "", 16f, Ui.text).apply { gravity = Gravity.CENTER; maxLines = 3 }
        errorPanel.addView(errorText, LinearLayout.LayoutParams(-1, -2))
        val errorActions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        errorActions.addView(Ui.button(this, "重试") { retryManually() })
        errorActions.addView(Ui.button(this, "线路") { showRouteChoices() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(context, 42)).apply { marginStart = Ui.dp(this@LivePlayerActivity, 8) }
        })
        errorActions.addView(Ui.button(this, "返回") { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(context, 42)).apply { marginStart = Ui.dp(this@LivePlayerActivity, 8) }
        })
        errorPanel.addView(errorActions, LinearLayout.LayoutParams(-1, Ui.dp(this, 50)).apply { topMargin = Ui.dp(this@LivePlayerActivity, 10) })
        root.addView(errorPanel, FrameLayout.LayoutParams(Ui.dp(this, 620), -2, Gravity.CENTER))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundedBackground(0xD90C1015.toInt(), Ui.dp(context, 18))
            setPadding(Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6))
            visibility = View.GONE
        }
        val controlRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        playButton = controlButton("播放") { togglePlayback() }
        danmakuButton = controlButton("弹幕") { toggleDanmaku() }
        qualityButton = controlButton("画质") { showQualityChoices() }
        codecButton = controlButton("编码") { showCodecChoices() }
        protocolButton = controlButton("协议") { showProtocolChoices() }
        routeButton = controlButton("线路") { showRouteChoices() }
        favoriteButton = controlButton("收藏") { toggleFavorite() }
        listOf(playButton, danmakuButton, qualityButton, codecButton, protocolButton, routeButton, favoriteButton).forEach(controlRow::addView)
        controls.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(controlRow)
        }, LinearLayout.LayoutParams(-1, Ui.dp(this, 54)))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = Ui.dp(this@LivePlayerActivity, 26); rightMargin = Ui.dp(this@LivePlayerActivity, 26); bottomMargin = Ui.dp(this@LivePlayerActivity, 24)
        })
        root.setOnClickListener { if (controlsVisible) hideControls() else showControls(focusFirst = false) }
        root.post { updateDanmakuViewport() }
        return root
    }

    private fun controlButton(label: String, action: () -> Unit): Button = Ui.button(this, label, action).apply {
        minWidth = Ui.dp(context, 96)
        layoutParams = LinearLayout.LayoutParams(-2, Ui.dp(this@LivePlayerActivity, 44)).apply { marginEnd = Ui.dp(this@LivePlayerActivity, 5) }
        setOnFocusChangeListener { view, focused ->
            Ui.applyFocusHighlight(view, focused)
            if (focused) armControlsTimeout()
        }
    }

    private fun loadRoom(roomId: Long) {
        status.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        val token = ++sessionGeneration
        AppExecutors.io.execute {
            val result = runCatching { api.getLiveRoomDetails(roomId) }
            runOnUiThread {
                if (token != sessionGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { details ->
                    room = details
                    library.recordWatch(details.summary)
                    headerTitle.text = "LIVE  ·  ${details.summary.title}"
                    headerMeta.text = listOf(details.summary.anchorName, details.summary.parentAreaName, details.summary.areaName)
                        .filter(String::isNotBlank).distinct().joinToString(" · ")
                    updateFavoriteButton()
                    if (details.isHidden || details.isLocked) {
                        showFatal("该直播间已隐藏或锁定。")
                    } else if (details.summary.liveStatus !in setOf(1, 2)) {
                        showFatal("主播当前未开播。")
                    } else {
                        requestStream(settings.liveQualityId)
                        if (settings.liveDanmakuEnabled) startDanmaku(details.summary.roomId)
                    }
                }.onFailure { showFatal("无法读取直播间信息：${friendlyMessage(it)}") }
            }
        }
    }

    private fun requestStream(requestedQuality: Int) {
        val currentRoom = room ?: return
        val token = ++streamGeneration
        firstFrameRendered = false
        status.text = "正在获取直播流…"
        status.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        val protocol = settings.liveProtocolPreference
        val codec = settings.liveCodecPreference
        val initialQn = requestedQuality.takeIf { it > 0 } ?: 10_000
        AppExecutors.io.execute {
            val result = runCatching {
                val first = api.getLivePlayInfo(currentRoom.summary.roomId, initialQn, protocol, codec)
                val available = first.first
                val targetQn = LiveQualityPolicy.targetQuality(requestedQuality, available, first.second)
                    ?: throw BiliApiException(-500, "直播间没有可用画质")
                val selected = if (first.second.any { it.qualityId == targetQn }) first
                    else api.getLivePlayInfo(currentRoom.summary.roomId, targetQn, protocol, codec)
                val selectedProfiles = selected.second.ifEmpty { first.second }
                val actualQn = LiveQualityPolicy.actualQuality(targetQn, selectedProfiles)
                    ?: throw BiliApiException(-500, "直播间没有返回可播放的画质")
                Triple(available.ifEmpty { selected.first }, actualQn, selectedProfiles)
            }
            runOnUiThread {
                if (token != streamGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { (availableQualities, chosenQn, streamProfiles) ->
                    qualities = availableQualities
                    profiles = streamProfiles
                    currentQualityId = chosenQn
                    if (requestedQuality > 0 && chosenQn != requestedQuality) {
                        val actualLabel = availableQualities.firstOrNull { it.id == chosenQn }?.label ?: "画质 $chosenQn"
                        Toast.makeText(this, "直播间未返回所选画质，当前使用 $actualLabel", Toast.LENGTH_LONG).show()
                    }
                    val capabilities = runCatching { PlaybackDeviceCapabilities.detect(this) }
                        .getOrElse { PlaybackDeviceCapabilities.unrestricted() }
                    attempts = LiveRecoveryPolicy.attempts(
                        streamProfiles,
                        chosenQn,
                        settings.liveProtocolPreference,
                        settings.liveCodecPreference,
                        capabilities.supportedCodecs,
                    )
                    Log.i(TAG, "Live profiles=${streamProfiles.size} usableAttempts=${attempts.size} quality=$chosenQn codec=${settings.liveCodecPreference} protocol=${settings.liveProtocolPreference}")
                    if (attempts.isEmpty()) {
                        showFatal("该直播间当前没有设备可用的 ${settings.liveCodecPreference.label} / ${settings.liveProtocolPreference.label} 流。可在设置或播放器中尝试其他选项。")
                    } else {
                        automaticRetriesUsed = 0
                        currentAttemptIndex = 0
                        playAttempt(attempts.first(), automatic = false)
                    }
                    updateControlLabels()
                }.onFailure { showFatal("无法获取直播流：${friendlyMessage(it)}") }
            }
        }
    }

    private fun playAttempt(attempt: LivePlaybackAttempt, automatic: Boolean) {
        val currentRoom = room ?: return
        Log.i(TAG, "Starting live qn=${attempt.profile.qualityId} protocol=${attempt.profile.protocol} codec=${attempt.profile.codec} route=${attempt.route.label} automatic=$automatic retry=$automaticRetriesUsed")
        releasePlayerOnly()
        errorPanel.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = if (automatic) "线路异常，正在尝试 ${attempt.route.label} · ${attempt.profile.protocol.label}…" else "正在连接 ${attempt.route.label} · ${attempt.profile.protocol.label}…"
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(PLAYER_USER_AGENT)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(mapOf("Referer" to "https://live.bilibili.com/${currentRoom.summary.roomId}"))
        val sourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(sourceFactory).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayButton()
                if (playbackState == Player.STATE_BUFFERING) {
                    if (firstFrameRendered) status.text = "正在缓冲直播…"
                    else status.text = "正在连接直播流…"
                    status.visibility = View.VISIBLE
                } else if (playbackState == Player.STATE_READY && firstFrameRendered) {
                    status.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                startTimeoutRunnable?.let(mainHandler::removeCallbacks)
                handlePlaybackFailure(error)
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                runOnUiThread {
                    if (player !== exo || isFinishing || isDestroyed) return@runOnUiThread
                    firstFrameRendered = true
                    startTimeoutRunnable?.let(mainHandler::removeCallbacks)
                    startTimeoutRunnable = null
                    status.visibility = View.GONE
                    errorPanel.visibility = View.GONE
                    header.visibility = if (controlsVisible) View.VISIBLE else View.GONE
                    updateControlLabels()
                    armControlsTimeout()
                }
            }
        })
        val itemBuilder = MediaItem.Builder().setUri(attempt.route.url)
        if (attempt.profile.protocol == com.lvsaber.bilitv.data.LiveStreamProtocol.HLS) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        exo.setMediaItem(itemBuilder.build())
        exo.playWhenReady = true
        exo.prepare()
        scheduleStartTimeout(exo)
        updateControlLabels()
    }

    private fun scheduleStartTimeout(target: ExoPlayer) {
        startTimeoutRunnable?.let(mainHandler::removeCallbacks)
        val timeout = Runnable {
            if (player === target && !firstFrameRendered && !isFinishing && !isDestroyed) {
                handlePlaybackFailure(BiliApiException(-1008, "直播首帧等待超时"))
            }
        }
        startTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, START_TIMEOUT_MS)
    }

    private fun handlePlaybackFailure(error: Throwable) {
        status.visibility = View.GONE
        val nextIndex = LiveRecoveryPolicy.nextAttempt(
            attempts,
            currentAttemptIndex,
            automaticRetriesUsed,
            settings.liveAutoRecoveryEnabled && isRecoverable(error),
        )
        val playbackCode = (error as? PlaybackException)?.errorCode
        val apiCode = (error as? BiliApiException)?.apiCode
        Log.w(TAG, "Live playback failed playbackCode=${playbackCode ?: "-"} apiCode=${apiCode ?: "-"} attempt=${currentAttemptIndex + 1}/${attempts.size} retries=$automaticRetriesUsed next=${nextIndex?.plus(1) ?: "none"}")
        if (nextIndex != null) {
            automaticRetriesUsed++
            currentAttemptIndex = nextIndex
            val next = attempts[nextIndex]
            Toast.makeText(this, "${friendlyMessage(error)} · 自动尝试${next.route.label} ${next.profile.protocol.label}", Toast.LENGTH_SHORT).show()
            playAttempt(next, automatic = true)
            return
        }
        showPlaybackError("直播播放失败：${friendlyMessage(error)}")
    }

    private fun isRecoverable(error: Throwable): Boolean {
        if (error is BiliApiException) return error.apiCode == -1008 || error.httpCode?.let { it in 500..599 } == true
        val playback = error as? PlaybackException ?: return error.message?.contains("timeout", true) == true
        return LiveRecoveryPolicy.isRecoverablePlaybackCode(playback.errorCode)
    }

    private fun showPlaybackError(message: String) {
        firstFrameRendered = false
        status.visibility = View.GONE
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
        val hasAlternates = attempts.size > 1
        errorPanel.getChildAt(1)?.let { actions ->
            if (actions is LinearLayout && actions.childCount > 1) actions.getChildAt(1).isEnabled = hasAlternates
        }
        showControls(focusFirst = true)
        focusRetryAction()
    }

    private fun showFatal(message: String) {
        releasePlayerOnly()
        status.visibility = View.GONE
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
        errorPanel.getChildAt(1)?.let { actions -> if (actions is LinearLayout && actions.childCount > 1) actions.getChildAt(1).isEnabled = false }
        showControls(focusFirst = false)
        focusRetryAction()
    }

    private fun focusRetryAction() {
        (errorPanel.getChildAt(1) as? LinearLayout)?.getChildAt(0)?.requestFocus()
    }

    private fun retryManually() {
        if (attempts.isEmpty()) {
            room?.let { requestStream(settings.liveQualityId) }
            return
        }
        currentAttemptIndex = 0
        automaticRetriesUsed = 0
        playAttempt(attempts.first(), automatic = false)
    }

    private fun togglePlayback() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        updatePlayButton()
        armControlsTimeout()
    }

    private fun toggleDanmaku() {
        settings = settings.copy(liveDanmakuEnabled = !danmakuOverlay.overlayEnabled)
        settings.save(this)
        danmakuOverlay.overlayEnabled = settings.liveDanmakuEnabled
        if (settings.liveDanmakuEnabled) room?.let { startDanmaku(it.summary.roomId) }
        else { danmakuClient?.disconnect(); danmakuClient = null }
        updateControlLabels()
        armControlsTimeout()
    }

    private fun startDanmaku(roomId: Long) {
        danmakuClient?.disconnect()
        val client = LiveDanmakuClient(api, roomId, object : LiveDanmakuClient.Listener {
            override fun onState(state: LiveDanmakuState, detail: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    danmakuState = state
                    updateDanmakuButtonLabel()
                    if (state == LiveDanmakuState.UNAVAILABLE) {
                        status.text = detail
                        if (player?.isPlaying == true) {
                            Toast.makeText(this@LivePlayerActivity, detail, Toast.LENGTH_LONG).show()
                        } else {
                            status.visibility = View.VISIBLE
                        }
                    }
                }
            }

            override fun onMessage(text: String) {
                runOnUiThread { if (!isFinishing && !isDestroyed) danmakuOverlay.addMessage(text) }
            }
        })
        danmakuClient = client
        client.connect()
    }

    private fun showQualityChoices() {
        if (qualities.isEmpty()) return
        val options = qualities.distinctBy { it.id }.sortedByDescending { it.id }
        showChoiceDialog("直播画质", options.map { it.label }) { index ->
            val quality = options[index]
            settings = settings.copy(liveQualityId = quality.id)
            settings.save(this)
            requestStream(quality.id)
        }
    }

    private fun showCodecChoices() {
        val options = LiveCodecPreference.entries.toList()
        showChoiceDialog("直播编码", options.map { it.label }) { index ->
            settings = settings.copy(liveCodecPreference = options[index])
            settings.save(this)
            requestStream(currentQualityId)
        }
    }

    private fun showProtocolChoices() {
        val options = LiveProtocolPreference.entries.toList()
        showChoiceDialog("直播协议", options.map { it.label }) { index ->
            settings = settings.copy(liveProtocolPreference = options[index])
            settings.save(this)
            requestStream(currentQualityId)
        }
    }

    private fun showRouteChoices() {
        if (attempts.isEmpty()) return
        val current = attempts.getOrNull(currentAttemptIndex)?.profile ?: return
        val options = current.routes
        if (options.size <= 1) {
            Toast.makeText(this, "当前直播流只有一条可用线路", Toast.LENGTH_SHORT).show()
            return
        }
        showChoiceDialog("选择直播线路", options.map { it.label }) { index ->
            val desired = options[index]
            val matching = attempts.indexOfFirst { it.profile.protocol == current.protocol && it.profile.codec == current.codec && it.route.url == desired.url }
            if (matching >= 0) {
                currentAttemptIndex = matching
                automaticRetriesUsed = 0
                playAttempt(attempts[matching], automatic = false)
            }
        }
    }

    private fun showChoiceDialog(title: String, items: List<String>, onSelected: (Int) -> Unit) {
        if (items.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onSelected(which) }
            .show()
        showControls(focusFirst = false)
    }

    private fun toggleFavorite() {
        val summary = room?.summary ?: return
        library.toggleFavorite(summary)
        updateFavoriteButton()
        Toast.makeText(this, if (library.isFavorite(summary.roomId)) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
        armControlsTimeout()
    }

    private fun updateFavoriteButton() {
        val saved = room?.summary?.roomId?.let(library::isFavorite) == true
        favoriteButton.text = if (saved) "已收藏" else "收藏"
        favoriteButton.setTextColor(if (saved) Ui.pink else android.graphics.Color.WHITE)
    }

    private fun updateControlLabels() {
        updatePlayButton()
        updateDanmakuButtonLabel()
        qualityButton.text = qualities.firstOrNull { it.id == currentQualityId }?.label?.let { "画质：$it" } ?: "画质：自动"
        codecButton.text = "编码：${settings.liveCodecPreference.label.substringBefore(" · ")}"
        protocolButton.text = "协议：${settings.liveProtocolPreference.label.substringBefore(" · ")}"
        val attempt = attempts.getOrNull(currentAttemptIndex)
        routeButton.text = "线路：${attempt?.route?.label ?: "自动"}"
        updateFavoriteButton()
    }

    private fun updateDanmakuButtonLabel() {
        danmakuButton.text = if (!danmakuOverlay.overlayEnabled) "弹幕：关" else when (danmakuState) {
            LiveDanmakuState.CONNECTED -> "弹幕：已连接"
            LiveDanmakuState.UNAVAILABLE -> "弹幕：不可用"
            LiveDanmakuState.CONNECTING -> "弹幕：连接中"
            LiveDanmakuState.RECONNECTING -> "弹幕：重连中"
        }
    }

    private fun updatePlayButton() {
        playButton.text = if (player?.isPlaying == true) "暂停" else "播放"
    }

    private fun showControls(focusFirst: Boolean) {
        controlsVisible = true
        controls.visibility = View.VISIBLE
        header.visibility = View.VISIBLE
        if (focusFirst) playButton.requestFocus()
        armControlsTimeout()
    }

    private fun hideControls() {
        if (currentFocus != null && isInside(controls, currentFocus!!)) controls.clearFocus()
        controlsVisible = false
        controls.visibility = View.GONE
        header.visibility = View.GONE
        if (errorPanel.visibility != View.VISIBLE) playerView.requestFocus()
    }

    private fun armControlsTimeout() {
        mainHandler.removeCallbacks(controlsTimeout)
        if (controlsVisible && player?.isPlaying == true && errorPanel.visibility != View.VISIBLE) {
            mainHandler.postDelayed(controlsTimeout, CONTROLS_HIDE_DELAY_MS)
        }
    }

    private fun updateDanmakuViewport() {
        if (!::danmakuOverlay.isInitialized || !::playerView.isInitialized) return
        val surface = playerView.videoSurfaceView
        if (danmakuSurface !== surface) {
            danmakuSurface?.removeOnLayoutChangeListener(surfaceListener)
            danmakuSurface = surface
            surface?.addOnLayoutChangeListener(surfaceListener)
        }
        if (surface == null || surface.width <= 0 || surface.height <= 0) {
            danmakuOverlay.setVideoBounds(null)
            return
        }
        val surfaceLocation = IntArray(2)
        val rootLocation = IntArray(2)
        surface.getLocationOnScreen(surfaceLocation)
        root.getLocationOnScreen(rootLocation)
        danmakuOverlay.setVideoBounds(
            RectF(
                (surfaceLocation[0] - rootLocation[0]).toFloat(),
                (surfaceLocation[1] - rootLocation[1]).toFloat(),
                (surfaceLocation[0] - rootLocation[0] + surface.width).toFloat(),
                (surfaceLocation[1] - rootLocation[1] + surface.height).toFloat(),
            ),
        )
    }

    private fun releasePlayerOnly() {
        startTimeoutRunnable?.let(mainHandler::removeCallbacks)
        startTimeoutRunnable = null
        playerView.player = null
        player?.release()
        player = null
    }

    private fun releaseSession() {
        sessionGeneration++
        streamGeneration++
        mainHandler.removeCallbacks(controlsTimeout)
        danmakuClient?.disconnect()
        danmakuClient = null
        releasePlayerOnly()
    }

    private fun isInside(ancestor: View, view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is BiliApiException -> when (error.apiCode) {
            -101, -111 -> "请登录后重试"
            -352, -412 -> "B 站请求校验未通过"
            60004 -> "直播间不存在"
            else -> error.message
        }
        is PlaybackException -> error.cause?.message?.take(100) ?: "播放器错误 ${error.errorCode}"
        else -> error.message?.take(100) ?: "网络异常"
    }

    companion object {
        private const val TAG = "LivePlayer"
        const val EXTRA_ROOM_ID = "live_room_id"
        private const val PLAYER_USER_AGENT = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        private const val START_TIMEOUT_MS = 12_000L
        private const val CONTROLS_HIDE_DELAY_MS = 5_000L
    }
}
