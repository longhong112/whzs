package cn.lhup.wechatmood.core

import android.content.Context
import android.util.Log
import cn.lhup.wechatmood.analysis.TimeText
import java.io.File

/** 进程内日志：写入共享目录，方便设置页直接查看，无需跨进程连接。 */
object MoodLog {
    private const val TAG = "WeChatMood"
    @Volatile private var file: File? = null
    var frameworkSink: ((String) -> Unit)? = null

    fun init(context: Context) {
        SharedStorage.init(context)
        file = SharedStorage.logFile()
    }

    fun i(message: String) = write("I", message)
    fun w(message: String) = write("W", message)

    /** 调试探索报告：整段写入日志，供适配时查看。 */
    fun dump(title: String, body: String) {
        if (body.isBlank()) write("D", title) else write("D", "$title\n$body")
    }

    fun e(tag: String, error: Throwable) =
        write("E", "$tag: ${error.javaClass.simpleName}: ${error.message}\n${error.stackTraceToString().takeLast(4000)}")

    @Synchronized
    private fun write(level: String, message: String) {
        val line = "${TimeText.full(System.currentTimeMillis())} $level $message"
        runCatching { Log.println(if (level == "E") Log.ERROR else Log.INFO, TAG, message) }
        runCatching { frameworkSink?.invoke(line) }
        runCatching {
            val target = file ?: return
            target.appendText(line + "\n")
            if (target.length() > 512 * 1024) {
                target.writeText(target.readLines().takeLast(800).joinToString("\n") + "\n")
            }
        }
    }

    @Synchronized
    fun read(): String = runCatching { file?.takeIf(File::isFile)?.readText().orEmpty() }.getOrDefault("")

    fun sanitize(value: String): String = value.replace(Regex("(?i)(access_?token|refresh_?token|api[_-]?key)\\s*[:=]\\s*\\S+"), "$1=***")
}
