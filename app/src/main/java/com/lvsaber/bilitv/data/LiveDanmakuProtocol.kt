package com.lvsaber.bilitv.data

import org.json.JSONArray
import org.json.JSONObject
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Inflater

data class LiveDanmakuPayload(val messageId: String, val text: String)

data class LiveDanmakuPacket(val operation: Int, val version: Int, val body: ByteArray)

/** Bilibili live packet framing. Supports both zlib (v2) and Brotli (v3) server packets. */
object LiveDanmakuProtocol {
    private const val HEADER_LENGTH = 16
    private const val MAX_PACKET_BYTES = 2 * 1024 * 1024
    private const val MAX_DECOMPRESSION_DEPTH = 3

    fun authPacket(roomId: Long, token: String, protocolVersion: Int = 2, buvid3: String = "", uid: Long = 0L): ByteArray {
        require(roomId > 0L)
        val body = JSONObject()
            .put("uid", uid.coerceAtLeast(0L))
            .put("roomid", roomId)
            .put("protover", protocolVersion)
            .put("platform", "web")
            .put("clientver", "1.4.3")
            .put("type", 2)
            .put("key", token)
        if (buvid3.isNotBlank()) body.put("buvid", buvid3)
        val bodyBytes = body.toString()
            .toByteArray(StandardCharsets.UTF_8)
        return packet(operation = 7, version = 1, body = bodyBytes)
    }

    fun heartbeatPacket(): ByteArray = packet(operation = 2, version = 1, body = ByteArray(0))

    fun parsePackets(bytes: ByteArray): List<LiveDanmakuPacket> = parsePackets(bytes, 0)

    fun parseDanmaku(body: ByteArray): LiveDanmakuPayload? = runCatching {
        val event = JSONObject(String(body, StandardCharsets.UTF_8))
        val command = event.optString("cmd").substringBefore(':')
        if (command != "DANMU_MSG") return null
        val info = event.optJSONArray("info") ?: event.optJSONObject("data")?.optJSONArray("info") ?: return null
        val text = info.optString(1).trim()
        if (text.isEmpty() || text == "null") return null
        val metadata = info.optJSONArray(0)
        val messageId = metadata?.let { values ->
            (values.opt(7)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: values.opt(8)?.toString()?.takeIf { it.isNotBlank() && it != "null" })
        }.orEmpty()
        LiveDanmakuPayload(messageId, text)
    }.getOrNull()

    fun authResult(body: ByteArray): Int? = runCatching {
        JSONObject(String(body, StandardCharsets.UTF_8)).takeIf { it.has("code") }?.optInt("code")
    }.getOrNull()

    /** Returns only the event type for bounded diagnostics; never exposes a user's message text. */
    fun commandName(body: ByteArray): String? = runCatching {
        JSONObject(String(body, StandardCharsets.UTF_8)).optString("cmd")
            .substringBefore(':').takeIf(String::isNotBlank)
    }.getOrNull()

    private fun parsePackets(bytes: ByteArray, depth: Int): List<LiveDanmakuPacket> {
        if (bytes.size > MAX_PACKET_BYTES || depth > MAX_DECOMPRESSION_DEPTH) return emptyList()
        val result = mutableListOf<LiveDanmakuPacket>()
        var offset = 0
        while (offset + HEADER_LENGTH <= bytes.size) {
            val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.BIG_ENDIAN)
            val packetLength = buffer.int
            val headerLength = buffer.short.toInt() and 0xFFFF
            val version = buffer.short.toInt() and 0xFFFF
            val operation = buffer.int
            buffer.int // sequence number
            if (packetLength < headerLength || headerLength < HEADER_LENGTH || packetLength > MAX_PACKET_BYTES) break
            if (offset + packetLength > bytes.size) break
            val body = bytes.copyOfRange(offset + headerLength, offset + packetLength)
            if (operation == 5 && version == 2) {
                val inflated = inflate(body)
                result += parsePackets(inflated, depth + 1)
            } else if (operation == 5 && version == 3) {
                val inflated = inflateBrotli(body)
                result += parsePackets(inflated, depth + 1)
            } else {
                result += LiveDanmakuPacket(operation, version, body)
            }
            offset += packetLength
        }
        return result
    }

    private fun packet(operation: Int, version: Int, body: ByteArray): ByteArray {
        val result = ByteBuffer.allocate(HEADER_LENGTH + body.size).order(ByteOrder.BIG_ENDIAN)
        result.putInt(HEADER_LENGTH + body.size)
        result.putShort(HEADER_LENGTH.toShort())
        result.putShort(version.toShort())
        result.putInt(operation)
        result.putInt(1)
        result.put(body)
        return result.array()
    }

    private fun inflate(compressed: ByteArray): ByteArray {
        val inflater = Inflater()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            inflater.setInput(compressed)
            while (!inflater.finished() && output.size() <= MAX_PACKET_BYTES) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (error: DataFormatException) {
                    throw IllegalArgumentException("直播弹幕压缩数据无效", error)
                }
                if (count > 0) output.write(buffer, 0, count)
                else if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            if (output.size() > MAX_PACKET_BYTES) throw IllegalArgumentException("直播弹幕包超出限制")
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun inflateBrotli(compressed: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        BrotliInputStream(ByteArrayInputStream(compressed)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_PACKET_BYTES) throw IllegalArgumentException("直播弹幕包超出限制")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }
}
