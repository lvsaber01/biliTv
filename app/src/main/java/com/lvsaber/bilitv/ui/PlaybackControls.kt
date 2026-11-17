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
    private val hideTask = Runnable { hideControls() }
    private val commitTask = Runnable { commitSeek() }
    private val playButton: Button
    private val progress: SeekBar
    private val time: TextView
    private val totalTime: TextView
    private val hint: TextView
    private lateinit var titleLabel: TextView
    private lateinit var qualityBadge: TextView
    private lateinit var bottomPanel: LinearLayout
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
        val topScrim = View(context).apply {
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

        val bottomScrim = View(context).apply {
            isFocusable = false
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xB80A0D12.toInt(), 0xF20A0D12.toInt()),
            )
        }
        addView(bottomScrim, LayoutParams(-1, Ui.dp(context, 238), Gravity.BOTTOM))

        val transport = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        transport.addView(Ui.chip(context, "↶ 10秒", false) { stepSeek(-1); commitSeek() }.apply {
            setTextSize(13f)
        }, LinearLayout.LayoutParams(-2, Ui.dp(context, 46)))
        playButton = Ui.chip(context, "暂停", false) { performToggle() }.apply {
            tag = "playback-toggle"
            setTextSize(22f)
            setTextColor(Color.WHITE)
            background = Ui.roundedBackground(0xC91C2934.toInt(), Ui.dp(context, 25))
        }
        transport.addView(playButton, LinearLayout.LayoutParams(Ui.dp(context, 58), Ui.dp(context, 54)).apply {
            marginStart = Ui.dp(context, 10); marginEnd = Ui.dp(context, 10)
        })
        transport.addView(Ui.chip(context, "10秒 ↷", false) { stepSeek(1); commitSeek() }.apply {
            setTextSize(13f)
        }, LinearLayout.LayoutParams(-2, Ui.dp(context, 46)))

        bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 28), Ui.dp(context, 18), Ui.dp(context, 28), Ui.dp(context, 18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xA80A0D12.toInt(), 0xE80A0D12.toInt()),
            )
        }
        hint = Ui.text(context, "左右快退/快进 10 秒 · 确认播放/暂停 · 上键打开常用设置", 12f, Ui.muted).apply {
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
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(0, Ui.dp(context, 6), 0, 0)
        }
        controlsRow.addView(transport, LinearLayout.LayoutParams(-2, Ui.dp(context, 58)))
        controlsRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        actions.setPadding(0, 0, 0, 0)
        controlsRow.addView(actions, LinearLayout.LayoutParams(-2, Ui.dp(context, 58)))
        bottomPanel.addView(controlsRow, LinearLayout.LayoutParams(-1, Ui.dp(context, 64)))
        addView(bottomPanel, LayoutParams(-1, -2, Gravity.BOTTOM))

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
    }

    fun update(position: Long, duration: Long, isPlaying: Boolean, isReady: Boolean) {
        val changed = playing != isPlaying || ready != isReady
        positionMs = position.coerceAtLeast(0)
        durationMs = duration.coerceAtLeast(0)
        playing = isPlaying; ready = isReady
        playButton.text = if (playing) "Ⅱ" else "▶"
        playButton.contentDescription = if (playing) "暂停播放" else "继续播放"
        if (changed) resetHideTimer()
        renderProgress()
    }

    fun updateTitle(value: String) { titleLabel.text = value }

    fun updateStreamLabel(value: String) { qualityBadge.text = value }

    private fun renderProgress() {
        val value = seekTarget ?: positionMs
        time.text = WatchProgress.formatPosition(value)
        totalTime.text = if (durationMs > 0) WatchProgress.formatPosition(durationMs) else "--:--"
        progress.contentDescription = "播放进度 ${WatchProgress.formatPosition(value)}"
        progress.isEnabled = ready && durationMs > 0
        if (!dragging) progress.progress = if (durationMs > 0) (value * 10000 / durationMs).toInt().coerceIn(0, 10000) else 0
        hint.text = if (seekTarget != null) "定位到 ${WatchProgress.formatPosition(value)} · 松开方向键跳转" else "左右快退/快进 10 秒 · 确认播放/暂停 · 上键设置 · 返回隐藏"
    }

    private fun resetHideTimer() {
        handler.removeCallbacks(hideTask)
        if (controlsVisible && playing && ready && !suspended && !dialogOpen && !morePanelVisible && !dragging && seekTarget == null) handler.postDelayed(hideTask, 5000)
    }

    fun showControls(focusTransport: Boolean = true) {
        animate().cancel(); fading = false
        controlsVisible = true; visibility = VISIBLE; alpha = 1f
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (focusTransport) playButton.requestFocus()
        resetHideTimer()
    }

    fun hideControls() {
        if (morePanelVisible) closeOptionsPanel(restoreFocus = false)
        commitSeek()
        handler.removeCallbacks(hideTask)
        if (!controlsVisible) return
        controlsVisible = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        clearFocus(); focusVideo()
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
        commitSeek(); showControls(); togglePlayback()
    }

    private fun stepSeek(direction: Int) {
        showControls()
        if (!ready || durationMs <= 0) { hint.text = "正在准备视频，暂时无法跳转"; return }
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
        renderProgress(); resetHideTimer()
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
        if (media) {
            if (event.repeatCount == 0) {
                if (code == KeyEvent.KEYCODE_MEDIA_PLAY && !playing || code == KeyEvent.KEYCODE_MEDIA_PAUSE && playing || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) performToggle()
            }
            return true
        }
        if (center) {
            if (event.repeatCount == 0) {
                if (inActions) { resetHideTimer(); focused?.performClick() } else performToggle()
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
            } else {
                val now = SystemClock.uptimeMillis()
                if (event.repeatCount == 0 || now - lastSeekStep >= 150) {
                    stepSeek(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    seekKey = code; lastSeekStep = now
                }
            }
        } else if (vertical && event.repeatCount == 0) {
            commitSeek(); showControls(false)
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
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
