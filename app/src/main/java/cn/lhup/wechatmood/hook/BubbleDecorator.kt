package cn.lhup.wechatmood.hook

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import cn.lhup.wechatmood.core.MoodLog
import java.util.IdentityHashMap

/** 在真实气泡下方追加一层卡片，不替换宿主行或 ViewHolder。 */
object BubbleDecorator {
    private class Card(
        val key: String,
        val view: AnalysisCardView,
        val parent: ViewGroup,
        val anchor: View,
        val assignedId: Int?,
        val detach: View.OnAttachStateChangeListener,
    ) {
        var signature: String = ""
    }

    private val cards = IdentityHashMap<View, Card>()
    private val unsupported = mutableSetOf<String>()

    fun show(row: View, model: AnalysisCardModel?): Boolean {
        val empty = model == null || (model.timeLine.isBlank() && model.analysis.isNullOrBlank() &&
            model.status.isNullOrBlank() && model.replyHeader.isNullOrBlank() && model.replies.isEmpty())
        if (empty) {
            clear(row)
            return false
        }
        val visible = model!!
        var state = cards[row]
        if (state != null && (state.key != visible.key || state.view.parent !== state.parent)) {
            clear(row)
            state = null
        }
        if (state == null) {
            state = attach(row, visible.key) ?: return false
            cards[row] = state
        }
        val signature = buildString {
            append(visible.timeLine).append('\u0001')
            append(visible.analysis.orEmpty()).append('\u0001')
            append(visible.status.orEmpty()).append('\u0001')
            append(visible.replyHeader.orEmpty()).append('\u0001')
            append(visible.replies.joinToString("\u0002"))
        }
        if (state.signature != signature) {
            state.signature = signature
            state.view.render(visible)
        }
        return true
    }

    private fun attach(row: View, key: String): Card? {
        val root = row as? ViewGroup ?: return null
        val anchor = findBubble(root) ?: return null
        val rowPos = IntArray(2).also { root.getLocationOnScreen(it) }
        val anchorPos = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val left = (anchorPos[0] - rowPos[0]).coerceAtLeast(0)
        val width = minOf(dp(row, 320), root.width - left - dp(row, 16))
        if (width < dp(row, 120)) return null
        val card = AnalysisCardView(row.context, key, { text -> ReplyInput.fill(row.context, text) }, null)
        var branch: View = anchor
        var parent = branch.parent as? ViewGroup
        var target: ViewGroup? = null
        var assignedId: Int? = null
        while (parent != null && isInside(parent, root)) {
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL &&
                parent.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                val parentPos = IntArray(2).also { parent.getLocationOnScreen(it) }
                val lp = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = (anchorPos[0] - parentPos[0] - parent.paddingLeft).coerceAtLeast(0)
                    topMargin = dp(row, 3)
                    bottomMargin = dp(row, 6)
                }
                parent.addView(card, lp)
                target = parent
                break
            }
            if (parent === root) break
            branch = parent
            parent = branch.parent as? ViewGroup
        }
        if (target == null && root is RelativeLayout && branch.parent === root &&
            root.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            val branchParams = branch.layoutParams as? RelativeLayout.LayoutParams ?: return null
            if (branchParams.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0) return null
            if (branch.id == View.NO_ID) {
                assignedId = View.generateViewId()
                branch.id = assignedId
            }
            val lp = RelativeLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.BELOW, branch.id)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                leftMargin = left
                topMargin = dp(row, 3)
                bottomMargin = dp(row, 6)
            }
            root.addView(card, lp)
            target = root
        }
        if (target == null) {
            val signature = "${root.javaClass.name}/${anchor.parent?.javaClass?.name}"
            if (unsupported.add(signature)) MoodLog.w("暂不绘制未知气泡布局：$signature")
            return null
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { MessageSniffer.restoreBoundCard(v) }
            override fun onViewDetachedFromWindow(v: View) {
                if (!MessageSniffer.hasBoundMessage(v)) clear(v)
            }
        }
        row.addOnAttachStateChangeListener(detach)
        return Card(key, card, target, branch, assignedId, detach)
    }

    private fun findBubble(root: ViewGroup): View? {
        val holder = root.tag
        if (holder != null) {
            val method = generateSequence(holder.javaClass as Class<*>) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == "getMainContainerView" && it.parameterCount == 0 }
            val main = runCatching { method?.isAccessible = true; method?.invoke(holder) as? View }.getOrNull()
            if (main != null && main !== root && main.isShown && isInside(main, root)) return main
        }
        fun find(view: View, depth: Int): View? {
            if (depth > 24 || view.visibility != View.VISIBLE) return null
            if (view.javaClass.name.endsWith(".MMNeat7extView")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), depth + 1)?.let { return it }
            return null
        }
        return find(root, 0)
    }

    private fun isInside(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    fun clear(row: View) {
        val state = cards.remove(row) ?: return
        row.removeOnAttachStateChangeListener(state.detach)
        (state.view.parent as? ViewGroup)?.removeView(state.view)
        if (state.assignedId != null && state.anchor.id == state.assignedId) state.anchor.id = View.NO_ID
    }

    fun clearAll() { cards.keys.toList().forEach(::clear) }

    fun prune() {
        cards.keys.filter { !it.isAttachedToWindow }.drop(32).forEach(::clear)
    }

    private fun dp(view: View, n: Int) = (n * view.resources.displayMetrics.density).toInt()
}
