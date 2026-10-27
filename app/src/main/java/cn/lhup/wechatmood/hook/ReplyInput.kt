package cn.lhup.wechatmood.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/** 把可选回复写入微信原生输入框；不改动宿主菜单或发送行为。 */
object ReplyInput {
    fun fill(context: Context, text: String): Boolean = runCatching {
        val activity = context as? android.app.Activity ?: return@runCatching false
        val footer = findFooter(activity.window.decorView) ?: return@runCatching false
        val editor = findEditor(footer) ?: return@runCatching false
        editor.setText(text)
        editor.setSelection(editor.text.length)
        editor.requestFocus()
        true
    }.getOrDefault(false)

    private fun findFooter(root: View): View? {
        if (root.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" && root.isShown) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findFooter(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findEditor(root: View): EditText? {
        if (root is EditText && root.isShown && root.isEnabled) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findEditor(root.getChildAt(i))?.let { return it }
        }
        return null
    }
}
