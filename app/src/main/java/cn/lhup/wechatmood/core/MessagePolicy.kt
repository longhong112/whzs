package cn.lhup.wechatmood.core

/** Shared text limits so prompts and cards never receive blank or oversized chat text. */
object MessagePolicy {
    const val MAX_CHARS = 1000
    const val MAX_CONTEXT_CHARACTERS = 14000

    fun textOrNull(text: String?): String? {
        val value = text?.trim().orEmpty()
        return value.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }
    }
}
