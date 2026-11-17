package com.lvsaber.bilitv.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import com.lvsaber.bilitv.data.WatchProgress
import com.lvsaber.bilitv.R

/** One owner for visibility, focus, transport keys and touch scrubbing. No player/network ownership. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic composition with mandatory host callbacks, not XML inflation.
class PlaybackControls(
    context: Context,
    title: String,
    private val actions: LinearLayout,
    private val togglePlayback: () -> Unit,
    private val seekPlayback: (Long) -> Unit,
    private val focusVideo: () -> Unit,
    private val moreActions: LinearLayout? = null,
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var positionMs = 0L
    private var durationMs = 0L
    private var playing = false
    private var ready = false
    private var suspended = false
    private var dragging = false
    private var dialogOpen = false
    private var rememberedAction: View? = null
    private var seekTarget: Long? = null
    private var seekKey = 0
    private var lastSeekStep = 0L
    private var fading = false
    private var seekOverlay = false
    private val hideTask = Runnable { hideControls() }
    private val commitTask = Runnable { commitSeek() }
    private val settleSeekTask = Runnable { if (seekOverlay && playing) hideControls() }
    private val playButton: Button
    private val rewindButton: Button
    private val forwardButton: Button
    private lateinit var transport: LinearLayout
    private val progress: SeekBar
    private val time: TextView
    private val totalTime: TextView
    private val hint: TextView
    private lateinit var seekReadout: TextView
    private lateinit var titleLabel: TextView
    private lateinit var qualityBadge: TextView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var topScrim: View
    private lateinit var bottomScrim: View
    private lateinit var pauseScrim: View
    private var optionsPanel: LinearLayout? = null
    private var optionsCloseButton: Button? = null
    val morePanelVisible: Boolean get() = optionsPanel?.visibility == View.VISIBLE
    var controlsVisible = true
        private set

    init {
        tag = "playback-controls"
        clipChildren = false
        clipToPadding = false
        setOnClickListener { toggleVisibility() }
        pauseScrim = View(context).apply {
            setBackgroundColor(0x55000000)
            visibility = View.GONE
            isFocusable = false
        }
        addView(pauseScrim, LayoutParams(-1, -1))
        topScrim = View(context).apply {
            isFocusable = false
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB80A0D12.toInt(), 0x620A0D12, Color.TRANSPARENT),
            )
        }
        addView(topScrim, LayoutParams(-1, Ui.dp(context, 176), Gravity.TOP))

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 6))
        }
        titleLabel = Ui.text(context, title, 20f, Color.WHITE).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(Ui.dp(context, 4).toFloat(), 0f, Ui.dp(context, 1).toFloat(), 0xCC000000.toInt())
        }
        heading.addView(titleLabel, LinearLayout.LayoutParams(0, Ui.dp(context, 54), 1f))
        qualityBadge = Ui.text(context, "正在读取画质", 13f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = Ui.roundedBackground(0x9C0B1017.toInt(), Ui.dp(context, 18))
            setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0)
            setSingleLine(true)
        }
        heading.addView(qualityBadge, LinearLayout.LayoutParams(-2, Ui.dp(context, 40)).apply {
            marginStart = Ui.dp(context, 12)
        })
        addView(heading, LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = Ui.dp(context, 28)
            rightMargin = Ui.dp(context, 28)
            topMargin = Ui.dp(context, 14)
        })

        bottomScrim = View(context).apply {
            isFocusable = false
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xB80A0D12.toInt(), 0xF20A0D12.toInt()),
            )
        }
        addView(bottomScrim, LayoutParams(-1, Ui.dp(context, 238), Gravity.BOTTOM))

        transport = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            background = Ui.roundedBackground(0x66101820, Ui.dp(context, 42))
            setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
        }
        rewindButton = Ui.chip(context, "↶", false) { stepSeek(-1); commitSeek() }.apply {
            contentDescription = "快退 10 秒"
            setTextSize(25f)
            minWidth = 0
            setPadding(0, 0, 0, 0)
            background = Ui.roundedBackground(0x991B2732.toInt(), Ui.dp(context, 32))
        }
        transport.addView(rewindButton, LinearLayout.LayoutParams(Ui.dp(context, 62), Ui.dp(context, 62)).apply {
            marginEnd = Ui.dp(context, 10)
        })
        playButton = Ui.chip(context, "暂停", false) { performToggle() }.apply {
            tag = "playback-toggle"
            contentDescription = "播放或暂停"
            setTextSize(28f)
            minWidth = 0
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            background = Ui.roundedBackground(0xD91C2934.toInt(), Ui.dp(context, 38))
        }
        transport.addView(playButton, LinearLayout.LayoutParams(Ui.dp(context, 76), Ui.dp(context, 76)).apply {
            marginEnd = Ui.dp(context, 10)
        })
        forwardButton = Ui.chip(context, "↷", false) { stepSeek(1); commitSeek() }.apply {
            contentDescription = "快进 10 秒"
            setTextSize(25f)
            minWidth = 0
            setPadding(0, 0, 0, 0)
            background = Ui.roundedBackground(0x991B2732.toInt(), Ui.dp(context, 32))
        }
        transport.addView(forwardButton, LinearLayout.LayoutParams(Ui.dp(context, 62), Ui.dp(context, 62)))

        bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 28), Ui.dp(context, 18), Ui.dp(context, 28), Ui.dp(context, 18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xA80A0D12.toInt(), 0xE80A0D12.toInt()),
            )
        }
        hint = Ui.text(context, "左右快退/快进 10 秒 · 下键进入播放选项 · 返回收起控制", 12f, Ui.muted).apply {
            setPadding(Ui.dp(context, 2), 0, Ui.dp(context, 2), Ui.dp(context, 3))
        }
        bottomPanel.addView(hint, LinearLayout.LayoutParams(-1, Ui.dp(context, 22)))
        val timeline = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        time = Ui.text(context, "00:00", 14f, Color.WHITE).apply { setPadding(0, 0, Ui.dp(context, 12), 0) }
        progress = SeekBar(context).apply {
            tag = "playback-progress"
            max = 10000
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.blue)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    commitSeek(); dragging = true; showControls(false); resetHideTimer()
                }
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser && ready && durationMs > 0) {
                        seekTarget = durationMs * value / 10000
                        renderProgress()
                    }
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    dragging = false; commitSeek(); resetHideTimer()
                }
            })
        }
        totalTime = Ui.text(context, "--:--", 14f, Color.WHITE).apply { setPadding(Ui.dp(context, 12), 0, 0, 0) }
        timeline.addView(time, LinearLayout.LayoutParams(-2, Ui.dp(context, 34)))
        timeline.addView(progress, LinearLayout.LayoutParams(0, Ui.dp(context, 34), 1f))
        timeline.addView(totalTime, LinearLayout.LayoutParams(-2, Ui.dp(context, 34)))
        bottomPanel.addView(timeline, LinearLayout.LayoutParams(-1, Ui.dp(context, 38)))

        val controlsRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            clipChildren = false
            setPadding(0, Ui.dp(context, 6), 0, 0)
        }
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        actions.setPadding(0, 0, 0, 0)
        val actionScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(actions, LinearLayout.LayoutParams(-2, Ui.dp(context, 58)))
        }
        controlsRow.addView(actionScroll, LinearLayout.LayoutParams(-1, Ui.dp(context, 58)))
        bottomPanel.addView(controlsRow, LinearLayout.LayoutParams(-1, Ui.dp(context, 64)))
        addView(bottomPanel, LayoutParams(-1, -2, Gravity.BOTTOM))
        addView(transport, LayoutParams(-2, -2, Gravity.CENTER))
        seekReadout = Ui.text(context, "", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = Ui.roundedBackground(0xCC101820.toInt(), Ui.dp(context, 18))
            setPadding(Ui.dp(context, 14), Ui.dp(context, 4), Ui.dp(context, 14), Ui.dp(context, 4))
            visibility = View.GONE
            isFocusable = false
        }
        addView(seekReadout, LayoutParams(-2, Ui.dp(context, 34), Gravity.CENTER).apply { topMargin = Ui.dp(context, 112) })

        moreActions?.let { menuActions ->
            menuActions.orientation = LinearLayout.VERTICAL
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.floatingPanel(context, 20)
                setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
                visibility = View.GONE
                isFocusable = true
                isClickable = true
                setOnClickListener { /* Consume taps on the panel surface without toggling playback. */ }
            }
            val panelHeader = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            panelHeader.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(context, "播放选项", 19f, Color.WHITE).apply { setPadding(0, 0, 0, Ui.dp(context, 2)) })
                addView(Ui.text(context, "常用控制之外的更多设置", 12f, Ui.muted).apply { setPadding(0, 0, 0, 0) })
            }, LinearLayout.LayoutParams(0, Ui.dp(context, 48), 1f))
            optionsCloseButton = Ui.chip(context, "×", false) { closeOptionsPanel() }.apply {
                contentDescription = "关闭更多播放设置"
                minWidth = Ui.dp(context, 42)
                setPadding(0, 0, 0, 0)
            }
            panelHeader.addView(optionsCloseButton, LinearLayout.LayoutParams(Ui.dp(context, 42), Ui.dp(context, 42)))
            panel.addView(panelHeader)
            val scroll = ScrollView(context).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                addView(menuActions, FrameLayout.LayoutParams(-1, -2))
            }
            panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(context, 8) })
            panel.addView(Ui.text(context, "↑↓ 选择 · 确认调整 · 返回收起", 12f, Ui.muted).apply {
                setPadding(Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2), 0)
            }, LinearLayout.LayoutParams(-1, Ui.dp(context, 24)))
            optionsPanel = panel
            val widthDp = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
            val heightDp = context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density
            val panelWidth = Ui.dp(context, if (widthDp < 1100f) 340 else 420)
            val panelHeight = Ui.dp(context, (heightDp.toInt() - 142).coerceIn(320, if (widthDp < 1100f) 430 else 680))
            addView(panel, LayoutParams(panelWidth, panelHeight, Gravity.RIGHT or Gravity.BOTTOM).apply {
                rightMargin = Ui.dp(context, 28)
                bottomMargin = Ui.dp(context, 138)
            })
            optionsCloseButton?.nextFocusDownId = menuActions.getChildAt(0)?.id ?: View.NO_ID
            menuActions.getChildAt(0)?.nextFocusUpId = optionsCloseButton?.id ?: View.NO_ID
        }
        updateLayerVisibility()
    }

    fun update(position: Long, duration: Long, isPlaying: Boolean, isReady: Boolean) {
        val changed = playing != isPlaying || ready != isReady
        positionMs = position.coerceAtLeast(0)
        durationMs = duration.coerceAtLeast(0)
        playing = isPlaying; ready = isReady
        playButton.text = if (playing) "Ⅱ" else "▶"
        playButton.contentDescription = if (playing) "暂停播放" else "继续播放"
        rewindButton.isEnabled = ready && durationMs > 0
        forwardButton.isEnabled = ready && durationMs > 0
        if (changed) resetHideTimer()
        updateLayerVisibility()
        renderProgress()
    }

    fun updateTitle(value: String) { titleLabel.text = value }

    fun updateStreamLabel(value: String) { qualityBadge.text = value }

    private fun renderProgress() {
        val value = seekTarget ?: positionMs
        time.text = WatchProgress.formatPosition(value)
        totalTime.text = if (durationMs > 0) WatchProgress.formatPosition(durationMs) else "--:--"
        seekReadout.text = "${WatchProgress.formatPosition(value)}  /  ${if (durationMs > 0) WatchProgress.formatPosition(durationMs) else "--:--"}"
        progress.contentDescription = "播放进度 ${WatchProgress.formatPosition(value)}"
        progress.isEnabled = ready && durationMs > 0
        if (!dragging) progress.progress = if (durationMs > 0) (value * 10000 / durationMs).toInt().coerceIn(0, 10000) else 0
        hint.text = if (seekTarget != null) "定位到 ${WatchProgress.formatPosition(value)} · 松开方向键跳转" else "左右快退/快进 10 秒 · 下键打开选项 · 返回收起"
    }

    private fun resetHideTimer() {
        handler.removeCallbacks(hideTask)
        if (controlsVisible && playing && ready && !suspended && !dialogOpen && !morePanelVisible && !dragging && seekTarget == null) {
            handler.postDelayed(hideTask, if (seekOverlay) 1_500 else 5_000)
        }
    }

    fun showControls(focusTransport: Boolean = false) {
        animate().cancel(); fading = false
        handler.removeCallbacks(settleSeekTask)
        seekOverlay = false
        controlsVisible = true; visibility = VISIBLE; alpha = 1f
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (focusTransport) playButton.requestFocus()
        updateLayerVisibility()
        resetHideTimer()
    }

    private fun showSeekControls() {
        animate().cancel(); fading = false
        controlsVisible = true
        seekOverlay = true
        visibility = VISIBLE; alpha = 1f
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        updateLayerVisibility()
        handler.removeCallbacks(hideTask)
        handler.removeCallbacks(settleSeekTask)
    }

    private fun updateLayerVisibility() {
        if (!::bottomPanel.isInitialized) return
        val fullLayer = controlsVisible && !seekOverlay
        pauseScrim.visibility = if (fullLayer && ready && !playing) VISIBLE else GONE
        topScrim.visibility = if (fullLayer) VISIBLE else GONE
        bottomScrim.visibility = if (fullLayer) VISIBLE else GONE
        bottomPanel.visibility = if (fullLayer) VISIBLE else GONE
        transport.visibility = if (controlsVisible) VISIBLE else GONE
        seekReadout.visibility = if (controlsVisible && seekOverlay) VISIBLE else GONE
    }

    fun hideControls() {
        if (morePanelVisible) closeOptionsPanel(restoreFocus = false)
        commitSeek()
        handler.removeCallbacks(hideTask)
        handler.removeCallbacks(settleSeekTask)
        if (!controlsVisible) return
        controlsVisible = false
        seekOverlay = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        clearFocus(); focusVideo()
        pauseScrim.visibility = GONE
        topScrim.visibility = GONE
        bottomScrim.visibility = GONE
        bottomPanel.visibility = GONE
        transport.visibility = GONE
        fading = true
        animate().alpha(0f).setDuration(180).withEndAction {
            if (fading) { visibility = GONE; fading = false }
        }.start()
    }

    fun toggleVisibility() {
        if (morePanelVisible) closeOptionsPanel()
        else if (controlsVisible) hideControls() else showControls()
    }

    fun toggleOptionsPanel() {
        val panel = optionsPanel ?: return
        if (panel.visibility == View.VISIBLE) closeOptionsPanel() else {
            showControls(focusTransport = false)
            panel.visibility = View.VISIBLE
            handler.removeCallbacks(hideTask)
            moreActions?.getChildAt(0)?.takeIf { it.isShown && it.isEnabled }?.requestFocus()
                ?: optionsCloseButton?.requestFocus()
        }
    }

    fun closeOptionsPanel(restoreFocus: Boolean = true) {
        val panel = optionsPanel ?: return
        if (panel.visibility != View.VISIBLE) return
        panel.visibility = View.GONE
        if (restoreFocus) {
            val moreToggle = actions.findViewWithTag<View>("playback-more-toggle")
            moreToggle?.requestFocus() ?: playButton.requestFocus()
        }
        resetHideTimer()
    }

    private fun performToggle() {
        commitSeek(); showControls(focusTransport = true); togglePlayback()
    }

    private fun stepSeek(direction: Int) {
        if (!ready || durationMs <= 0) {
            showControls(focusTransport = false)
            hint.text = "正在准备视频，暂时无法跳转"
            return
        }
        showSeekControls()
        seekTarget = ((seekTarget ?: positionMs) + direction * 10000L).coerceIn(0, durationMs)
        handler.removeCallbacks(hideTask)
        handler.removeCallbacks(commitTask)
        // Allow the initial remote long-press delay before its first repeat event.
        handler.postDelayed(commitTask, 1000)
        renderProgress()
    }

    private fun commitSeek() {
        handler.removeCallbacks(commitTask)
        val target = seekTarget ?: return
        seekTarget = null; seekKey = 0
        positionMs = target
        seekPlayback(target)
        renderProgress()
        if (seekOverlay && playing) {
            handler.removeCallbacks(settleSeekTask)
            handler.postDelayed(settleSeekTask, 1_500)
        } else resetHideTimer()
    }

    /** Caller excludes its error overlay; dialogs receive their own window events. */
    fun handleKey(event: KeyEvent, focused: View?): Boolean {
        val code = event.keyCode
        val center = code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        val media = code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
        val horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (!center && !media && !horizontal && !vertical) return false
        if (morePanelVisible) {
            val menu = moreActions
            val menuViews = menu?.let { list ->
                (0 until list.childCount).map { list.getChildAt(it) }
                    .filter { it.isShown && it.isEnabled && it.isFocusable }
            }.orEmpty()
            if (event.action == KeyEvent.ACTION_UP) return true
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when {
                media -> if (event.repeatCount == 0) performToggle()
                center && focused === optionsCloseButton -> closeOptionsPanel()
                center && focused != null && menu != null && isInside(focused, menu) -> focused.performClick()
                vertical && focused === optionsCloseButton && code == KeyEvent.KEYCODE_DPAD_DOWN -> menuViews.firstOrNull()?.requestFocus()
                vertical && menu != null && focused != null && isInside(focused, menu) -> {
                    val index = menuViews.indexOf(focused)
                    val next = index + if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (next in menuViews.indices) menuViews[next].requestFocus()
                    else if (code == KeyEvent.KEYCODE_DPAD_UP) optionsCloseButton?.requestFocus()
                }
                horizontal -> {
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT && focused === menuViews.firstOrNull()) closeOptionsPanel()
                }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (code == seekKey) commitSeek()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val inActions = controlsVisible && isInside(focused, actions)
        val inTransport = controlsVisible && isInside(focused, transport)
        if (media) {
            if (event.repeatCount == 0) {
                if (code == KeyEvent.KEYCODE_MEDIA_PLAY && !playing || code == KeyEvent.KEYCODE_MEDIA_PAUSE && playing || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) performToggle()
            }
            return true
        }
        if (center) {
            if (event.repeatCount == 0) {
                if (inActions || inTransport) { resetHideTimer(); focused?.performClick() } else performToggle()
            }
        } else if (horizontal) {
            if (inActions) {
                val index = (0 until actions.childCount).indexOfFirst { actions.getChildAt(it) === focused }
                val direction = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                var nextIndex = index + direction
                while (nextIndex in 0 until actions.childCount) {
                    val candidate = actions.getChildAt(nextIndex)
                    if (candidate.visibility == View.VISIBLE && candidate.isEnabled && candidate.isFocusable && candidate.requestFocus()) break
                    nextIndex += direction
                }
                resetHideTimer()
            } else if (inTransport) {
                val transportButtons = listOf(rewindButton, playButton, forwardButton)
                val current = transportButtons.indexOf(focused)
                val next = current + if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (next in transportButtons.indices) transportButtons[next].requestFocus()
                resetHideTimer()
            } else {
                val now = SystemClock.uptimeMillis()
                if (event.repeatCount == 0 || now - lastSeekStep >= 150) {
                    stepSeek(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    seekKey = code; lastSeekStep = now
                }
            }
        } else if (vertical && event.repeatCount == 0) {
            commitSeek(); showControls(false)
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                (rememberedAvailableAction() ?: firstAvailableAction())?.requestFocus() ?: playButton.requestFocus()
            } else playButton.requestFocus()
        }
        return true
    }

    private fun isInside(view: View?, ancestor: View): Boolean {
        var cursor = view
        while (cursor != null) { if (cursor === ancestor) return true; cursor = cursor.parent as? View }
        return false
    }

    private fun firstAvailableAction(): View? = (0 until actions.childCount)
        .asSequence()
        .map(actions::getChildAt)
        .firstOrNull { it.visibility == View.VISIBLE && it.isEnabled && it.isFocusable }

    private fun rememberedAvailableAction(): View? = rememberedAction?.takeIf {
        it.visibility == View.VISIBLE && it.isEnabled && it.isFocusable
    }

    fun beforeAction(button: View) { rememberedAction = button; commitSeek(); resetHideTimer() }
    fun beginDialog() { dialogOpen = true; handler.removeCallbacks(hideTask) }
    fun endDialog() { dialogOpen = false; restoreAction() }
    private fun restoreAction() {
        showControls(false)
        (rememberedAvailableAction() ?: playButton).requestFocus()
    }
    fun suspendControls() { commitSeek(); suspended = true; handler.removeCallbacks(hideTask) }
    fun resumeControls() { suspended = false; if (rememberedAction != null && controlsVisible) restoreAction() else resetHideTimer() }
    fun dispose() { animate().cancel(); handler.removeCallbacksAndMessages(null) }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
}
