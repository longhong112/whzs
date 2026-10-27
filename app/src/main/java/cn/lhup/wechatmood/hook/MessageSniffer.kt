package cn.lhup.wechatmood.hook

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ListView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import cn.lhup.wechatmood.analysis.ChatContext
import cn.lhup.wechatmood.analysis.ChatContexts
import cn.lhup.wechatmood.core.AppConfig
import cn.lhup.wechatmood.core.ModulePrefs
import cn.lhup.wechatmood.core.MoodLog
import cn.lhup.wechatmood.reply.ReplyContext
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * 聊天列表监听：只在列表滑到底部时自动分析最新一条消息；
 * 向上浏览历史时不做任何分析或生成；历史分析一律由长按手动触发。
 */
object MessageSniffer {
    private val main = Handler(Looper.getMainLooper())

    private data class Bound(val talker: String, val metadata: MessageMetadata,
        val position: Int, val adapter: WeakReference<Any>?)

    private val bindings = Collections.synchronizedMap(WeakHashMap<View, Bound>())
    private var active = WeakReference<Activity>(null)
    private var installed = false
    @Volatile private var adapterStatus = "正在连接新版聊天列表"
    private var lastReport = ""
    private var ui: HostUi? = null
    private var displayedTalker: String? = null
    private var lastMessageId: Long? = null
    private var atBottom = false

    /** 每类卡片只保留最近 N 条消息；窗口为 null 表示不限。 */
    private class KeepWindow(val analysis: Set<Long>?, val sent: Set<Long>?, val replies: Set<Long>?)
    private var keepWindow: KeepWindow? = null
    private var windowStamp = ""

    private data class PendingCard(val observer: ViewTreeObserver, val listener: ViewTreeObserver.OnPreDrawListener)
    private val pendingCards = mutableMapOf<View, PendingCard>()

    private val tick = object : Runnable {
        override fun run() {
            val activity = active.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            runCatching { scan(activity) }.onFailure {
                clearPendingCards()
                BubbleDecorator.clearAll()
                report("读取当前页面失败：${it.javaClass.simpleName}")
                ui?.showStatus("页面读取失败，点击查看", null)
            }
            main.postDelayed(this, 700)
        }
    }

    // ---------------------------------------------------------------- 安装

