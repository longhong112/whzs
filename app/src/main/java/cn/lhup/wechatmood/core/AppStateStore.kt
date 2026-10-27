package cn.lhup.wechatmood.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 微信进程写入的会话状态（按聊天关闭分析），设置页只读，避免两边互相覆盖。 */
object AppStateStore {
    @Volatile private var stamp: Long = -1
    @Volatile private var disabled: Set<String> = emptySet()

    fun isChatEnabled(talker: String): Boolean = talker !in load()

    fun setChatEnabled(talker: String, enabled: Boolean) {
        synchronized(this) {
            val next = HashSet(load()).apply {
                if (enabled) remove(talker) else add(talker)
            }
            disabled = next
            write(next)
        }
    }

    private fun load(): Set<String> {
        val file = SharedStorage.stateFile()
        val modified = if (file.isFile) file.lastModified() else 0L
        if (modified == stamp) return disabled
        stamp = modified
        if (!file.isFile) return emptySet()
        disabled = runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("disabledChats") ?: JSONArray()
            buildSet { for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(::add) }
        }.getOrDefault(disabled)
        return disabled
    }

    private fun write(value: Set<String>) {
        val file = SharedStorage.stateFile()
        val json = JSONObject().put("disabledChats", JSONArray(value.toList().sorted()))
        runCatching { file.writeText(json.toString(2)) }
        stamp = file.lastModified()
    }
}
