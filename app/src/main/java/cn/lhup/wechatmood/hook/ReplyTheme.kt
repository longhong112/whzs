package cn.lhup.wechatmood.hook

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.TextView

/** 清新蓝色系；不依赖宿主资源，避免微信换肤后读不到颜色。 */
internal class ReplyTheme(private val context: Context) {
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val surface = color("#F2F7FD", "#131C26")
    val card = color("#FBFDFF", "#1B2836")
    val ink = color("#16324F", "#DCE9F8")
    val muted = color("#5B7391", "#9DB2C9")
    val accent = color("#2C7BE5", "#7CB4F5")
    val onAccent = color("#FFFFFF", "#0B2138")
    val border = color("#CFE0F5", "#2F4358")
    val soft = color("#E4EEFB", "#22344A")
    val error = color("#C0392B", "#FFB1B7")
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun color(light: String, night: String) = Color.parseColor(if (dark) night else light)
    fun shape(fill: Int, radius: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(fill)
        if (stroke != null) setStroke(dp(1).coerceAtLeast(1), stroke)
    }
    fun label(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(2).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun action(value: String, primary: Boolean = false, quiet: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = value; textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(48)
            val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
            setTextColor(ColorStateList(states, intArrayOf(muted, if (primary) onAccent else accent)))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true; isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf((accent and 0x00ffffff) or 0x22000000),
                shape(if (primary) accent else if (quiet) Color.TRANSPARENT else card, 12, if (primary || quiet) null else border).apply {
                    setColor(ColorStateList(states, intArrayOf(if (quiet) Color.TRANSPARENT else soft,
                        if (primary) accent else if (quiet) Color.TRANSPARENT else card)))
                },
                shape(ink, 12))
            setOnClickListener { onClick() }
        }
}
