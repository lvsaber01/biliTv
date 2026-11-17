package com.lvsaber.bilitv.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.lvsaber.bilitv.core.AppExecutors
import com.lvsaber.bilitv.data.BiliApiClient
import com.lvsaber.bilitv.data.QrLoginStatus
import com.lvsaber.bilitv.data.QrLoginTicket

/** QR login flow isolated from the main screen so it can be cancelled safely. */
class LoginActivity : AppCompatActivity() {
    private lateinit var api: BiliApiClient
    private lateinit var status: TextView
    private lateinit var qrImage: ImageView
    private lateinit var retryButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var ticket: QrLoginTicket? = null
    private var active = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.keepLandscapeOnTelevision(this)
        api = BiliApiClient(this)
        setContentView(buildContent())
        window.decorView.post { if (Ui.isTelevision(this)) retryButton.requestFocus() }
        generate()
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.pageInset(context), Ui.dp(context, 14), Ui.pageInset(context), Ui.dp(context, 18))
            background = Ui.pageBackground(context)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = Ui.chip(this, "‹ 返回", false) { finish() }.apply { id = View.generateViewId() }
        header.addView(back, LinearLayout.LayoutParams(-2, Ui.dp(this, 42)))
        header.addView(Ui.text(this, "二维码登录", 25f).apply {
            setPadding(Ui.dp(context, 14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        root.addView(header, LinearLayout.LayoutParams(-1, Ui.dp(this, 52)))

        val wide = !Ui.isCompactWidth(this) &&
            resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 720f
        val content = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val instructions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 24), Ui.dp(context, 22), Ui.dp(context, 24), Ui.dp(context, 22))
        }
        instructions.addView(Ui.text(this, "安全登录 biliTv", 22f).apply { setPadding(0, 0, 0, Ui.dp(context, 10)) })
        instructions.addView(Ui.text(this, "使用哔哩哔哩官方客户端扫码并确认。登录后可同步账号专属内容和观看进度。", 15f, Ui.muted).apply {
            maxLines = 3
            setPadding(0, 0, 0, Ui.dp(context, 14))
        })
        listOf(
            "1   打开手机上的哔哩哔哩客户端",
            "2   扫描右侧二维码",
            "3   在手机上确认登录",
        ).forEach { line ->
            instructions.addView(Ui.text(this, line, 15f).apply {
                setPadding(0, Ui.dp(context, 5), 0, Ui.dp(context, 5))
            }, LinearLayout.LayoutParams(-1, Ui.dp(this, 38)))
        }
        if (wide) content.addView(instructions, LinearLayout.LayoutParams(0, -1, 1f).apply {
            marginEnd = Ui.dp(this@LoginActivity, 14)
        }) else content.addView(instructions, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Ui.dp(this@LoginActivity, 12)
        })

        val qrPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.panel(context)
            setPadding(Ui.dp(context, 18), Ui.dp(context, 14), Ui.dp(context, 18), Ui.dp(context, 16))
        }
        status = Ui.text(this, "正在生成二维码…", 14f, Ui.muted).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(0, 0, 0, Ui.dp(context, 10))
        }
        qrPanel.addView(status, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)))
        val qrSize = Ui.dp(this, if (wide) 280 else 238)
        qrImage = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isFocusable = false
            isClickable = false
            contentDescription = "哔哩哔哩登录二维码"
        }
        qrPanel.addView(qrImage, LinearLayout.LayoutParams(qrSize, qrSize))
        retryButton = Ui.button(this, "重新生成") { generate() }.apply {
            id = View.generateViewId()
            nextFocusUpId = back.id
        }
        back.nextFocusDownId = retryButton.id
        qrPanel.addView(retryButton, LinearLayout.LayoutParams(-2, Ui.dp(this, 44)).apply {
            topMargin = Ui.dp(this@LoginActivity, 12)
        })
        if (wide) content.addView(qrPanel, LinearLayout.LayoutParams(0, -1, 1f))
        else content.addView(qrPanel, LinearLayout.LayoutParams(-1, -2))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = Ui.dp(this@LoginActivity, 8)
        })
        return root
    }

    private fun generate() {
        handler.removeCallbacksAndMessages(null)
        status.text = "正在生成二维码…"
        qrImage.setImageDrawable(null)
        AppExecutors.io.execute {
            runCatching { api.generateQrLogin() }
                .onSuccess { newTicket ->
                    ticket = newTicket
                    val bitmap = runCatching { createQrBitmap(newTicket.url) }.getOrNull()
                    runOnUiThread {
                        if (!active) return@runOnUiThread
                        if (bitmap == null) {
                            status.text = "二维码生成失败，请重试"
                        } else {
                            qrImage.setImageBitmap(bitmap)
                            status.text = "请使用哔哩哔哩客户端扫码确认"
                            schedulePoll(0L)
                        }
                    }
                }
                .onFailure { error -> runOnUiThread { if (active) status.text = "生成失败：${error.message ?: "网络错误"}" } }
        }
    }

    private fun schedulePoll(delayMs: Long) {
        handler.postDelayed({ poll() }, delayMs)
    }

    private fun poll() {
        val current = ticket ?: return
        AppExecutors.io.execute {
            val result = runCatching { api.pollQrLogin(current) }
            runOnUiThread {
                if (!active) return@runOnUiThread
                result.onSuccess { updateStatus(it) }
                    .onFailure { status.text = "轮询失败，稍后重试…" }
                if (active && ticket === current && result.getOrNull()?.loggedIn != true) {
                    schedulePoll(2_500L)
                }
            }
        }
    }

    private fun updateStatus(result: QrLoginStatus) {
        when (result.code) {
            86101 -> status.text = "等待扫码…"
            86090 -> status.text = "已扫码，请在手机上确认登录"
            86038 -> status.text = "二维码已过期，请重新生成"
            0 -> {
                status.text = "登录成功"
                setResult(RESULT_OK)
                active = false
                handler.removeCallbacksAndMessages(null)
                finish()
            }
            else -> status.text = result.message.ifBlank { "登录状态：${result.code}" }
        }
    }

    private fun createQrBitmap(value: String): Bitmap {
        val size = 900
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size, hints)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
            for (x in 0 until size) for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
