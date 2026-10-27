package cn.lhup.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import cn.lhup.wechatmood.core.AppConfigStore

/** + 号菜单里的“聊天分析”：自定义历史条数与补充内容，结果展示在最后一条消息下方。 */
object ChatAnalysisPanel {
    fun show(activity: Activity) {
        val config = AppConfigStore.load()
        val theme = ReplyTheme(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        content.addView(TextView(activity).apply {
            text = "向后回溯的历史消息条数（0 表示不限，最多 999）"
            textSize = 13f
            setTextColor(theme.muted)
        })
        val count = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(config.contextMessages.toString())
            setTextColor(theme.ink)
            setSingleLine()
        }
        content.addView(count)
        content.addView(TextView(activity).apply {
            text = "补充内容（可选）"
            textSize = 13f
            setTextColor(theme.muted)
            setPadding(0, dp(10), 0, 0)
        })
        val hint = EditText(activity).apply {
            hint = "例如：我想约她周末看电影，但不想太直接"
            setTextColor(theme.ink)
            minLines = 2
            maxLines = 5
        }
        content.addView(hint)
        AlertDialog.Builder(activity)
            .setTitle("聊天分析")
            .setView(content)
            .setPositiveButton("开始分析", null)
            .setNegativeButton("取消", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val limit = count.text.toString().trim().toIntOrNull()
                        if (limit == null || limit !in 0..999) {
                            Toast.makeText(activity, "请输入 0～999 之间的条数（0 表示不限）",
                                Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        MessageSniffer.analyzeLatest(limit, hint.text.toString()) { error ->
                            Toast.makeText(activity, error ?: "已开始分析，结果显示在最后一条消息下方",
                                Toast.LENGTH_SHORT).show()
                            if (error == null) dismiss()
                        }
                    }
                }
                show()
            }
    }
}
