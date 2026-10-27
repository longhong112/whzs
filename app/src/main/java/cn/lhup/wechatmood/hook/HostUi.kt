package cn.lhup.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.View
import android.widget.CompoundButton
import android.widget.Switch
import cn.lhup.wechatmood.core.Diagnostics
import cn.lhup.wechatmood.core.ModulePrefs

/** 聊天页宿主入口：分析开关、长按菜单、聊天分析与回复抽屉。 */
class HostUi(private val activity: Activity) {
    private val replyUi = ReplyHostUi(activity) { createAnalysisControl() }
    private var control: Switch? = null
    private var syncing = false
    private var status = ""
    private var talker: String? = null
    private var dialog: AlertDialog? = null

    fun showStatus(value: String, currentTalker: String?) {
        if (talker != currentTalker) dialog?.dismiss()
        talker = currentTalker
        status = value
        replyUi.update(currentTalker)
        syncControl()
    }

    private fun syncControl() {
        syncing = true
        control?.apply {
            visibility = View.VISIBLE
            isChecked = talker?.let(ModulePrefs::isChatEnabled) ?: false
            isEnabled = talker != null
            contentDescription = "当前聊天分析开关；$status；长按查看更多操作"
        }
        syncing = false
    }

    private fun createAnalysisControl(): View {
        removeControl()
        val theme = ReplyTheme(activity)
        val toggle = Switch(activity).apply {
            text = "分析"
            textSize = 14f
            setTextColor(theme.ink)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(theme.accent, theme.muted))
            trackTintList = ColorStateList(states, intArrayOf(theme.soft, theme.border))
            switchPadding = dp(3)
            minHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
                override fun onCheckedChanged(button: CompoundButton, checked: Boolean) {
                    if (syncing) return
                    if (!MessageSniffer.setChatEnabled(talker, checked)) {
                        syncing = true
                        isChecked = talker?.let(ModulePrefs::isChatEnabled) ?: false
                        syncing = false
                        Diagnostics.showFailure(activity, "开关未保存",
                            "当前聊天已变化或本地保存失败，请重新进入聊天后重试。")
                    } else if (!checked) {
                        BubbleDecorator.clearAll()
                    }
                    MessageSniffer.refresh()
                }
            })
            setOnLongClickListener { showActions(); true }
        }
        control = toggle
        syncControl()
        toggle.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                control = toggle
                syncControl()
            }

            override fun onViewDetachedFromWindow(view: View) {
                if (control === view) control = null
            }
        })
        return toggle
    }

    private fun showActions() {
        if (dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(activity).setTitle("微话助手 · $status")
            .setItems(arrayOf("聊天分析（自定义历史条数）", "帮我回 / 找话题", "微话助手设置", "导出运行日志")) { _, which ->
                when (which) {
                    0 -> ChatAnalysisPanel.show(activity)
                    1 -> replyUi.open()
                    2 -> openSettings()
                    3 -> Diagnostics.show(activity)
                }
            }
            .setNegativeButton("关闭", null).create().also { it.show() }
    }

    fun showSettings() {
        replyUi.hide()
        talker = null
        dialog?.dismiss()
        removeControl()
    }

    private fun openSettings() = HostSettingsEntry.open(activity)

    fun suggestReply(focusMessageId: Long? = null): Boolean = replyUi.open(focusMessageId)

    fun hide() {
        replyUi.hide()
        removeControl()
        dialog?.dismiss()
        talker = null
    }

    fun dispose() {
        hide()
        replyUi.dispose()
    }

    private fun removeControl() {
        control?.setOnCheckedChangeListener(null)
        control?.setOnLongClickListener(null)
        control = null
    }

    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
}
