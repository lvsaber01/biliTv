package com.lvsaber.bilitv.ui

import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView

/** Explicit D-pad links for the search field, suggestion rows and in-page results grid. */
internal object SearchFocusPolicy {
    fun connect(
        input: EditText,
        submit: View,
        hotWords: List<View>,
        history: List<View>,
        results: RecyclerView,
        topSearchTab: View? = null,
    ) {
        listOf(input, submit, results).forEach(::ensureId)
        (hotWords + history).forEach(::ensureId)
        topSearchTab?.let(::ensureId)

        val firstSuggestion = hotWords.firstOrNull() ?: history.firstOrNull()
        val lastHistoryTerm = history.lastOrNull { it.tag?.toString() != "video-search-history-clear" }
        val downFallback = firstSuggestion?.id ?: submit.id
        input.nextFocusDownId = downFallback
        submit.nextFocusDownId = downFallback
        topSearchTab?.let { tab ->
            input.nextFocusUpId = tab.id
            submit.nextFocusUpId = tab.id
            tab.nextFocusDownId = input.id
        }

        hotWords.forEachIndexed { index, view ->
            linkHorizontalRow(hotWords, index)
            view.nextFocusUpId = input.id
            view.nextFocusDownId = history.getOrNull(index)?.takeUnless {
                it.tag?.toString() == "video-search-history-clear"
            }?.id
                ?: lastHistoryTerm?.id
                ?: results.id
        }
        history.forEachIndexed { index, view ->
            linkHorizontalRow(history, index)
            view.nextFocusUpId = hotWords.getOrNull(index)?.id
                ?: hotWords.lastOrNull()?.id
                ?: input.id
            view.nextFocusDownId = results.id
        }
        results.nextFocusUpId = lastHistoryTerm?.id ?: hotWords.lastOrNull()?.id ?: input.id
    }

    private fun linkHorizontalRow(views: List<View>, index: Int) {
        val view = views[index]
        view.nextFocusLeftId = views.getOrNull(index - 1)?.id ?: view.id
        view.nextFocusRightId = views.getOrNull(index + 1)?.id ?: view.id
    }

    private fun ensureId(view: View) {
        if (view.id == View.NO_ID) view.id = View.generateViewId()
    }
}
