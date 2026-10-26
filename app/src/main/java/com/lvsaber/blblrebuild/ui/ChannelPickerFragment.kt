package com.lvsaber.blblrebuild.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import androidx.media3.common.util.UnstableApi
import com.lvsaber.blblrebuild.data.AppSettings
import com.lvsaber.blblrebuild.data.ChannelCatalog

@UnstableApi
class ChannelPickerFragment : DialogFragment() {
    companion object {
        fun newInstance(current: String) = ChannelPickerFragment().apply { arguments = Bundle().apply { putString("current", current) } }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val settings = AppSettings.load(context)
        val current = arguments?.getString("current")
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 20), Ui.dp(context, 16), Ui.dp(context, 20), Ui.dp(context, 12))
            setBackgroundColor(Ui.background)
        }
        root.addView(Ui.text(context, "更多频道", 26f))
        root.addView(Ui.text(context, "未固定频道 · 选择后直接进入，不会自动占用顶部名额", 13f, Ui.muted))
        val columns = if (resources.configuration.screenWidthDp >= 800) 4 else 3
        val grid = GridLayout(context).apply { columnCount = columns }
        var selected: android.view.View? = null
        val buttons = mutableListOf<android.view.View>()
        ChannelCatalog.normalize(settings.channelOrder, ChannelCatalog.keys)
            .filter { it in settings.hiddenTopChannels }
            .forEachIndexed { index, key ->
            val channel = ChannelCatalog.find(key) ?: return@forEachIndexed
            val mark = if (key == current) "正在浏览" else "进入频道"
            val button = Ui.chip(context, "${channel.symbol}  ${channel.title}\n$mark", key == current) {
                dismiss()
                (activity as? MainActivity)?.selectChannel(key)
            }.apply {
                id = android.view.View.generateViewId()
                gravity = Gravity.CENTER
                setSingleLine(false)
                Ui.focusHighlight(this)
            }
            buttons += button
            if (key == current) selected = button
            grid.addView(button, GridLayout.LayoutParams().apply {
                width = 0; height = Ui.dp(context, 90)
                rowSpec = GridLayout.spec(index / columns)
                columnSpec = GridLayout.spec(index % columns, 1f)
                setMargins(5, 5, 5, 5)
            })
        }
        root.addView(NestedScrollView(context).apply { addView(grid) }, LinearLayout.LayoutParams(-1,
            Ui.dp(context, (resources.configuration.screenHeightDp - 180).coerceIn(120, 300))))
        root.addView(Ui.chip(context, "管理频道", false) {
            dismiss()
            (activity as? MainActivity)?.openSettings("channels")
        })
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener { (selected ?: buttons.firstOrNull())?.requestFocus() }
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? MainActivity)?.closeChannelPicker()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
