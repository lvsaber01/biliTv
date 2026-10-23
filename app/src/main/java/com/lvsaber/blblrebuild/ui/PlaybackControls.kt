package com.lvsaber.blblrebuild.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
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
import android.widget.TextView
import com.lvsaber.blblrebuild.data.WatchProgress
import com.lvsaber.blblrebuild.data.PreviewSprite
import com.lvsaber.blblrebuild.R

/** One owner for visibility, focus, transport keys and touch scrubbing. No player/network ownership. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic composition with mandatory host callbacks, not XML inflation.
class PlaybackControls(
    context: Context,
    title: String,
    private val actions: LinearLayout,
    private val togglePlayback: () -> Unit,
    private val seekPlayback: (Long) -> Unit,
    private val focusVideo: () -> Unit,
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
    private var previewSource: PreviewSprite? = null
    private var previewLoader: ((PreviewSprite, Int, Int, (Bitmap?) -> Unit) -> Unit)? = null
    private var previewRequest = 0
    private var lastPreviewSecond = -1
    private val hideTask = Runnable { hideControls() }
    private val commitTask = Runnable { commitSeek() }
    private val playButton: Button
    private val progress: SeekBar
    private val time: TextView
    private val hint: TextView
    private val previewCard: LinearLayout
    private val previewImage: android.widget.ImageView
    private val previewTime: TextView
    var controlsVisible = true
        private set

    init {
        tag = "playback-controls"
        setOnClickListener { toggleVisibility() }
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xBB0B0E12.toInt())
        }
        heading.addView(Ui.text(context, title, 16f, Color.WHITE).apply { maxLines = 1 }, LinearLayout.LayoutParams(-1, Ui.dp(context, 42)))
        heading.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }, LinearLayout.LayoutParams(-1, Ui.dp(context, 50)))
        addView(heading, LayoutParams(-1, -2, Gravity.TOP))

        previewImage = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = Ui.roundedBackground(Color.BLACK, Ui.dp(context, 8))
        }
        previewTime = Ui.text(context, "", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xAA0B0E12.toInt())
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
        }
        previewCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 4))
            background = Ui.roundedBackground(Ui.surface, Ui.dp(context, 10))
            isFocusable = false
            visibility = GONE
            addView(previewImage, LinearLayout.LayoutParams(Ui.dp(context, 300), Ui.dp(context, 170)))
            addView(previewTime, LinearLayout.LayoutParams(-1, Ui.dp(context, 28)))
        }
        addView(previewCard, LayoutParams(Ui.dp(context, 320), Ui.dp(context, 214), Gravity.CENTER).apply {
            bottomMargin = Ui.dp(context, 138)
        })

        val transport = LinearLayout(context).apply { gravity = Gravity.CENTER }
        transport.addView(Ui.chip(context, "↶ 10 秒", false) { stepSeek(-1); commitSeek() })
        playButton = Ui.chip(context, "暂停", false) { performToggle() }.apply {
            tag = "playback-toggle"
            setTextSize(22f)
            setTextColor(Color.WHITE)
        }
        transport.addView(playButton, LinearLayout.LayoutParams(Ui.dp(context, 106), Ui.dp(context, 62)).apply {
            marginStart = Ui.dp(context, 18); marginEnd = Ui.dp(context, 18)
        })
        transport.addView(Ui.chip(context, "10 秒 ↷", false) { stepSeek(1); commitSeek() })
        addView(transport, LayoutParams(-2, -2, Gravity.CENTER))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 18), Ui.dp(context, 8), Ui.dp(context, 18), Ui.dp(context, 12))
            setBackgroundColor(0xCC0B0E12.toInt())
        }
        hint = Ui.text(context, "左右快退/快进 10 秒 · 确认播放/暂停 · 上键设置 · 返回隐藏", 13f, Ui.muted)
        bottom.addView(hint)
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
        bottom.addView(progress, LinearLayout.LayoutParams(-1, Ui.dp(context, 36)))
        time = Ui.text(context, "00:00 / --:--", 15f, Color.WHITE)
        bottom.addView(time)
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
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

    fun setPreviewSource(source: PreviewSprite?, loader: ((PreviewSprite, Int, Int, (Bitmap?) -> Unit) -> Unit)? = null) {
        previewSource = source
        previewLoader = loader
        lastPreviewSecond = -1
    }

    private fun renderProgress() {
        val value = seekTarget ?: positionMs
        time.text = context.getString(R.string.playback_time, WatchProgress.formatPosition(value), if (durationMs > 0) WatchProgress.formatPosition(durationMs) else "--:--")
        progress.contentDescription = "播放进度 ${WatchProgress.formatPosition(value)}"
        progress.isEnabled = ready && durationMs > 0
        if (!dragging) progress.progress = if (durationMs > 0) (value * 10000 / durationMs).toInt().coerceIn(0, 10000) else 0
        hint.text = if (seekTarget != null) "定位到 ${WatchProgress.formatPosition(value)} · 松开方向键跳转" else "左右快退/快进 10 秒 · 确认播放/暂停 · 上键设置 · 返回隐藏"
        renderPreview(value)
    }

    private fun renderPreview(value: Long) {
        val target = seekTarget
        if (target == null || !controlsVisible) {
            previewCard.visibility = GONE
            return
        }
        previewCard.visibility = VISIBLE
        val delta = target - positionMs
        previewTime.text = "${WatchProgress.formatPosition(target)}  ·  ${if (delta >= 0) "+" else "-"}${WatchProgress.formatPosition(kotlin.math.abs(delta))}"
        val source = previewSource ?: return
        val loader = previewLoader ?: return
        val second = (target / 1000).toInt()
        if (second == lastPreviewSecond) return
        lastPreviewSecond = second
        val request = ++previewRequest
        loader(source, second, Ui.dp(context, 300)) { bitmap ->
            if (request == previewRequest && bitmap != null && seekTarget != null) previewImage.setImageBitmap(bitmap)
        }
    }

    private fun resetHideTimer() {
        handler.removeCallbacks(hideTask)
        if (controlsVisible && playing && ready && !suspended && !dialogOpen && !dragging && seekTarget == null) handler.postDelayed(hideTask, 5000)
    }

    fun showControls(focusTransport: Boolean = true) {
        animate().cancel(); fading = false
        controlsVisible = true; visibility = VISIBLE; alpha = 1f
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (focusTransport) playButton.requestFocus()
        resetHideTimer()
    }

    fun hideControls() {
        commitSeek()
        handler.removeCallbacks(hideTask)
        if (!controlsVisible) return
        controlsVisible = false
        previewCard.visibility = GONE
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        clearFocus(); focusVideo()
        fading = true
        animate().alpha(0f).setDuration(180).withEndAction {
            if (fading) { visibility = GONE; fading = false }
        }.start()
    }

    fun toggleVisibility() { if (controlsVisible) hideControls() else showControls() }

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
        previewRequest++
        lastPreviewSecond = -1
        previewCard.visibility = GONE
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
                actions.getChildAt(index + if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)?.takeIf { it.isEnabled }?.requestFocus()
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
                (rememberedAction?.takeIf { it.isEnabled } ?: actions.getChildAt(0))?.requestFocus()
            } else playButton.requestFocus()
        }
        return true
    }

    private fun isInside(view: View?, ancestor: View): Boolean {
        var cursor = view
        while (cursor != null) { if (cursor === ancestor) return true; cursor = cursor.parent as? View }
        return false
    }

    fun beforeAction(button: View) { rememberedAction = button; commitSeek(); resetHideTimer() }
    fun beginDialog() { dialogOpen = true; handler.removeCallbacks(hideTask) }
    fun endDialog() { dialogOpen = false; restoreAction() }
    private fun restoreAction() {
        showControls(false)
        (rememberedAction?.takeIf { it.isEnabled } ?: playButton).requestFocus()
    }
    fun suspendControls() { commitSeek(); suspended = true; handler.removeCallbacks(hideTask) }
    fun resumeControls() { suspended = false; if (rememberedAction != null && controlsVisible) restoreAction() else resetHideTimer() }
    fun dispose() { animate().cancel(); handler.removeCallbacksAndMessages(null) }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
}
