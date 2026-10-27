package cn.lhup.wechatmood.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** 单条消息下方的分析卡片：时间、分析正文、可选回复。全部文本可长按自由选择复制。 */
data class AnalysisCardModel(
    val key: String,
    val timeLine: String,
    val analysis: String? = null,
    val status: String? = null,
    val replyHeader: String? = null,
    val replies: List<String> = emptyList(),
    val onReply: (String) -> Unit = {},
    val onRetry: (() -> Unit)? = null,
)

class AnalysisCardView(context: Context, val key: String, private val onReply: (String) -> Unit,
    private val onRetry: (() -> Unit)?) : LinearLayout(context) {

    private val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    private val bg = color("#EDF5FF", "#1A2430")
    private val border = color("#CFE2F8", "#2C3D50")
    private val ink = color("#143A5E", "#D9E7F7")
    private val muted = color("#5C7595", "#93A9C0")
    private val accent = color("#2E7CD6", "#6FB0F2")
    private val soft = color("#DCEAFC", "#22344A")

    private val header = label("", 11f, muted)
    private val body = label("", 13f, ink).apply {
        setTextIsSelectable(true)
        movementMethod = LinkMovementMethod.getInstance()
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private val status = label("", 12f, muted)
    private val replyTitle = label("", 12f, accent, bold = true)
    private val replyBox = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(bg)
            setStroke(dp(1).coerceAtLeast(1), border)
        }
        setPadding(dp(11), dp(7), dp(11), dp(8))
        addView(header, LayoutParams(-1, -2))
        addView(body, LayoutParams(-1, -2))
        addView(status, LayoutParams(-1, -2))
        addView(replyTitle, LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(replyBox, LayoutParams(-1, -2))
    }

    fun render(model: AnalysisCardModel) {
        header.text = model.timeLine
        header.visibility = if (model.timeLine.isBlank()) View.GONE else View.VISIBLE
        body.text = model.analysis.orEmpty()
        body.visibility = if (model.analysis.isNullOrBlank()) View.GONE else View.VISIBLE
        if (model.status.isNullOrBlank()) {
            status.visibility = View.GONE
        } else {
            status.text = model.status
            status.visibility = View.VISIBLE
            status.setTextColor(if (model.onRetry != null) accent else muted)
            status.isClickable = model.onRetry != null
            status.setOnClickListener { model.onRetry?.invoke() }
        }
        replyTitle.text = model.replyHeader.orEmpty()
        replyTitle.visibility = if (model.replyHeader.isNullOrBlank()) View.GONE else View.VISIBLE
        replyBox.removeAllViews()
        model.replies.forEachIndexed { index, text ->
            replyBox.addView(replyRow(index + 1, text, model.onReply), LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }

    private fun replyRow(index: Int, text: String, action: (String) -> Unit) = TextView(context).apply {
        this.text = "$index. $text"
        textSize = 13f
        setTextColor(ink)
        gravity = Gravity.CENTER_VERTICAL
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        setTextIsSelectable(true)
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf((accent and 0x00ffffff) or 0x33000000),
            GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(soft)
                setStroke(dp(1).coerceAtLeast(1), border)
            }, null)
        // 短按填入输入框，长按由系统选择菜单接管复制。
        setOnClickListener { action(text) }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun color(light: String, night: String) = Color.parseColor(if (this.night) night else light)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun widthFor(view: ViewGroup): Int = (view.resources.displayMetrics.widthPixels * 0.8f).toInt()
    }
}
