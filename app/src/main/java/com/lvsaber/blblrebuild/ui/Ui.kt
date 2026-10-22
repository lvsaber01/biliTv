package com.lvsaber.blblrebuild.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import com.lvsaber.blblrebuild.R

object Ui {
    val background = Color.rgb(11, 14, 18)
    val surface = Color.rgb(21, 26, 33)
    val surfaceRaised = Color.rgb(29, 36, 45)
    val divider = Color.rgb(48, 57, 68)
    val text = Color.rgb(247, 249, 251)
    val muted = Color.rgb(169, 180, 191)
    val blue = Color.rgb(0, 174, 236)
    val pink = Color.rgb(251, 114, 153)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun button(context: Context, label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(14f)
        isAllCaps = false
        minHeight = dp(context, 42)
        minWidth = dp(context, 88)
        setPadding(dp(context, 12), 0, dp(context, 12), 0)
        background = roundedBackground(blue, dp(context, 8))
        setOnClickListener { onClick() }
        isFocusable = true
    }

    fun chip(context: Context, label: String, selected: Boolean, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        setTextColor(if (selected) Color.WHITE else muted)
        setTextSize(13f)
        isAllCaps = false
        minHeight = dp(context, 34)
        minWidth = 0
        setPadding(dp(context, 12), 0, dp(context, 12), 0)
        background = roundedBackground(if (selected) blue else surfaceRaised, dp(context, 17))
        setOnClickListener { onClick() }
        isFocusable = true
    }

    fun text(context: Context, value: String = "", size: Float = 15f, color: Int = text): TextView = TextView(context).apply {
        this.text = value
        setTextColor(color)
        setTextSize(size)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
    }

    fun panel(context: Context): GradientDrawable = roundedBackground(surface, dp(context, 10))

    fun poster(context: Context): AppCompatImageView = PosterImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundedBackground(surfaceRaised, dp(context, 10))
        clipToOutline = true
        contentDescription = null
    }

    fun roundedBackground(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    fun focusHighlight(view: View) {
        view.setOnFocusChangeListener { _, focused ->
            view.alpha = 1f
            if (focused) view.scaleX = 1.02f else view.scaleX = 1f
            if (focused) view.scaleY = 1.02f else view.scaleY = 1f
            view.elevation = if (focused) dp(view.context, 8).toFloat() else 0f
        }
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
