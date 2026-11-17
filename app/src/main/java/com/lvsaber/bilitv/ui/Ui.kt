package com.lvsaber.bilitv.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.text.InputType
import androidx.appcompat.widget.AppCompatImageView
import com.lvsaber.bilitv.R
import kotlin.math.ceil

object Ui {
    val background = Color.rgb(8, 13, 20)
    val surface = Color.rgb(16, 25, 35)
    val surfaceRaised = Color.rgb(21, 33, 45)
    val divider = Color.argb(34, 228, 242, 250)
    val text = Color.rgb(244, 248, 252)
    val muted = Color.rgb(160, 175, 189)
    val blue = Color.rgb(85, 210, 244)
    val pink = Color.rgb(251, 114, 153)
    private val primaryText = Color.rgb(10, 32, 43)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun pageInset(context: Context): Int {
        val widthDp = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
        return dp(context, (widthDp * 0.041f).toInt().coerceIn(20, 80))
    }

    fun button(context: Context, label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, primaryText),
        ))
        setTextSize(14f)
        isAllCaps = false
        minHeight = dp(context, if (isTelevision(context)) 42 else 48)
        minWidth = dp(context, 88)
        setPadding(dp(context, 12), 0, dp(context, 12), 0)
        val radius = dp(context, 12)
        val primary = roundedBackground(Color.rgb(223, 248, 255), radius).apply {
            setStroke(dp(context, 1), Color.argb(100, Color.red(blue), Color.green(blue), Color.blue(blue)))
        }
        val disabled = roundedBackground(surfaceRaised, radius).apply { setStroke(dp(context, 1), divider) }
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), disabled)
            addState(intArrayOf(), primary)
        }
        setOnClickListener { onClick() }
        isFocusable = true
        focusHighlight(this)
    }

    /** Secondary settings actions stay on the dark surface; focus remains a separate cyan ring. */
    fun secondaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        button(context, label, onClick).apply {
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(muted, Ui.text),
            ))
            background = roundedBackground(surfaceRaised, dp(context, 12)).apply {
                setStroke(dp(context, 1), divider)
            }
        }

    fun chip(context: Context, label: String, selected: Boolean, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        setTextSize(13f)
        isAllCaps = false
        minHeight = dp(context, if (isTelevision(context)) 38 else 44)
        minWidth = 0
        setPadding(dp(context, 13), 0, dp(context, 13), 0)
        setChipSelected(this, selected)
        setOnClickListener { onClick() }
        isFocusable = true
        focusHighlight(this)
    }

    /** Compact, readable top-level navigation shared by the three content zones. */
    fun navigationButton(context: Context, label: String, onClick: () -> Unit): Button =
        chip(context, label, false, onClick).apply {
            setTextSize(15f)
            minHeight = dp(context, 42)
            minWidth = 0
            setPadding(dp(context, 10), 0, dp(context, 10), 0)
        }

    fun setChipSelected(button: android.widget.Button, selected: Boolean) {
        button.setTextColor(if (selected) Color.WHITE else muted)
        button.background = roundedBackground(
            if (selected) Color.argb(42, Color.red(blue), Color.green(blue), Color.blue(blue)) else surfaceRaised,
            dp(button.context, 17),
        ).apply {
            setStroke(dp(button.context, 1), if (selected) Color.argb(145, Color.red(blue), Color.green(blue), Color.blue(blue)) else divider)
        }
        button.isSelected = selected
    }

    fun searchInput(context: Context, hint: String): EditText = EditText(context).apply {
        this.hint = hint
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setTextColor(Ui.text)
        setHintTextColor(muted)
        setTextSize(15f)
        background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        setPadding(dp(context, 4), 0, dp(context, 8), 0)
        minHeight = dp(context, 48)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Keep the search submit action reachable by D-pad without changing visual row order. */
    fun linkSearchFocus(input: EditText, submit: View) {
        if (input.id == View.NO_ID) input.id = View.generateViewId()
        if (submit.id == View.NO_ID) submit.id = View.generateViewId()
        input.nextFocusRightId = submit.id
        submit.nextFocusLeftId = input.id
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            val start = input.selectionStart
            val end = input.selectionEnd
            if (start == end && (end < 0 || end >= input.text.length)) submit.requestFocus() else false
        }
        submit.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) input.requestFocus() else false
        }
    }

    /** Search forms share one compact field; the touch device keeps a larger hit target. */
    fun searchBox(context: Context, input: EditText, submit: View): android.widget.LinearLayout {
        val television = isTelevision(context)
        val innerHeight = dp(context, if (television) 42 else 48)
        return android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panel(context)
            setPadding(dp(context, 10), dp(context, 4), dp(context, 8), dp(context, 4))
            minimumHeight = dp(context, if (television) 58 else 64)
            clipChildren = false
            val icon = TextView(context).apply {
                text = "⌕"
                setTextColor(blue)
                setTextSize(23f)
                gravity = Gravity.CENTER
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(icon, android.widget.LinearLayout.LayoutParams(dp(context, 30), innerHeight))
            addView(input, android.widget.LinearLayout.LayoutParams(0, innerHeight, 1f))
            addView(submit, android.widget.LinearLayout.LayoutParams(-2, innerHeight).apply {
                marginStart = dp(context, 8)
            })
        }
    }

    fun compactNavigationButton(button: Button) {
        button.setSingleLine(true)
        button.maxLines = 1
        button.ellipsize = TextUtils.TruncateAt.END
        button.setTextSize(12f)
        button.minWidth = 0
        button.setPadding(dp(button.context, 4), 0, dp(button.context, 4), 0)
    }

    fun text(context: Context, value: String = "", size: Float = 15f, color: Int = text): TextView = TextView(context).apply {
        this.text = value
        setTextColor(color)
        setTextSize(size)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
    }

    fun pageBackground(context: Context): LayerDrawable {
        val base = GradientDrawable().apply { setColor(background) }
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(context, 1080).toFloat()
            setGradientCenter(0.74f, 0.02f)
            colors = intArrayOf(Color.argb(36, 40, 122, 151), Color.TRANSPARENT)
        }
        return LayerDrawable(arrayOf(base, glow))
    }

    fun panel(context: Context): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            Color.argb(248, 24, 35, 46),
            Color.argb(245, 15, 24, 33),
        )).apply {
            cornerRadius = dp(context, 14).toFloat()
            setStroke(dp(context, 1), Color.argb(30, 228, 242, 250))
        }

    fun floatingPanel(context: Context, radiusDp: Int = 18): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            Color.argb(246, 15, 25, 35),
            Color.argb(238, 9, 16, 23),
        )).apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 1), Color.argb(34, 228, 242, 250))
        }

    fun poster(context: Context): AppCompatImageView = PosterImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundedBackground(surfaceRaised, dp(context, 10))
        clipToOutline = true
        contentDescription = null
    }

    fun avatar(context: Context): AppCompatImageView = AppCompatImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply {
            setColor(surfaceRaised)
            shape = GradientDrawable.OVAL
        }
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        contentDescription = null
    }

    fun roundedBackground(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    fun focusHighlight(view: View) {
        val outline = GradientDrawable().apply {
            setColor(Color.argb(28, Color.red(blue), Color.green(blue), Color.blue(blue)))
            setStroke(dp(view.context, if (isTelevision(view.context)) 3 else 2), blue)
            cornerRadius = dp(view.context, if (view is Button) 12 else 14).toFloat()
        }
        view.foreground = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), outline)
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        view.setOnFocusChangeListener { _, focused ->
            applyFocusHighlight(view, focused)
        }
    }

    fun applyFocusHighlight(view: View, focused: Boolean) {
        view.alpha = 1f
        // Keep the measured hit box stable: focus must not shift neighboring D-pad targets.
        view.scaleX = 1f
        view.scaleY = 1f
        view.elevation = if (focused) dp(view.context, 6).toFloat() else 0f
    }

    fun isTelevision(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun isCompactWidth(context: Context): Boolean =
        !isTelevision(context) && context.resources.configuration.smallestScreenWidthDp < 600

    fun keepLandscapeOnTelevision(activity: Activity) {
        activity.requestedOrientation = if (isTelevision(activity))
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Match the approved five-column TV / four-column landscape-tablet card rhythm. */
    fun videoColumns(context: Context, width: Int = context.resources.displayMetrics.widthPixels): Int {
        val widthDp = (width / context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return if (isTelevision(context)) (widthDp / 180).coerceIn(4, 5)
        else if (widthDp >= 600) 4
        else (widthDp / 168).coerceIn(2, 3)
    }

    fun railCardWidth(context: Context, width: Int): Int =
        (width - dp(context, 16)) / videoColumns(context, width) - dp(context, 8)

    fun cardHeight(context: Context, width: Int): Int {
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        // Reserve two title lines, two metadata lines, and breathing room above the card border.
        val textBlockHeight = ceil(78f * fontScale).toInt()
        return (width - dp(context, 12)) * 9 / 16 + dp(context, textBlockHeight)
    }

    private class PosterImageView(context: Context) : AppCompatImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val width = measuredWidth
            if (width > 0 && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
                setMeasuredDimension(width, (width * 9f / 16f).toInt())
            }
        }
    }
}
