package com.lvsaber.blblrebuild.data

import android.content.Context
import android.graphics.Point
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * Runtime playback capabilities. Nothing here is Shield-specific: the values
 * are discovered from the current Android device, decoder list and display.
 */
data class PlaybackDeviceCapabilities(
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxFrameRate: Float = 0f,
    val supportedCodecs: Set<PlaybackCodec> = emptySet(),
    val supportedHdrTypes: Set<Int> = emptySet(),
    val codecLimits: Map<PlaybackCodec, CodecPlaybackLimit> = emptyMap(),
    val probeAvailable: Boolean = true,
) {
    fun supports(quality: PlaybackQuality): DeviceSupport {
        val width = quality.width.takeIf { it > 0 } ?: quality.inferredWidth()
        val height = quality.height.takeIf { it > 0 } ?: quality.inferredHeight()
        if (maxWidth > 0 && width > 0 && width > maxWidth) {
            return DeviceSupport(false, "超过设备/显示器最大宽度")
        }
        if (maxHeight > 0 && height > 0 && height > maxHeight) {
            return DeviceSupport(false, "超过设备/显示器最大高度")
        }
        val knownCodecs = quality.codecs.mapNotNull { PlaybackCodec.fromCodecString(it) }.toSet()
        if (knownCodecs.isNotEmpty() && supportedCodecs.isNotEmpty() && knownCodecs.intersect(supportedCodecs).isEmpty()) {
            return DeviceSupport(false, "设备未声明支持该视频编码")
        }
        val codecLimited = knownCodecs.mapNotNull { codec ->
            codecLimits[codec]?.let { codec to it }
        }
        if (codecLimited.isNotEmpty() && codecLimited.none { (_, limit) -> limit.accepts(width, height, 0f, maxWidth, maxHeight) }) {
            return DeviceSupport(false, "该编码超过设备能力")
        }
        if (probeAvailable && quality.isDolbyVision &&
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION !in supportedHdrTypes
        ) {
            return DeviceSupport(false, "显示器未声明支持 Dolby Vision")
        }
        if (probeAvailable && quality.isHdr && !quality.isDolbyVision &&
            setOf(
                Display.HdrCapabilities.HDR_TYPE_HDR10,
                Display.HdrCapabilities.HDR_TYPE_HLG,
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
            ).intersect(supportedHdrTypes).isEmpty()
        ) {
            return DeviceSupport(false, "显示器未声明支持 HDR")
        }
        return DeviceSupport(true, null)
    }

    fun supports(track: VideoTrack): DeviceSupport {
        val codec = PlaybackCodec.fromCodecString(track.codecs)
        val codecLimit = codec?.let { codecLimits[it] }
        val widthLimit = minPositive(maxWidth, codecLimit?.maxWidth ?: 0)
        val heightLimit = minPositive(maxHeight, codecLimit?.maxHeight ?: 0)
        val frameRateLimit = minPositive(maxFrameRate, codecLimit?.maxFrameRate ?: 0f)
        if (widthLimit > 0 && track.width > 0 && track.width > widthLimit) {
            return DeviceSupport(false, "超过设备/显示器最大宽度")
        }
        if (heightLimit > 0 && track.height > 0 && track.height > heightLimit) {
            return DeviceSupport(false, "超过设备/显示器最大高度")
        }
        if (frameRateLimit > 0f && track.frameRate > frameRateLimit + 0.5f) {
            return DeviceSupport(false, "超过设备支持的帧率")
        }
        if (codec != null && supportedCodecs.isNotEmpty() && codec !in supportedCodecs) {
            return DeviceSupport(false, "设备未声明支持 ${codec.label}")
        }
        if (probeAvailable && track.isDolbyVision &&
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION !in supportedHdrTypes
        ) {
            return DeviceSupport(false, "显示器未声明支持 Dolby Vision")
        }
        if (probeAvailable && track.isHdr && !track.isDolbyVision &&
            setOf(
                Display.HdrCapabilities.HDR_TYPE_HDR10,
                Display.HdrCapabilities.HDR_TYPE_HLG,
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
            ).intersect(supportedHdrTypes).isEmpty()
        ) {
            return DeviceSupport(false, "显示器未声明支持 HDR")
        }
        return DeviceSupport(true, null)
    }

    private fun minPositive(first: Int, second: Int): Int = when {
        first <= 0 -> second
        second <= 0 -> first
        else -> min(first, second)
    }

    private fun minPositive(first: Float, second: Float): Float = when {
        first <= 0f -> second
        second <= 0f -> first
        else -> min(first, second)
    }

    private fun PlaybackQuality.inferredWidth(): Int = when (inferredHeight()) {
        2160 -> 3840
        4320 -> 7680
        else -> 0
    }

    private fun PlaybackQuality.inferredHeight(): Int = when {
        height > 0 -> height
        id == 6 -> 240
        id == 16 -> 360
        id == 32 -> 480
        id == 64 || id == 74 -> 720
        id in setOf(80, 112, 116) -> 1080
        id in setOf(120, 125, 126, 129) -> 2160
        id == 127 -> 4320
        else -> 0
    }

    companion object {
        fun detect(context: Context): PlaybackDeviceCapabilities {
            val display = displayOf(context)
            val displaySize = displaySize(display)
            var decoderWidth = 0
            var decoderHeight = 0
            var decoderFrameRate = 0f
            val codecs = linkedSetOf<PlaybackCodec>()
            val codecLimits = linkedMapOf<PlaybackCodec, CodecPlaybackLimit>()

            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filterNot { it.isEncoder }
                    .forEach { codecInfo ->
                        for (mime in codecInfo.supportedTypes.filter { it.startsWith("video/", ignoreCase = true) }) {
                            val codec = PlaybackCodec.fromMimeType(mime) ?: continue
                            val caps = codecInfo.getCapabilitiesForType(mime)
                            val video = caps.videoCapabilities ?: continue
                            codecs += codec
                            decoderWidth = max(decoderWidth, video.supportedWidths.upper)
                            decoderHeight = max(decoderHeight, video.supportedHeights.upper)
                            decoderFrameRate = max(decoderFrameRate, video.supportedFrameRates.upper.toFloat())
                            val previous = codecLimits[codec]
                            codecLimits[codec] = CodecPlaybackLimit(
                                maxWidth = max(previous?.maxWidth ?: 0, video.supportedWidths.upper),
                                maxHeight = max(previous?.maxHeight ?: 0, video.supportedHeights.upper),
                                maxFrameRate = max(previous?.maxFrameRate ?: 0f, video.supportedFrameRates.upper.toFloat()),
                            )
                        }
                    }
            }

            val maxWidth = limitByDisplay(decoderWidth, displaySize.x)
            val maxHeight = limitByDisplay(decoderHeight, displaySize.y)
            val hdrTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                display?.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
            } else {
                emptySet()
            }
            return PlaybackDeviceCapabilities(
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                maxFrameRate = decoderFrameRate,
                supportedCodecs = codecs,
                supportedHdrTypes = hdrTypes,
                codecLimits = codecLimits,
                probeAvailable = true,
            )
        }

        /** Unit tests and callers without an Android display can opt out of filtering. */
        fun unrestricted(): PlaybackDeviceCapabilities = PlaybackDeviceCapabilities(probeAvailable = false)

        private fun displayOf(context: Context): Display? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return context.getSystemService(WindowManager::class.java)?.defaultDisplay
            }
            @Suppress("DEPRECATION")
            return (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }

        private fun displaySize(display: Display?): Point {
            if (display == null) return Point(0, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val modes = display.supportedModes
                val mode = modes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
                if (mode != null) return Point(mode.physicalWidth, mode.physicalHeight)
            }
            val point = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            return point
        }

        private fun limitByDisplay(decoderLimit: Int, displayLimit: Int): Int = when {
            decoderLimit <= 0 -> displayLimit
            displayLimit <= 0 -> decoderLimit
            else -> min(decoderLimit, displayLimit)
        }
    }
}

