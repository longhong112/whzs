package cn.lhup.wechatmood.xposed

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import cn.lhup.wechatmood.BuildConfig
import cn.lhup.wechatmood.core.ModulePrefs
import cn.lhup.wechatmood.core.MoodLog
import cn.lhup.wechatmood.core.Diagnostics
import cn.lhup.wechatmood.hook.MessageSniffer
import cn.lhup.wechatmood.hook.ReplyDatabaseHistory

/** Direct package entry, independent of the optional initZygote callback. */
class HookEntry : IXposedHookLoadPackage {
    private var installed = false
    private var announced = false

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.tencent.mm" || param.processName != "com.tencent.mm") return
        MoodLog.frameworkSink = { XposedBridge.log(it) }
        XposedBridge.log("WeiHua ${BuildConfig.VERSION_NAME}: entered WeChat main process")
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) { initialize(p.args[0] as Context) }
            })
        // Covers module loaders that attach after Application.attach has already run.
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnResume", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val activity = p.args.firstOrNull() as? Activity ?: return
                if (activity.packageName != "com.tencent.mm") return
                initialize(activity.application)
                if (!installed) {
                    Toast.makeText(activity, "微话助手初始化失败，请查看模块日志", Toast.LENGTH_LONG).show()
                    return
                }
                runCatching {
                    MessageSniffer.resume(activity)
                    if (!announced) {
                        announced = true
                        Toast.makeText(activity, "微话助手已加载 · 长按消息可分析", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { MoodLog.e("HOST_RESUME_FAILED", it) }
            }
        })
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnPause", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                (p.args.firstOrNull() as? Activity)?.let { MessageSniffer.pause(it) }
            }
        })
    }

    @Synchronized private fun initialize(context: Context) {
        if (installed) return
        runCatching {
            MoodLog.init(context)
            MoodLog.i("ENVIRONMENT\n${Diagnostics.environment(context)}")
            ModulePrefs.init(context)
            MoodLog.i("微信主进程已加载模块 ${BuildConfig.VERSION_NAME}")
            ModulePrefs.report("模块 ${BuildConfig.VERSION_NAME} 已加载，等待打开聊天")
            ReplyDatabaseHistory.install(context.classLoader)
            MessageSniffer.install(context)
            installed = true
        }.onFailure {
            XposedBridge.log("WeiHua initialization failed: ${it.javaClass.name}")
            MoodLog.e("HOOK_INIT_FAILED", it)
        }
    }
}
