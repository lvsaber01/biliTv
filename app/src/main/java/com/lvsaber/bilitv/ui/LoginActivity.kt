package com.lvsaber.bilitv.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())
    private var ticket: QrLoginTicket? = null
    private var active = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        api = BiliApiClient(this)
        setContentView(buildContent())
        generate()
    }

    private fun buildContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(context, 24), Ui.dp(context, 18), Ui.dp(context, 24), Ui.dp(context, 18))
        setBackgroundColor(Ui.background)
        addView(Ui.text(context, "二维码登录", 28f, Ui.text), LinearLayout.LayoutParams(-1, Ui.dp(context, 54)))
        status = Ui.text(context, "正在生成二维码…", 15f, Ui.muted)
        addView(status, LinearLayout.LayoutParams(-1, Ui.dp(context, 48)))
        qrImage = ImageView(context).apply {
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isFocusable = true
        }
        val qrParams = LinearLayout.LayoutParams(Ui.dp(context, 300), Ui.dp(context, 300))
        qrParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
        addView(qrImage, qrParams)
        val retry = Ui.button(context, "重新生成") { generate() }
        val retryParams = LinearLayout.LayoutParams(-2, Ui.dp(context, 48))
        retryParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
        retryParams.topMargin = Ui.dp(context, 12)
        addView(retry, retryParams)
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
