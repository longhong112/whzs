package cn.lhup.wechatmood.core

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cn.lhup.wechatmood.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 运行诊断：只读取本机文件与配置，不再访问已删除的跨进程设置通道。 */
object Diagnostics {
    fun environment(context: Context): String = buildString {
        appendLine("module=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("process=${context.packageName} uid=${Process.myUid()} pid=${Process.myPid()}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        appendLine("storage=${SharedStorage.displayPath()}")
    }

    fun collect(context: Context): String = MoodLog.sanitize(buildString {
        appendLine("微话助手运行诊断 ${Date()}")
        appendLine(environment(context))
        val config = AppConfigStore.load(force = true)
        appendLine("workbuddy=${if (config.loggedIn) "已登录" else "未登录"} region=${config.workBuddyRegion.label} model=${config.model}")
        appendLine("autoAnalyze=${config.autoAnalyze} autoReplies=${config.autoReplies} replyCount=${config.replyCount} context=${config.contextMessages}")
        appendLine("storageReady=${SharedStorage.accessible(context)}")
        appendLine("--- 最近日志 ---")
        appendLine(MoodLog.read().takeLast(6000).ifBlank { "尚无记录" })
    })

    fun show(activity: Activity) {
        val report = collect(activity)
        val view = TextView(activity).apply {
            text = report
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(activity).setTitle("运行日志")
            .setView(ScrollView(activity).apply { addView(view) })
            .setPositiveButton("保存日志文件") { _, _ -> export(activity) }
            .setNeutralButton("复制全部") { _, _ -> copy(activity, report) }
            .setNegativeButton("关闭", null).show()
    }

    fun showFailure(activity: Activity, title: String, detail: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity).setTitle(title)
            .setMessage(MoodLog.sanitize(detail).take(900))
            .setPositiveButton("复制诊断") { _, _ -> copy(activity) }
            .setNegativeButton("关闭", null).show()
    }

    fun showSettingsConnectionHelp(activity: Activity) {
        showFailure(activity, "设置暂不可用",
            "请检查设置页的存储权限是否已授权，并确认可以读写：\n${SharedStorage.displayPath()}\n\n授权后重新打开聊天即可。")
    }

    fun copy(activity: Activity, report: String = collect(activity)) {
        runCatching {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("微话助手运行日志", report))
            Toast.makeText(activity, "运行日志已复制", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(activity, "复制失败，可长按选择文本", Toast.LENGTH_LONG).show()
        }
    }

    fun export(activity: Activity) {
        val report = collect(activity)
        val name = "weihua-log-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())}.txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            }
            val uri = runCatching {
                activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()
            if (uri != null) {
                runCatching {
                    activity.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
                    Toast.makeText(activity, "日志已保存到 Download/$name", Toast.LENGTH_LONG).show()
                    return
                }
            }
        }
        runCatching {
            val file = SharedStorage.logFile().parentFile?.let { java.io.File(it, name) }
            file?.writeText(report)
            Toast.makeText(activity, "日志已写入 ${file?.absolutePath}", Toast.LENGTH_LONG).show()
        }.onFailure {
            copy(activity, report)
        }
    }
}