data class DeviceSupport(val supported: Boolean, val reason: String?)

data class CodecPlaybackLimit(val maxWidth: Int, val maxHeight: Int, val maxFrameRate: Float) {
    fun accepts(width: Int, height: Int, frameRate: Float, displayWidth: Int, displayHeight: Int): Boolean {
        val widthLimit = when {
            displayWidth <= 0 -> maxWidth
            maxWidth <= 0 -> displayWidth
            else -> min(displayWidth, maxWidth)
        }
        val heightLimit = when {
            displayHeight <= 0 -> maxHeight
            maxHeight <= 0 -> displayHeight
            else -> min(displayHeight, maxHeight)
        }
        return (widthLimit <= 0 || width <= 0 || width <= widthLimit) &&
            (heightLimit <= 0 || height <= 0 || height <= heightLimit) &&
            (maxFrameRate <= 0f || frameRate <= 0f || frameRate <= maxFrameRate + 0.5f)
    }
}

enum class PlaybackCodec(val label: String) {
    AVC("AVC / H.264"),
    HEVC("HEVC / H.265"),
    AV1("AV1");

    companion object {
        fun fromCodecString(value: String): PlaybackCodec? = when {
            value.startsWith("avc", ignoreCase = true) -> AVC
            value.startsWith("hev", ignoreCase = true) || value.startsWith("hvc", ignoreCase = true) -> HEVC
            value.startsWith("av01", ignoreCase = true) -> AV1
            else -> null
        }

        fun fromMimeType(value: String): PlaybackCodec? = when (value.lowercase()) {
            "video/avc" -> AVC
            "video/hevc", "video/h265" -> HEVC
            "video/av01", "video/av1" -> AV1
            else -> null
        }
    }
}
