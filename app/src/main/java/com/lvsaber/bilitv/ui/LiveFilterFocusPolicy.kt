package com.lvsaber.bilitv.ui

internal sealed class LiveFilterFocusTarget {
    data class Parent(val index: Int) : LiveFilterFocusTarget()
    data class Child(val index: Int) : LiveFilterFocusTarget()
    object Apply : LiveFilterFocusTarget()
    object Stay : LiveFilterFocusTarget()
}

/** Prevents D-pad focus from escaping the modal live-area selector at list boundaries. */
internal object LiveFilterFocusPolicy {
    fun parent(index: Int, count: Int, direction: PgcFocusDirection): LiveFilterFocusTarget {
        if (index !in 0 until count) return LiveFilterFocusTarget.Stay
        return when (direction) {
            PgcFocusDirection.UP -> if (index > 0) LiveFilterFocusTarget.Parent(index - 1) else LiveFilterFocusTarget.Apply
            PgcFocusDirection.DOWN -> if (index + 1 < count) LiveFilterFocusTarget.Parent(index + 1) else LiveFilterFocusTarget.Stay
            PgcFocusDirection.LEFT, PgcFocusDirection.RIGHT -> LiveFilterFocusTarget.Stay
        }
    }

    fun child(index: Int, count: Int, parentIndex: Int, direction: PgcFocusDirection): LiveFilterFocusTarget {
        if (index !in 0 until count) return LiveFilterFocusTarget.Stay
        return when (direction) {
            PgcFocusDirection.UP -> if (index > 0) LiveFilterFocusTarget.Child(index - 1) else LiveFilterFocusTarget.Parent(parentIndex)
            PgcFocusDirection.DOWN -> if (index + 1 < count) LiveFilterFocusTarget.Child(index + 1) else LiveFilterFocusTarget.Stay
            PgcFocusDirection.LEFT, PgcFocusDirection.RIGHT -> LiveFilterFocusTarget.Stay
        }
    }
}
