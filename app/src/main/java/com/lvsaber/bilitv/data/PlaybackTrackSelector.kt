package com.lvsaber.bilitv.data

/**
 * Selects one DASH video track while keeping the player UI resolution-only.
 * Codec preference belongs to settings, never to the quality dialog.
 */
object PlaybackTrackSelector {
    fun candidates(tracks: List<VideoTrack>, settings: AppSettings): List<VideoTrack> {
        val deviceTracks = tracks.filter { it.deviceSupported }
        val allowedTracks = if (settings.videoCodec == AppSettings.VideoCodec.AUTO) {
            // Experimental mode is an explicit escape hatch, not an instruction for
            // automatic mode to select an unverified codec such as AV1.
            deviceTracks
        } else {
            tracks.filter { it.deviceSupported || settings.experimentalQualityEnabled }
        }
        return when (settings.videoCodec) {
            AppSettings.VideoCodec.AUTO -> allowedTracks
            AppSettings.VideoCodec.AVC -> allowedTracks.filter { it.isCodec(PlaybackCodec.AVC) }
            AppSettings.VideoCodec.HEVC -> allowedTracks.filter { it.isCodec(PlaybackCodec.HEVC) }
            AppSettings.VideoCodec.AV1 -> allowedTracks.filter { it.isCodec(PlaybackCodec.AV1) }
        }
    }

    fun selectableTracks(tracks: List<VideoTrack>, codec: AppSettings.VideoCodec): List<VideoTrack> =
        when (codec) {
            AppSettings.VideoCodec.AUTO -> tracks
            AppSettings.VideoCodec.AVC -> tracks.filter { it.isCodec(PlaybackCodec.AVC) }
            AppSettings.VideoCodec.HEVC -> tracks.filter { it.isCodec(PlaybackCodec.HEVC) }
            AppSettings.VideoCodec.AV1 -> tracks.filter { it.isCodec(PlaybackCodec.AV1) }
        }

    fun select(tracks: List<VideoTrack>, settings: AppSettings): VideoTrack? {
        val candidates = candidates(tracks, settings)
        if (candidates.isEmpty()) return null
        val targetHeight = qualityTargetHeight(settings.defaultQuality)
        val targetId = settings.defaultQuality.takeIf { it > 0 }
        val exact = targetId?.let { id -> candidates.filter { it.id == id } }.orEmpty()
        val pool = exact.ifEmpty { candidates }
        return pool.sortedWith(
            compareBy<VideoTrack> {
                if (targetHeight == Int.MAX_VALUE || it.height <= targetHeight) 0 else 1
            }
                .thenByDescending {
                    if (targetHeight == Int.MAX_VALUE || it.height <= targetHeight) it.height else -it.height
                }
                .thenBy { codecPriority(it) }
                .thenByDescending { it.id }
                .thenByDescending { it.bandwidth },
        ).firstOrNull()
    }

    fun codecPriority(track: VideoTrack): Int = when {
        track.isCodec(PlaybackCodec.AVC) -> 0
        track.isCodec(PlaybackCodec.HEVC) -> 1
        track.isCodec(PlaybackCodec.AV1) -> 2
        else -> 3
    }

    fun qualityTargetHeight(id: Int): Int = when {
        id <= 0 -> Int.MAX_VALUE
        id == 6 -> 240
        id == 16 -> 360
        id == 32 -> 480
        id == 64 || id == 74 -> 720
        id in setOf(80, 112, 116) -> 1080
        id in setOf(120, 125, 126, 129) -> 2160
        id == 127 -> 4320
        else -> 1080
    }

    private fun VideoTrack.isCodec(codec: PlaybackCodec): Boolean =
        PlaybackCodec.fromCodecString(codecs) == codec
}
