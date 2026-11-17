package com.lvsaber.bilitv.ui

/** One-shot rule: entering ranking/index starts on card one; later returns restore saved focus. */
internal class BangumiInitialGridFocusGate {
    private var pendingPage: String? = null

    fun pageSelected(page: String, enteringPage: Boolean) {
        pendingPage = page.takeIf { enteringPage && it in setOf("ranking", "index") }
    }

    /** null means preserve normal focus restoration; true/false consume the one-shot entry. */
    fun consume(page: String, hasItems: Boolean): Boolean? {
        if (pendingPage != page) return null
        pendingPage = null
        return hasItems
    }
}
