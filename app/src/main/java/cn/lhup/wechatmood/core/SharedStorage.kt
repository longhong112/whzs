package cn.lhup.wechatmood.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * 模块与设置页共用的文件存储。
 *
 * 安卓 9/10（API 28-29）：直接使用 /Android/data/com.tencent.mm/files/lh，
 * 两个进程都有存储权限，可直接读写。
 *
 * 安卓 11+（API 30+）：系统禁止 A 应用访问 B 应用的 Android/data 目录，
 * 因此改用等价的 /Android/media/com.tencent.mm/lh；该目录不在此限制内，
 * 微信进程可读写自己的目录，设置页取得「所有文件访问」后也能读写。
 * 模块与设置页按同一规则选择路径，避免出现两边读到不同文件的问题。
 */
object SharedStorage {
    const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val FOLDER = "lh"

    @Volatile private var application: Context? = null

    /** 模块（微信进程）与设置页共用同一个进程级 Context。 */
    fun init(context: Context) {
        application = context.applicationContext
    }

    val appContext: Context
        get() = requireNotNull(application) { "SharedStorage 尚未初始化" }

    fun root(): File {
        val external = Environment.getExternalStorageDirectory()
        val preferred = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            File(external, "Android/data/$WECHAT_PACKAGE/files/$FOLDER")
        } else {
            File(external, "Android/media/$WECHAT_PACKAGE/$FOLDER")
        }
        if (mkdir(preferred)) return preferred
        val fallback = File(external, "lh")
        if (mkdir(fallback)) return fallback
        return preferred
    }

    private fun mkdir(dir: File): Boolean = runCatching {
        (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
    }.getOrDefault(false)

    fun configFile(): File = File(root(), "config.json")
    fun stateFile(): File = File(root(), "state.json")
    fun databaseFile(): File = File(root(), "lh.db")
    fun logFile(): File = File(root(), "module.log")

    fun displayPath(): String = root().absolutePath

    fun hasRuntimePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) return true
        val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        return read && write
    }

    fun hasAllFilesAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        Environment.isExternalStorageManager() else true

    /** 当前设备是否已经可以读写共享目录。 */
    fun accessible(context: Context): Boolean {
        val ready = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) hasAllFilesAccess()
        else hasRuntimePermission(context)
        if (!ready) return false
        return runCatching {
            val file = File(root(), ".probe")
            file.writeText("1")
            val ok = file.readText() == "1"
            file.delete()
            ok
        }.getOrDefault(false)
    }
}
