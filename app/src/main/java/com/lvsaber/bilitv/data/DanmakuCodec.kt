package com.lvsaber.bilitv.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** The XML endpoint uses raw DEFLATE; some edges return zlib, gzip, or identity. */
object DanmakuCodec {
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun readBounded(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "弹幕数据超过大小限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, encoding: String?): String {
        require(bytes.size <= MAX_BYTES) { "弹幕数据超过大小限制" }
        fun xml(data: ByteArray): String = data.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            .also { require(it.startsWith("<?xml") || it.startsWith("<i>")) { "弹幕接口未返回 XML" } }
        // Android can transparently decode gzip; accept an already decoded body first.
        runCatching { xml(bytes) }.getOrNull()?.let { return it }
        if (encoding.equals("gzip", true) || bytes.take(2) == listOf(0x1f.toByte(), 0x8b.toByte())) {
            return GZIPInputStream(ByteArrayInputStream(bytes)).use { xml(readBounded(it)) }
        }
        for (raw in listOf(true, false)) {
            val inflater = Inflater(raw)
            try {
                val decoded = InflaterInputStream(ByteArrayInputStream(bytes), inflater).use { xml(readBounded(it)) }
                return decoded
            } catch (_: Exception) {
                // Try the other DEFLATE framing, then surface a useful failure.
            } finally { inflater.end() }
        }
        error("无法解压弹幕数据")
    }
}
