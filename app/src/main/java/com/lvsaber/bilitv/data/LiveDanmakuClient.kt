package com.lvsaber.bilitv.data

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class LiveDanmakuState { CONNECTING, CONNECTED, RECONNECTING, UNAVAILABLE }

/** Receive-only WebSocket client. The player owns its lifetime; comments never control playback. */
class LiveDanmakuClient(
    private val api: BiliApiClient,
    private val roomId: Long,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: LiveDanmakuState, detail: String)
        fun onMessage(text: String)
    }

    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var socket: SSLSocket? = null
    private var task: Future<*>? = null
    private val seenMessageIds = LinkedHashSet<String>()
    private var diagnosticMessagesLogged = 0
    private var diagnosticPacketsLogged = 0

    fun connect() {
        synchronized(lock) {
            if (running) return
            running = true
            try {
                task = executor.submit { reconnectLoop() }
            } catch (_: RejectedExecutionException) {
                running = false
                notifyState(LiveDanmakuState.UNAVAILABLE, "弹幕连接资源繁忙")
            }
        }
    }

    fun disconnect() {
        running = false
        runCatching { socket?.close() }
        synchronized(lock) {
            task?.cancel(true)
            task = null
        }
    }

    private fun reconnectLoop() {
        var failures = 0
        while (running) {
            notifyState(if (failures == 0) LiveDanmakuState.CONNECTING else LiveDanmakuState.RECONNECTING,
                if (failures == 0) "正在连接弹幕" else "弹幕断开，正在重连")
            try {
                val config = api.getLiveDanmakuConfig(roomId)
                var connectedToHost = false
                var lastFailure: Throwable? = null
                for (host in config.hosts) {
                    if (!running) return
                    try {
                        openConnection(host.host, host.wssPort, config.token, config.buvid3, config.uid)
                        connectedToHost = true
                        break
                    } catch (error: Exception) {
                        if (!running) return
                        lastFailure = error
                        closeSocket()
                    }
                }
                if (!connectedToHost) throw (lastFailure ?: IOException("弹幕服务器不可用"))
                failures = 0
            } catch (error: Exception) {
                if (!running) return
                failures++
                Log.w(TAG, "Live danmaku connection failed: ${safeReason(error)} (${error.javaClass.simpleName})")
                if (failures >= MAX_RECONNECTS) {
                    notifyState(LiveDanmakuState.UNAVAILABLE, safeReason(error))
                    return
                }
                val delaySeconds = (1 shl (failures - 1)).coerceAtMost(16)
                notifyState(LiveDanmakuState.RECONNECTING, "${safeReason(error)} · ${delaySeconds} 秒后重试")
                try {
                    Thread.sleep(delaySeconds * 1_000L)
                } catch (_: InterruptedException) {
                    if (!running) return
                }
            }
        }
    }

    private fun openConnection(host: String, port: Int, token: String, buvid3: String, uid: Long) {
        val created = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
        socket = created
        created.soTimeout = HANDSHAKE_TIMEOUT_MS
        created.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
        created.sslParameters = created.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        created.startHandshake()
        val keyBytes = ByteArray(16).also(secureRandom::nextBytes)
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val output = created.outputStream
        val hostHeader = if (port == 443) host else "$host:$port"
        val request = buildString {
            append("GET /sub HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Origin: https://live.bilibili.com\r\n")
            append("User-Agent: ").append(WEBSOCKET_USER_AGENT).append("\r\n\r\n")
        }
        output.write(request.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val headers = readHandshakeHeaders(created.inputStream)
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(StandardCharsets.US_ASCII)),
            Base64.NO_WRAP,
        )
        if (!headers.status.startsWith("HTTP/1.1 101") && !headers.status.startsWith("HTTP/1.0 101")) {
            throw IOException("WebSocket 握手失败：${headers.status.take(80)}")
        }
        if (!headers.values["sec-websocket-accept"].equals(expected, ignoreCase = false)) {
            throw IOException("WebSocket 握手校验失败")
        }
        created.soTimeout = READ_TIMEOUT_MS
        sendFrame(output, opcode = 2, payload = LiveDanmakuProtocol.authPacket(roomId, token, buvid3 = buvid3, uid = uid))
        readConnection(created, output)
    }

    private fun readConnection(created: SSLSocket, output: OutputStream) {
        var lastHeartbeat = System.currentTimeMillis()
        var authenticated = false
        var fragmentOpcode = -1
        var fragments = ByteArrayOutputStream()
        while (running && !created.isClosed) {
            val frame = try {
                readFrame(created.inputStream)
            } catch (_: SocketTimeoutException) {
                val now = System.currentTimeMillis()
                if (liveHeartbeatDue(lastHeartbeat, now, HEARTBEAT_INTERVAL_MS)) {
                    sendFrame(output, 2, LiveDanmakuProtocol.heartbeatPacket())
                    lastHeartbeat = now
                }
                continue
            }
            // A busy room can keep readFrame() returning continuously, so relying only on
            // socket timeouts would suppress heartbeats until Bilibili closes the connection.
            val now = System.currentTimeMillis()
            if (liveHeartbeatDue(lastHeartbeat, now, HEARTBEAT_INTERVAL_MS)) {
                sendFrame(output, 2, LiveDanmakuProtocol.heartbeatPacket())
                lastHeartbeat = now
            }
            if (frame == null) throw IOException("弹幕服务器关闭了连接")
            when (frame.opcode) {
                8 -> {
                    val closeCode = if (frame.payload.size >= 2) {
                        ByteBuffer.wrap(frame.payload, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                    } else 0
                    val closeReason = if (frame.payload.size > 2) {
                        String(frame.payload, 2, frame.payload.size - 2, StandardCharsets.UTF_8)
                            .filterNot(Char::isISOControl).take(60)
                    } else ""
                    throw IOException(buildString {
                        append("弹幕服务器关闭连接")
                        if (closeCode > 0) append("（$closeCode")
                        if (closeReason.isNotBlank()) append(if (closeCode > 0) "：" else "（").append(closeReason)
                        if (closeCode > 0 || closeReason.isNotBlank()) append("）")
                    })
                }
                9 -> sendFrame(output, 10, frame.payload)
                10 -> Unit
                0, 2 -> {
                    if (frame.opcode == 2) {
                        fragmentOpcode = frame.opcode
                        fragments = ByteArrayOutputStream()
                    }
                    if (fragmentOpcode == -1) continue
                    if (fragments.size() + frame.payload.size > MAX_WEBSOCKET_MESSAGE_BYTES) {
                        throw IOException("弹幕消息过大")
                    }
                    fragments.write(frame.payload)
                    if (frame.fin) {
                        val payload = fragments.toByteArray()
                        fragmentOpcode = -1
                        for (packet in LiveDanmakuProtocol.parsePackets(payload)) {
                            if (diagnosticPacketsLogged < MAX_DIAGNOSTIC_PACKETS) {
                                diagnosticPacketsLogged++
                                val command = LiveDanmakuProtocol.commandName(packet.body) ?: "-"
                                Log.i(TAG, "Live packet #$diagnosticPacketsLogged op=${packet.operation} version=${packet.version} command=$command bytes=${packet.body.size}")
                            }
                            when (packet.operation) {
                                8 -> {
                                    val result = LiveDanmakuProtocol.authResult(packet.body)
                                    Log.i(TAG, "Danmaku auth response code=${result ?: "missing"}")
                                    if (result != 0) throw IOException("弹幕服务端拒绝连接${result?.let { "（$it）" }.orEmpty()}")
                                    authenticated = true
                                    notifyState(LiveDanmakuState.CONNECTED, "弹幕已连接")
                                }
                                5 -> dispatchDanmaku(packet.body)
                            }
                        }
                    }
                }
            }
        }
        if (running && authenticated) throw IOException("弹幕连接中断")
    }

    private fun dispatchDanmaku(body: ByteArray) {
        val message = LiveDanmakuProtocol.parseDanmaku(body) ?: return
        val id = message.messageId
        if (id.isNotBlank()) {
            synchronized(seenMessageIds) {
                if (!seenMessageIds.add(id)) return
                if (seenMessageIds.size > MAX_DEDUPLICATION_IDS) {
                    val iterator = seenMessageIds.iterator()
                    repeat(seenMessageIds.size - MAX_DEDUPLICATION_IDS) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
                }
            }
        }
        if (diagnosticMessagesLogged < 3) {
            diagnosticMessagesLogged++
            Log.i(TAG, "Received live danmaku message #$diagnosticMessagesLogged")
        }
        listener.onMessage(message.text)
    }

    private fun readHandshakeHeaders(input: InputStream): HandshakeHeaders {
        val lines = mutableListOf<String>()
        var totalBytes = 0
        while (totalBytes < MAX_HANDSHAKE_BYTES) {
            val line = readLiveWebSocketAsciiLine(input, MAX_HEADER_LINE_BYTES) ?: throw IOException("WebSocket 握手响应不完整")
            totalBytes += line.length + 2
            if (line.isEmpty()) break
            lines += line
        }
        if (totalBytes >= MAX_HANDSHAKE_BYTES || lines.isEmpty()) throw IOException("WebSocket 握手响应无效")
        val values = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) values[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        return HandshakeHeaders(lines.first(), values)
    }

    private fun readFrame(input: InputStream): WebSocketFrame? {
        val first = input.read()
        if (first < 0) return null
        val second = readByte(input)
        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        val masked = second and 0x80 != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) length = ((readByte(input) shl 8) or readByte(input)).toLong()
        else if (length == 127L) {
            length = 0L
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }
        if (length < 0 || length > MAX_WEBSOCKET_MESSAGE_BYTES) throw IOException("WebSocket 消息超出限制")
        val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(length.toInt())
        readFully(input, payload)
        if (mask != null) for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        return WebSocketFrame(fin, opcode, payload)
    }

    private fun sendFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        if (payload.size > MAX_WEBSOCKET_MESSAGE_BYTES) throw IOException("WebSocket 消息超出限制")
        val mask = ByteArray(4).also(secureRandom::nextBytes)
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write(payload.size ushr 8)
                header.write(payload.size)
            }
            else -> {
                header.write(0x80 or 127)
                header.write(ByteArray(4))
                header.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
            }
        }
        header.write(mask)
        val masked = ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }

    private fun readByte(input: InputStream): Int = input.read().takeIf { it >= 0 } ?: throw IOException("WebSocket 帧不完整")

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw IOException("WebSocket 帧不完整")
            offset += count
        }
    }

    private fun closeSocket() {
        val old = socket
        socket = null
        runCatching { old?.close() }
    }

    private fun notifyState(state: LiveDanmakuState, detail: String) {
        runCatching { listener.onState(state, detail) }
    }

    private fun safeReason(error: Throwable): String = when (error) {
        is BiliApiException -> when (error.apiCode) {
            -101, -111 -> "登录状态失效，弹幕暂不可用"
            -352, -412 -> "B 站暂时拒绝弹幕连接，请稍后重试"
            else -> "弹幕暂不可用（接口 ${error.apiCode}）"
        }
        else -> error.message?.take(80)?.ifBlank { null } ?: "网络连接异常"
    }

    private data class HandshakeHeaders(val status: String, val values: Map<String, String>)
    private data class WebSocketFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    companion object {
        private const val TAG = "LiveDanmaku"
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val WEBSOCKET_USER_AGENT = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MAX_RECONNECTS = 5
        private const val MAX_HANDSHAKE_BYTES = 16 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_WEBSOCKET_MESSAGE_BYTES = 2 * 1024 * 1024
        private const val MAX_DEDUPLICATION_IDS = 256
        private const val MAX_DIAGNOSTIC_PACKETS = 8
        private val secureRandom = SecureRandom()
        private val threadNumber = AtomicInteger()
        private val executor = ThreadPoolExecutor(
            1,
            2,
            60L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(2),
            ThreadFactory { runnable ->
                Thread(runnable, "LiveDanmaku-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}

internal fun liveHeartbeatDue(lastSentMs: Long, nowMs: Long, intervalMs: Long): Boolean =
    nowMs - lastSentMs >= intervalMs

internal fun readLiveWebSocketAsciiLine(input: InputStream, maxLineBytes: Int): String? {
    require(maxLineBytes > 0)
    val bytes = ByteArrayOutputStream()
    while (bytes.size() < maxLineBytes) {
        val value = input.read()
        if (value < 0) {
            return if (bytes.size() == 0) null else throw IOException("WebSocket 响应行被截断")
        }
        if (value == '\n'.code) {
            return String(bytes.toByteArray(), StandardCharsets.US_ASCII).removeSuffix("\r")
        }
        bytes.write(value)
    }
    throw IOException("WebSocket 响应头过长")
}
