package com.lvsaber.bilitv.data

/** Global presentation preferences; invalid persisted values safely use defaults. */
data class DanmakuSettings(
    val areaPercent: Int = 50,
    val speed: Float = 1f,
    val textScale: Float = 1f,
    val opacityPercent: Int = 75,
    val maxVisible: Int = 24,
) {
    fun normalized(): DanmakuSettings = copy(
        areaPercent = areaPercent.takeIf { it in AREAS } ?: 50,
        speed = speed.takeIf { it in SPEEDS } ?: 1f,
        textScale = textScale.takeIf { it in TEXT_SCALES } ?: 1f,
        opacityPercent = opacityPercent.takeIf { it in OPACITIES } ?: 75,
        maxVisible = maxVisible.takeIf { it in DENSITIES } ?: 24,
    )

    val durationMs: Long get() = (6000 / normalized().speed).toLong()

    companion object {
        val AREAS = listOf(25, 50, 75, 100)
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val TEXT_SCALES = listOf(0.8f, 1f, 1.25f)
        val OPACITIES = listOf(25, 50, 75, 100)
        val DENSITIES = listOf(12, 24, 40)
    }
}
