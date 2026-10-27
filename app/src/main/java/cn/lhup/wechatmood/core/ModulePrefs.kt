package cn.lhup.wechatmood.core

import android.content.Context
import cn.lhup.wechatmood.reply.ReplySettings
import java.io.File

/** 微信进程内的配置入口：直接读取共享目录文件，不再依赖设置页后台驻留或跨进程连接。 */
object ModulePrefs {
    data class RuntimeSettings(val generation: String, val rolesRevision: Long)

    @Volatile private var initialized = false
    @Volatile private var lastReport: String = ""

    fun init(context: Context) {
        if (initialized) return
        SharedStorage.init(context)
        AppConfigStore.load(force = true)
        initialized = true
    }

    fun config(): AppConfig = AppConfigStore.load()

    fun reload(force: Boolean = false) {
        AppConfigStore.load(force)
    }

    /** 设置页写文件后由轮询调用；mtime 未变化时无实际 IO。 */
    fun requestReload() {
        AppConfigStore.load(false)
    }

    val exploreMode: Boolean get() = false
    val bridgeAvailable: Boolean get() = true
    val canGenerateReply: Boolean get() = config().canAnalyze

    fun analysisSettings(): RuntimeSettings = RuntimeSettings(
        generation = AppConfigStore.load().revision.toString(),
        rolesRevision = roleRevision(),
    )

    fun replySettings(): ReplySettings = ReplySettings.fromConfig(AppConfigStore.load())

    fun isChatEnabled(talker: String): Boolean = AppStateStore.isChatEnabled(talker)

    fun setChatEnabled(talker: String, enabled: Boolean): Boolean {
        AppStateStore.setChatEnabled(talker, enabled)
        return true
    }

    fun backgroundChanged(talker: String) {
        // 角色/背景保存在共享数据库，界面重新读取即可，无需额外失效通知。
    }

    fun report(status: String) {
        lastReport = status
        runCatching {
            val file = SharedStorage.logFile()
            val line = "${TimeFormatter.now()} $status\n"
            file.appendText(line)
            if (file.length() > 512 * 1024) {
                val tail = file.readLines().takeLast(800).joinToString("\n")
                file.writeText(tail + "\n")
            }
        }
    }

    fun lastReport(): String = lastReport

    private fun roleRevision(): Long = runCatching {
        val db = AndroidCacheDatabase.open(SharedStorage.appContext)
        db.execute("CREATE TABLE IF NOT EXISTS reply_role_revision (id INTEGER PRIMARY KEY, revision INTEGER NOT NULL)")
        db.query("SELECT revision FROM reply_role_revision WHERE id = 1")?.toLongOrNull() ?: 0L
    }.getOrDefault(0L)

    private object TimeFormatter {
        private val format = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
        @Synchronized fun now(): String = format.format(java.util.Date())
    }

    @Suppress("unused")
    private fun probeWritable(): Boolean = File(SharedStorage.root(), ".probe").let { file ->
        runCatching { file.writeText("1"); file.delete(); true }.getOrDefault(false)
    }
}
