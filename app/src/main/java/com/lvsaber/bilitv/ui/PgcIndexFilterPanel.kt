package com.lvsaber.bilitv.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.lvsaber.bilitv.data.PgcCategory
import java.util.Calendar

data class PgcIndexFilterState(
    val category: PgcCategory,
    val year: Int? = null,
    val finished: Boolean? = null,
    val order: Int = 4,
)

/** TV-friendly overlay: choices stay local until the user presses Apply. */
class PgcIndexFilterPanel(
    context: Context,
    initial: PgcIndexFilterState,
    private val onApply: (PgcIndexFilterState) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {
    private var draftCategory = initial.category
    private var draftYear = initial.year
    private var draftFinished = initial.finished
    private var draftOrder = initial.order
    private val focusGroups = mutableListOf<List<View>>()
    private val categoryChoices = mutableListOf<Pair<PgcCategory, Button>>()
    private val yearChoices = mutableListOf<Pair<Int?, Button>>()
    private val statusChoices = mutableListOf<Pair<Boolean?, Button>>()
    private val orderChoices = mutableListOf<Pair<Int, Button>>()

    init {
        isFocusable = false
        val scrim = View(context).apply {
            setBackgroundColor(Color.argb(172, 0, 0, 0))
            setOnClickListener { onCancel() }
        }
        addView(scrim, LayoutParams(-1, -1))

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(Ui.dp(context, 22), Ui.dp(context, 18), Ui.dp(context, 22), Ui.dp(context, 16))
            background = Ui.roundedBackground(Ui.surface, Ui.dp(context, 18))
            elevation = Ui.dp(context, 16).toFloat()
            isFocusable = false
            isClickable = true
            setOnClickListener { }
        }
        val widthDp = context.resources.configuration.screenWidthDp
            .takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density).toInt()
        val panelWidth = Ui.dp(context, minOf(560, (widthDp - 28).coerceAtLeast(300)))
        addView(panel, LayoutParams(panelWidth, -1, Gravity.END).apply {
            topMargin = Ui.dp(context, 8)
            bottomMargin = Ui.dp(context, 8)
            marginEnd = Ui.dp(context, 10)
        })

        panel.addView(Ui.text(context, "番剧筛选", 21f, Ui.text).apply {
            setPadding(0, 0, 0, Ui.dp(context, 2))
            tag = "pgc-index-filter-title"
        }, LinearLayout.LayoutParams(-1, Ui.dp(context, 34)))
        panel.addView(Ui.text(context, "可调整多个条件，点击“筛选”后统一刷新", 12f, Ui.muted).apply {
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(-1, Ui.dp(context, 24)))

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        addOptionGroup(panel, "动画类型", PgcCategory.entries.map { it to it.label },
            selected = { draftCategory }, onSelect = { draftCategory = it },
            tag = { "pgc-index-filter-category-${it.seasonType}" }, target = categoryChoices)
        addOptionGroup(panel, "年份", listOf(null to "全部年份", currentYear to "$currentYear",
            currentYear - 1 to "${currentYear - 1}", currentYear - 2 to "${currentYear - 2}"),
            selected = { draftYear }, onSelect = { draftYear = it },
            tag = { "pgc-index-filter-year-${it ?: "all"}" }, target = yearChoices)
        addOptionGroup(panel, "状态", listOf(null to "全部状态", false to "连载中", true to "已完结"),
            selected = { draftFinished }, onSelect = { draftFinished = it },
            tag = { "pgc-index-filter-status-${it?.toString() ?: "all"}" }, target = statusChoices)
        addOptionGroup(panel, "排序", listOf(0 to "更新时间", 2 to "播放量", 3 to "追番数", 4 to "评分", 5 to "开播时间"),
            selected = { draftOrder }, onSelect = { draftOrder = it },
            tag = { "pgc-index-filter-order-$it" }, target = orderChoices)

        val actions = LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val reset = Ui.chip(context, "恢复默认", false) {
            draftCategory = PgcCategory.JAPANESE_ANIME
            draftYear = null
            draftFinished = null
            draftOrder = 4
            updateSelections()
        }.apply { tag = "pgc-index-filter-reset" }
        val cancel = Ui.chip(context, "取消", false, onCancel).apply { tag = "pgc-index-filter-cancel" }
        val apply = Ui.button(context, "筛选") { onApply(currentSelection()) }.apply { tag = "pgc-index-filter-apply" }
        listOf(reset, cancel, apply).forEachIndexed { index, button ->
            actions.addView(button, LinearLayout.LayoutParams(-2, Ui.dp(context, 42)).apply {
                marginStart = if (index == 0) 0 else Ui.dp(context, 8)
            })
        }
        panel.addView(actions, LinearLayout.LayoutParams(-1, Ui.dp(context, 50)).apply {
            topMargin = Ui.dp(context, 7)
        })
        focusGroups += listOf(reset, cancel, apply)
        wireFocusGraph()
        post {
            categoryChoices.firstOrNull { it.first == draftCategory }?.second?.requestFocus()
                ?: categoryChoices.firstOrNull()?.second?.requestFocus()
        }
    }

    private fun <T> addOptionGroup(
        panel: LinearLayout,
        label: String,
        options: List<Pair<T, String>>,
        selected: () -> T,
        onSelect: (T) -> Unit,
        tag: (T) -> String,
        target: MutableList<Pair<T, Button>>,
    ) {
        val context = panel.context
        panel.addView(Ui.text(context, label, 12f, Ui.muted).apply {
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(-1, Ui.dp(context, 21)))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        options.forEach { (value, title) ->
            val button = Ui.chip(context, title, selected() == value) {}.apply {
                this.tag = tag(value)
                setOnClickListener {
                    onSelect(value)
                    target.forEach { (candidate, choice) -> Ui.setChipSelected(choice, candidate == selected()) }
                }
            }
            target += value to button
            row.addView(button, LinearLayout.LayoutParams(-2, Ui.dp(context, 38)).apply {
                marginEnd = Ui.dp(context, 6)
            })
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(-2, -1))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, Ui.dp(context, 40)).apply {
            bottomMargin = Ui.dp(context, 7)
        })
        focusGroups += target.map { it.second }
    }

    private fun updateSelections() {
        categoryChoices.forEach { (value, button) -> Ui.setChipSelected(button, value == draftCategory) }
        yearChoices.forEach { (value, button) -> Ui.setChipSelected(button, value == draftYear) }
        statusChoices.forEach { (value, button) -> Ui.setChipSelected(button, value == draftFinished) }
        orderChoices.forEach { (value, button) -> Ui.setChipSelected(button, value == draftOrder) }
    }

    private fun currentSelection() = PgcIndexFilterState(draftCategory, draftYear, draftFinished, draftOrder)

    private fun wireFocusGraph() {
        focusGroups.forEachIndexed { groupIndex, group ->
            group.forEachIndexed { itemIndex, view ->
                view.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val previous = focusGroups.getOrNull(groupIndex - 1)
                    val next = focusGroups.getOrNull(groupIndex + 1)
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> group.getOrNull(itemIndex - 1)?.requestFocus() ?: true
                        KeyEvent.KEYCODE_DPAD_RIGHT -> group.getOrNull(itemIndex + 1)?.requestFocus() ?: true
                        KeyEvent.KEYCODE_DPAD_UP -> previous?.getOrNull(itemIndex.coerceAtMost(previous.lastIndex))?.requestFocus() ?: true
                        KeyEvent.KEYCODE_DPAD_DOWN -> next?.getOrNull(itemIndex.coerceAtMost(next.lastIndex))?.requestFocus() ?: true
                        else -> false
                    }
                }
            }
        }
    }
}