    fun install(context: Context) {
        if (installed) return
        installed = true
        val loader = context.classLoader
        val paths = listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty()
        Thread({
            runCatching {
                System.loadLibrary("dexkit")
                val menuPoints = mutableListOf<MessageMenu.HookPoints>()
                val settingsPoints = mutableListOf<HostSettingsEntry.Points>()
                val methods = paths.flatMap { path ->
                    DexKitBridge.create(path).use { bridge ->
                        NativeVoiceBridge.install(bridge, loader)
                        HostSettingsEntry.locate(bridge, loader)?.let(settingsPoints::add)
                        menuPoints += MessageMenu.locate(bridge, loader)
                        runCatching {
                            bridge.findMethod {
                                matcher { usingStrings("MicroMsg.MvvmChattingItem", "[onBindView]") }
                            }.mapNotNull { data ->
                                runCatching { data.getMethodInstance(loader) }.getOrNull()
                            }
                        }.onFailure { MoodLog.e("CHAT_BIND_LOCATE_FAILED 新版聊天绑定定位失败", it) }
                            .getOrDefault(emptyList())
                    }
                }.filter { it.parameterCount >= 3 && it.parameterTypes[2] == Int::class.javaPrimitiveType }
                    .distinct()
                MessageMenu.install(menuPoints)
                HostSettingsEntry.install(settingsPoints)
                check(methods.isNotEmpty()) { "未找到新版聊天绑定点" }
                for (method in methods) {
                    val adapterFields = fields(method.declaringClass).filter { field ->
                        field.type.methods.any { it.name == "getItem" &&
                            it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
                    }
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val holder = param.args.firstOrNull() ?: return
                            runCatching {
                                rowOf(holder)?.let { row ->
                                    cancelPendingCard(row); BubbleDecorator.clear(row); bindings.remove(row)
                                }
                            }
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            NativeVoiceBridge.observeBinding(param.thisObject)
                            val holder = param.args.firstOrNull() ?: return
                            val row = runCatching { rowOf(holder) }.getOrNull() ?: return
                            bindings.remove(row)
                            runCatching {
                                val position = param.args[2] as Int
                                val message = adapterFields.firstNotNullOfOrNull { field ->
                                    val adapter = field.get(param.thisObject) ?: return@firstNotNullOfOrNull null
                                    runCatching {
                                        val getItem = adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)
                                        val current = MessageMetadata.read(getItem.invoke(adapter, position))
                                            ?: return@runCatching null
                                        Bound(current.talker, current, position, WeakReference(adapter))
                                    }.getOrNull()
                                }
                                if (message != null) {
                                    bindings[row] = message
                                    restoreBoundCard(row)
                                }
                            }.onFailure { MoodLog.w("消息类型读取失败：${it.javaClass.simpleName}") }
                        }
                    })
                }
                adapterStatus = "新版聊天监听已连接"
                MoodLog.i("新版消息绑定点已连接：${methods.size} 个")
                main.post { active.get()?.let { refreshVisibleAdapters(it) } }
            }.onFailure {
                adapterStatus = "新版聊天未适配（${it.javaClass.simpleName}），可重新进入聊天后查看"
                MoodLog.e("CHAT_BIND_FAILED 新版聊天连接失败", it)
            }
        }, "weihua-chat-hook").start()
    }

    private fun rowOf(holder: Any): View? =
        fields(holder.javaClass).firstOrNull { it.type == View::class.java }?.get(holder) as? View

    private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()
    private fun fields(type: Class<*>): List<Field> = fieldCache.getOrPut(type) {
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }.toList()
    }

    // ---------------------------------------------------------------- 生命周期

    private fun cancelPendingCard(row: View) {
        pendingCards.remove(row)?.let {
            if (it.observer.isAlive) it.observer.removeOnPreDrawListener(it.listener)
            if (row.viewTreeObserver.isAlive) row.viewTreeObserver.removeOnPreDrawListener(it.listener)
        }
    }

    private fun clearPendingCards() {
        pendingCards.keys.toList().forEach(::cancelPendingCard)
        displayedTalker = null
    }

    fun resume(activity: Activity) {
        ReplyDatabaseHistory.resetAccountScope()
        main.removeCallbacks(tick)
        AnalysisCenter.reset()
        clearPendingCards()
        BubbleDecorator.clearAll()
        ui?.dispose()
        ui = null
        lastMessageId = null
        atBottom = false
        keepWindow = null
        windowStamp = ""
        active = WeakReference(activity)
        main.post(tick)
    }

    fun pause(activity: Activity) {
        if (active.get() !== activity) return
        ReplyDatabaseHistory.resetAccountScope()
        main.removeCallbacks(tick)
        AnalysisCenter.cancelChat(displayedTalker)
        clearPendingCards()
        BubbleDecorator.clearAll()
        ui?.dispose()
        ui = null
        keepWindow = null
        windowStamp = ""
        active.clear()
        NativeVoiceBridge.clearActiveChat()
    }

    fun refresh() {
        main.removeCallbacks(tick)
        if (active.get() != null) main.post(tick)
    }

    fun restoreBoundCard(row: View) {
        runCatching { restoreCard(row) }.onFailure {
            cancelPendingCard(row)
            BubbleDecorator.clear(row)
            MoodLog.w("气泡恢复失败：${it.javaClass.simpleName}")
        }
    }

    fun hasBoundMessage(row: View) = bindings.containsKey(row)

    private fun restoreCard(row: View) {
        val bound = bindings[row] ?: return
        cancelPendingCard(row)
        val talker = displayedTalker ?: return
        if (bound.talker != talker) return
        val model = modelFor(bound, row)
        if (model == null || (model.timeLine.isBlank() && model.analysis.isNullOrBlank() &&
                model.status.isNullOrBlank() && model.replyHeader.isNullOrBlank() && model.replies.isEmpty())) {
            BubbleDecorator.clear(row)
            return
        }
        if (BubbleDecorator.show(row, model)) return
        val observer = row.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                cancelPendingCard(row)
                if (bindings[row] !== bound) return true
                val current = modelFor(bound, row) ?: return true
                if (current.timeLine.isBlank() && current.analysis.isNullOrBlank() &&
                    current.status.isNullOrBlank() && current.replyHeader.isNullOrBlank() &&
                    current.replies.isEmpty()) return true
                return !runCatching { BubbleDecorator.show(row, current) }.getOrDefault(false)
            }
        }
        pendingCards[row] = PendingCard(observer, listener)
        observer.addOnPreDrawListener(listener)
        pendingCards.keys.filter { !it.isAttachedToWindow }.drop(32).toList().forEach(::cancelPendingCard)
    }

    private fun modelFor(bound: Bound, row: View): AnalysisCardModel? {
        val config = ModulePrefs.config()
        val message = bound.metadata
        val window = keepWindow
        val analysisVisible = allows(if (message.isSend == 1) window?.sent else window?.analysis, message.messageId)
        val repliesVisible = allows(window?.replies, message.messageId)
        return AnalysisCenter.model(
            talker = bound.talker,
            message = message,
            allowAnalysis = analysisVisible,
            allowReplies = repliesVisible,
            config = config,
            onRetry = { retry(bound) },
            onReply = { text -> ReplyInput.fill(row.context, text) },
        )
    }

    private fun allows(set: Set<Long>?, messageId: Long): Boolean = set == null || messageId in set

    /**
     * 从列表尾部向前收集最近 N 条可分析消息的 ID。
     * keep 为 0 表示不限，对应集合为 null；列表不可用时返回 null（完全放开）。
     */
    private fun keepWindowFor(list: ChatList, config: AppConfig): KeepWindow {
        val analysisKeep = config.analysisKeepMessages
        val sentKeep = config.sentKeepMessages
        val replyKeep = config.replyKeepMessages
        if (analysisKeep <= 0 && sentKeep <= 0 && replyKeep <= 0) return KeepWindow(null, null, null)
        val analysis = if (analysisKeep > 0) HashSet<Long>() else null
        val sent = if (sentKeep > 0) HashSet<Long>() else null
        val replies = if (replyKeep > 0) HashSet<Long>() else null
        var index = list.count - 1
        val floor = (list.count - 200).coerceAtLeast(0)
        while (index >= floor) {
            val record = list.read(index)
            if (record != null && record.messageId > 0 && record.type in setOf(1, 34) &&
                record.analysisText() != null) {
                val id = record.messageId
                if (record.isSend == 1) {
                    sent?.let { if (it.size < sentKeep) it += id }
                } else {
                    analysis?.let { if (it.size < analysisKeep) it += id }
                }
                replies?.let { if (it.size < replyKeep) it += id }
                val analysisFull = analysis == null || analysis.size >= analysisKeep
                val sentFull = sent == null || sent.size >= sentKeep
                val repliesFull = replies == null || replies.size >= replyKeep
                if (analysisFull && sentFull && repliesFull) break
            }
            index--
        }
        return KeepWindow(analysis, sent, replies)
    }

    // ---------------------------------------------------------------- 扫描

    private fun scan(activity: Activity) {
        ModulePrefs.requestReload()
        val root = activity.window.decorView as? ViewGroup ?: return
        val settings = activity.javaClass.name.let {
            it.endsWith(".SettingsUI") || it.endsWith(".MainSettingsUI")
        }
        val chatScope = chatNodes(root)
        val chat = chatScope.isNotEmpty()
        if (!settings && !chat) {
            clearPendingCards()
            BubbleDecorator.clearAll()
            ui?.hide()
            return
        }
        val panel = ui ?: HostUi(activity).also { ui = it }
        if (settings) {
            clearPendingCards()
            BubbleDecorator.clearAll()
            panel.showSettings()
            report(HostSettingsEntry.status(activity))
            return
        }
        val records = records(chatScope)
        val talker = conversation(activity, chatScope, records)
        if (displayedTalker != talker) {
            ReplyDatabaseHistory.resetAccountScope()
            AnalysisCenter.cancelChat(displayedTalker)
            clearPendingCards()
            BubbleDecorator.clearAll()
            displayedTalker = talker
            lastMessageId = null
            keepWindow = null
            windowStamp = ""
        }
        val config = ModulePrefs.config()
        val enabled = talker != null && ModulePrefs.isChatEnabled(talker)
        val list = talker?.let { chatList(chatScope, records, it) }
        val tail = list?.let { tailOf(it) }
        lastMessageId = tail?.metadata?.messageId
        atBottom = list != null && !list.scroller.canScrollVertically(1)
        if (list != null) {
            val stamp = "${list.count}|${lastMessageId}|${config.revision}"
            if (stamp != windowStamp) {
                windowStamp = stamp
                keepWindow = keepWindowFor(list, config)
            }
        } else {
            windowStamp = ""
            keepWindow = null
        }

        for ((row, message) in records.distinctBy { it.first }) {
            runCatching {
                if (BubbleDecorator.show(row, modelFor(message, row))) {
                    cancelPendingCard(row)
                }
            }.onFailure {
                BubbleDecorator.clear(row)
                MoodLog.w("气泡绘制失败：${it.javaClass.simpleName}")
            }
        }
        BubbleDecorator.prune()

        // 自动分析与可选回复：只在底部最后一条消息触发；向上浏览历史时完全静止。
        if (enabled && atBottom && tail != null && list != null && config.canAnalyze &&
            (config.autoAnalyze || config.autoReplies)) {
            val context = contextOf(tail.metadata, tail.position, list.adapter, config.contextMessages)
            if (context != null) {
                val selected = talker
                val allowed = {
                    selected != null && ModulePrefs.isChatEnabled(selected) && currentReplyTalker() == selected
                }
                AnalysisCenter.auto(context, config, talker, allowed)
            }
        }

        val autoAnalysis = config.autoAnalyze && tail != null &&
            (tail.metadata.isSend == 0 || config.analyzeSent)
        val autoReply = config.autoReplies && tail != null &&
            (tail.metadata.isSend == 0 || config.repliesForSent)
        val status = when {
            talker == null -> "暂未识别当前聊天，收到消息后重试"
            !enabled -> "本聊天自动分析已暂停 · 长按任意消息可手动分析"
            !config.canAnalyze -> "请先在微话助手中配置模型服务"
            records.isEmpty() -> "未识别到消息 · $adapterStatus"
            !atBottom -> "已停止自动分析（未滑到底部）· 长按消息可手动分析"
            autoAnalysis && autoReply -> "已对最新消息执行分析与可选回复"
            autoAnalysis -> "已对最新消息执行分析"
            autoReply -> "已为最新消息生成可选回复"
            else -> "自动分析未开启"
        }
        report(status)
        panel.showStatus(status, talker)
    }

    private fun report(status: String) {
        if (status == lastReport) return
        lastReport = status
        ModulePrefs.report(status)
    }

    // ---------------------------------------------------------------- 行/会话识别

    private fun chatNodes(root: View): List<View> {
        val footer = nodes(root).filter {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" && isVisible(it)
        }.singleOrNull() ?: return emptyList()
        val chatRoot = generateSequence(footer.parent as? View) { it.parent as? View }
            .firstOrNull { it.javaClass.name.endsWith(".ChattingUILayout") } ?: root
        return nodes(chatRoot)
    }

    private fun conversation(activity: Activity, scope: List<View>, records: List<Pair<View, Bound>>): String? {
        val footer = scope.firstOrNull { it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
        val footerTalker = footer?.let {
            runCatching { it.javaClass.getMethod("getTalkerUserName").invoke(it) as? String }.getOrNull()
        }
        val dedicated = if (activity.javaClass.name.endsWith(".ChattingUI"))
            activity.intent?.getStringExtra("Chat_User") else null
        return ConversationIdentity.resolve(records.map { it.second.talker }, footerTalker, dedicated)
    }

    private fun records(scope: List<View>): List<Pair<View, Bound>> {
        val result = mutableListOf<Pair<View, Bound>>()
        for (list in scope.filterIsInstance<ListView>()) {
            for (index in 0 until list.childCount) {
                val row = list.getChildAt(index)
                if (!isVisible(row)) continue
                val position = list.firstVisiblePosition + index
                val item = runCatching { list.getItemAtPosition(position) }.getOrNull()
                MessageMetadata.read(item)?.let { current ->
                    result += row to Bound(current.talker, current, position, WeakReference(list.adapter))
                }
            }
        }
        val views = scope.toSet()
        synchronized(bindings) {
            bindings.entries.filter { it.key in views && isVisible(it.key) }.forEach { result += it.key to it.value }
        }
        return result
    }

    private class ChatList(val adapter: Any, val count: Int,
        val read: (Int) -> MessageMetadata?, val scroller: View)

    private fun chatList(scope: List<View>, records: List<Pair<View, Bound>>, talker: String): ChatList? {
        for (list in scope.filterIsInstance<ListView>()) {
            val matches = (0 until list.childCount).any { index ->
                MessageMetadata.read(runCatching {
                    list.getItemAtPosition(list.firstVisiblePosition + index)
                }.getOrNull())?.talker == talker
            }
            if (matches) return ChatList(list.adapter, list.count, { index ->
                MessageMetadata.read(runCatching { list.getItemAtPosition(index) }.getOrNull())
            }, list)
        }
        val bound = records.map { it.second }.firstOrNull { it.talker == talker && it.adapter?.get() != null } ?: return null
        val adapter = bound.adapter?.get() ?: return null
        val count = runCatching {
            adapter.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name in setOf("getItemCount", "getCount") &&
                    it.returnType == Int::class.javaPrimitiveType
            }?.invoke(adapter) as? Int
        }.getOrNull() ?: return null
        val getItem = runCatching { adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType) }.getOrNull()
            ?: return null
        val row = records.firstOrNull { it.second.adapter?.get() === adapter }?.first ?: return null
        val scroller = generateSequence(row.parent as? View) { it.parent as? View }.firstOrNull { view ->
            generateSequence(view.javaClass as Class<*>) { it.superclass }.any { it.name.endsWith(".RecyclerView") }
        } ?: return null
        return ChatList(adapter, count, { index ->
            MessageMetadata.read(runCatching { getItem.invoke(adapter, index) }.getOrNull())
        }, scroller)
    }

    private class Tail(val metadata: MessageMetadata, val position: Int)

    private fun tailOf(list: ChatList): Tail? {
        for (index in (list.count - 1) downTo (list.count - 40).coerceAtLeast(0)) {
            val record = list.read(index) ?: continue
            if (record.messageId <= 0) continue
            if (record.type !in setOf(1, 34)) continue
            if (record.analysisText() == null) continue
            return Tail(record, index)
        }
        return null
    }

    private fun contextOf(message: MessageMetadata, position: Int, adapter: Any, limit: Int): ChatContext? {
        val getItem = runCatching { adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType) }.getOrNull()
            ?: return null
        return ChatContexts.collect(message, position, limit) { index ->
            MessageMetadata.read(runCatching { getItem.invoke(adapter, index) }.getOrNull())
        }
    }

    // ---------------------------------------------------------------- 对外动作

    fun currentReplyTalker(): String? {
        val activity = active.get() ?: return null
        val scope = chatNodes(activity.window.decorView)
        return if (scope.isEmpty()) null else conversation(activity, scope, records(scope))
    }

    fun replyContext(requireBottom: Boolean = true): ReplyContext {
        val activity = active.get() ?: error("请先打开聊天")
        val scope = chatNodes(activity.window.decorView)
        val rows = records(scope)
        val talker = conversation(activity, scope, rows) ?: error("暂未识别当前聊天，请重新进入后重试")
        val list = chatList(scope, rows, talker) ?: error("当前聊天列表暂不支持回复上下文，请重新进入聊天后重试")
        if (requireBottom && list.scroller.canScrollVertically(1)) error("请先回到聊天底部，再生成这次的回复")
        val messages = mutableListOf<MessageMetadata>()
        var textCount = 0
        for (index in (list.count - 1) downTo (list.count - 400).coerceAtLeast(0)) {
            val record = list.read(index) ?: continue
            if (record.talker != talker) continue
            messages += record
            if (record.type in setOf(1, 34) && ++textCount >= ReplyContext.MAX_MESSAGES) break
        }
        val result = ReplyContext.collect(talker, messages.asReversed())
        check(result.historyAnchor != null || result.messages.isNotEmpty()) { "暂时无法核对当前聊天，请重新进入后重试" }
        return result.copy(trimmed = result.trimmed || messages.size < list.count)
    }

    fun replyBoundary(): Long? {
        val activity = active.get() ?: return null
        val scope = chatNodes(activity.window.decorView)
        val rows = records(scope)
        val talker = conversation(activity, scope, rows) ?: return null
        return tailOf(chatList(scope, rows, talker) ?: return null)?.metadata?.messageId
    }

    fun setChatEnabled(talker: String?, enabled: Boolean): Boolean = runCatching {
        if (talker == null) return@runCatching false
        val activity = active.get() ?: return@runCatching false
        val scope = chatNodes(activity.window.decorView)
        if (scope.isEmpty() || conversation(activity, scope, records(scope)) != talker) return@runCatching false
        val saved = ModulePrefs.setChatEnabled(talker, enabled)
        if (saved && !enabled) {
            AnalysisCenter.cancelChat(talker)
            BubbleDecorator.clearAll()
        }
        saved
    }.onFailure { MoodLog.e("CHAT_SWITCH_FAILED 无法确认当前会话", it) }.getOrDefault(false)

    /** 长按菜单：定位当前气泡对应的实时消息。 */
    private fun boundForView(view: View): Bound? {
        val activity = active.get() ?: return null
        if (!isVisible(view)) return null
        val scope = chatNodes(activity.window.decorView)
        if (view !in scope) return null
        val talker = conversation(activity, scope, records(scope)) ?: return null
        val ancestors = generateSequence(view) { it.parent as? View }.toSet()
        return records(scope).filter { it.first in ancestors }.map { it.second }
            .filter { it.talker == talker }.distinctBy { it.metadata.messageId }.singleOrNull()
    }

    fun replyTargetForView(view: View): MessageMetadata? =
        boundForView(view)?.metadata?.takeIf { it.isReplyTarget() }

    fun messageForView(view: View): MessageMetadata? = boundForView(view)?.metadata

    fun suggestReply(view: View, expected: MessageMetadata): Boolean {
        val target = replyTargetForView(view) ?: return false
        if (target != expected) return false
        return ui?.suggestReply(target.messageId) == true
    }

    /** 长按分析：以该消息为终点向上回溯 N 条，结果固定展示在该消息下方。 */
    fun analyzeMessage(view: View, expected: MessageMetadata, sent: Boolean = false): Boolean {
        val bound = boundForView(view) ?: return false
        if (bound.metadata.messageId != expected.messageId) return false
        val config = ModulePrefs.config()
        val context = contextFor(bound, config.contextMessages) ?: return false
        if (context.target.isSend != sent) return false
        val talker = bound.talker
        AnalysisCenter.manual(context, config, talker, {
            ModulePrefs.isChatEnabled(talker) && currentReplyTalker() == talker
        })
        refresh()
        return true
    }

    private fun contextFor(bound: Bound, limit: Int): ChatContext? {
        val adapter = bound.adapter?.get()
        if (adapter != null) return contextOf(bound.metadata, bound.position, adapter, limit)
        val activity = active.get() ?: return null
        val list = chatNodes(activity.window.decorView).filterIsInstance<ListView>().firstOrNull {
            it.adapter != null && it.count > bound.position &&
                MessageMetadata.read(runCatching { it.getItemAtPosition(bound.position) }.getOrNull())
                    ?.messageId == bound.metadata.messageId
        } ?: return null
        return ChatContexts.collect(bound.metadata, bound.position, limit) { index ->
            MessageMetadata.read(runCatching { list.getItemAtPosition(index) }.getOrNull())
        }
    }

    private fun retry(bound: Bound) {
        val config = ModulePrefs.config()
        val context = contextFor(bound, config.contextMessages) ?: return
        val talker = bound.talker
        val allowed = { ModulePrefs.isChatEnabled(talker) && currentReplyTalker() == talker }
        AnalysisCenter.manual(context, config, talker, allowed)
        if (config.autoReplies && (bound.metadata.isSend == 0 || config.repliesForSent) &&
            lastMessageId == bound.metadata.messageId) {
            AnalysisCenter.manualReplies(context, config, talker, allowed)
        }
    }

    /** + 号菜单内的“聊天分析”：自定义历史条数与补充内容。 */
    fun analyzeLatest(historyCount: Int, hint: String, onResult: (String?) -> Unit) {
        val activity = active.get()
        if (activity == null) {
            onResult("请先打开聊天")
            return
        }
        val scope = chatNodes(activity.window.decorView)
        val rows = records(scope)
        val talker = conversation(activity, scope, rows)
        val list = talker?.let { chatList(scope, rows, it) }
        val tail = list?.let { tailOf(it) }
        if (list == null || tail == null) {
            onResult("当前聊天暂无可分析的消息")
            return
        }
        val config = ModulePrefs.config()
        val selected = talker
        val context = contextOf(tail.metadata, tail.position, list.adapter, historyCount) ?: run {
            onResult("这条消息暂不支持分析")
            return
        }
        AnalysisCenter.manual(context, config, selected, {
            ModulePrefs.isChatEnabled(selected) && currentReplyTalker() == selected
        }, hint)
        refresh()
        onResult(null)
    }

    // ---------------------------------------------------------------- 工具

    private fun nodes(root: View): List<View> {
        val result = mutableListOf<View>()
        fun visit(view: View, depth: Int) {
            if (depth > 40 || result.size >= 4000 || view.visibility != View.VISIBLE) return
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        return result
    }

    private fun isVisible(view: View) = view.isShown && view.isAttachedToWindow &&
        view.windowVisibility == View.VISIBLE && view.getGlobalVisibleRect(Rect())

    private fun refreshVisibleAdapters(activity: Activity) {
        val root = activity.window.decorView
        val views = nodes(root)
        if (views.none { it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }) return
        for (view in views) {
            if (generateSequence(view.javaClass as Class<*>) { it.superclass }
                    .none { it.name.endsWith(".RecyclerView") }) continue
            runCatching {
                val adapter = view.javaClass.getMethod("getAdapter").invoke(view) ?: return@runCatching
                adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
            }
        }
    }
}
