package com.lvsaber.bilitv.ui

/** A concrete destination for D-pad navigation in the PGC season/range/episode selector. */
internal sealed class PgcDetailFocusTarget {
    data class Episode(val index: Int) : PgcDetailFocusTarget()
    data class Range(val index: Int) : PgcDetailFocusTarget()
    data class Season(val index: Int) : PgcDetailFocusTarget()
    object Play : PgcDetailFocusTarget()
    object Stay : PgcDetailFocusTarget()
}

internal enum class PgcFocusDirection { LEFT, RIGHT, UP, DOWN }

internal object PgcDetailFocusPolicy {
    fun episode(
        index: Int,
        itemCount: Int,
        columns: Int,
        direction: PgcFocusDirection,
        rangeCount: Int,
        seasonCount: Int,
        selectedRange: Int,
        selectedSeason: Int,
    ): PgcDetailFocusTarget {
        if (index !in 0 until itemCount || columns <= 0) return PgcDetailFocusTarget.Stay
        val target = when (direction) {
            PgcFocusDirection.LEFT -> index - 1
            PgcFocusDirection.RIGHT -> index + 1
            PgcFocusDirection.UP -> index - columns
            PgcFocusDirection.DOWN -> index + columns
        }
        val sameRow = direction !in setOf(PgcFocusDirection.LEFT, PgcFocusDirection.RIGHT) || target / columns == index / columns
        if (target in 0 until itemCount && sameRow) return PgcDetailFocusTarget.Episode(target)
        return when (direction) {
            PgcFocusDirection.UP -> when {
                rangeCount > 1 -> PgcDetailFocusTarget.Range(selectedRange.coerceIn(0, rangeCount - 1))
                seasonCount > 1 -> PgcDetailFocusTarget.Season(selectedSeason.coerceIn(0, seasonCount - 1))
                else -> PgcDetailFocusTarget.Play
            }
            // Do not let focus escape the episode grid at its right/left or bottom edge.
            PgcFocusDirection.LEFT, PgcFocusDirection.RIGHT, PgcFocusDirection.DOWN -> PgcDetailFocusTarget.Stay
        }
    }

    fun range(
        index: Int,
        rangeCount: Int,
        seasonCount: Int,
        selectedSeason: Int,
        direction: PgcFocusDirection,
    ): PgcDetailFocusTarget {
        if (index !in 0 until rangeCount) return PgcDetailFocusTarget.Stay
        return when (direction) {
            PgcFocusDirection.LEFT -> if (index > 0) PgcDetailFocusTarget.Range(index - 1) else PgcDetailFocusTarget.Stay
            PgcFocusDirection.RIGHT -> if (index + 1 < rangeCount) PgcDetailFocusTarget.Range(index + 1) else PgcDetailFocusTarget.Stay
            PgcFocusDirection.UP -> if (seasonCount > 1) PgcDetailFocusTarget.Season(selectedSeason.coerceIn(0, seasonCount - 1)) else PgcDetailFocusTarget.Play
            PgcFocusDirection.DOWN -> PgcDetailFocusTarget.Episode(0)
        }
    }

    fun season(
        index: Int,
        seasonCount: Int,
        rangeCount: Int,
        selectedRange: Int,
        direction: PgcFocusDirection,
    ): PgcDetailFocusTarget {
        if (index !in 0 until seasonCount) return PgcDetailFocusTarget.Stay
        return when (direction) {
            PgcFocusDirection.LEFT -> if (index > 0) PgcDetailFocusTarget.Season(index - 1) else PgcDetailFocusTarget.Stay
            PgcFocusDirection.RIGHT -> if (index + 1 < seasonCount) PgcDetailFocusTarget.Season(index + 1) else PgcDetailFocusTarget.Stay
            PgcFocusDirection.UP -> PgcDetailFocusTarget.Play
            PgcFocusDirection.DOWN -> if (rangeCount > 1) PgcDetailFocusTarget.Range(selectedRange.coerceIn(0, rangeCount - 1)) else PgcDetailFocusTarget.Episode(0)
        }
    }

    fun firstSelector(rangeCount: Int, seasonCount: Int, selectedRange: Int, selectedSeason: Int): PgcDetailFocusTarget = when {
        seasonCount > 1 -> PgcDetailFocusTarget.Season(selectedSeason.coerceIn(0, seasonCount - 1))
        rangeCount > 1 -> PgcDetailFocusTarget.Range(selectedRange.coerceIn(0, rangeCount - 1))
        else -> PgcDetailFocusTarget.Episode(0)
    }
}
