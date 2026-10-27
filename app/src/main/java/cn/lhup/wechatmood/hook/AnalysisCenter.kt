package cn.lhup.wechatmood.hook

import cn.lhup.wechatmood.analysis.AnalysisRepository
import cn.lhup.wechatmood.analysis.ChatContext
import cn.lhup.wechatmood.analysis.TimeText
import cn.lhup.wechatmood.analysis.WorkBuddy
import cn.lhup.wechatmood.core.AppConfig
import cn.lhup.wechatmood.core.AppConfigStore
import cn.lhup.wechatmood.core.License
import cn.lhup.wechatmood.core.MoodLog
import cn.lhup.wechatmood.hook.MessageMetadata
import cn.lhup.wechatmood.voice.VoiceText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 所有 LLM 分析的唯一入口：自动分析、长按分析、已发送复盘、可选回复。
 * 结果落在共享数据库，退出聊天再进入只读缓存，不重复请求接口。
 */
object AnalysisCenter {
    private class Entry {
        var analysis: String? = null
        var analysisError: String? = null
        var loading = false
        var manual = false
        var replies: List<String> = emptyList()
        var repliesError: String? = null
        var repliesLoading = false
        var repliesAsked = false
    }

    private val entries = HashMap<String, Entry>()
    private val probedAnalysis = HashSet<String>()
    private val probedReplies = HashSet<String>()
    private val jobs = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun keyOf(talker: String, messageId: Long, createdAt: Long): String =
        "$talker|$messageId|$createdAt"

    private fun entry(key: String) = entries.getOrPut(key) { Entry() }

    /** 切换会话/离开聊天时释放进行中的请求。 */
    fun reset() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        entries.clear()
        probedAnalysis.clear()
        probedReplies.clear()
    }

    fun cancelChat(talker: String?) {
        if (talker == null) return
        jobs.filterKeys { it.contains("|$talker|") }.forEach { (_, job) -> job.cancel() }
        entries.keys.filter { it.startsWith("$talker|") }.forEach { entries.remove(it) }
        probedAnalysis.removeAll { it.startsWith("$talker|") }
        probedReplies.removeAll { it.startsWith("$talker|") }
    }

    private fun client(): WorkBuddy.Client = WorkBuddy.Client({ AppConfigStore.load() }) { tokens ->
        AppConfigStore.mutate {
            it.withTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt,
                tokens.userId, tokens.enterpriseId, tokens.domain, it.revision)
        }
    }

    fun model(
        talker: String,
        message: MessageMetadata,
        allowAnalysis: Boolean,
        allowReplies: Boolean,
        config: AppConfig,
        onRetry: (() -> Unit)?,
        onReply: (String) -> Unit,
    ): AnalysisCardModel {
        val key = keyOf(talker, message.messageId, message.createdAt)
        val entry = entry(key)
        // 高级功能需激活；未激活时只保留手动入口的提示，不展示已有结果和自动卡片。
        val licensed = License.isActive(config)
        val manualHint = entry.manual && !licensed
        // 手动长按分析始终展示结果，不受自动开关和保留条数限制。
        val canShow = licensed && (entry.manual || ((message.isSend == 0 || config.analyzeSent) && allowAnalysis))
        val repliesAllowed = licensed && allowReplies && (message.isSend == 0 || config.repliesForSent)
        probe(key, talker, message, config, canShow,
            repliesAllowed && config.autoReplies, entry)
        val status = when {
            manualHint -> License.REQUIRED_HINT
            !canShow -> null
            entry.loading -> "正在分析…"
            entry.analysisError != null -> "分析失败：${entry.analysisError}（点击重试）"
            else -> null
        }
        val replyHeader = when {
            !repliesAllowed -> null
            entry.repliesLoading -> "正在生成可选回复…"
            entry.repliesError != null -> "可选回复生成失败（点击重试）"
            entry.replies.isNotEmpty() -> "可选回复（点击填入微信输入框）"
            else -> null
        }
        return AnalysisCardModel(
            key = key,
            timeLine = if (config.showMessageTime) "发送时间 ${TimeText.full(message.createdAt)}" else "",
            analysis = entry.analysis.takeIf { canShow },
            status = status,
            replyHeader = replyHeader,
            replies = entry.replies.takeIf { repliesAllowed } ?: emptyList(),
            onReply = onReply,
            onRetry = if (status != null || replyHeader?.contains("失败") == true) onRetry else null,
        )
    }

    /** 首次显示时从共享数据库补齐上一次的分析结果，避免重复请求。 */
    private fun probe(key: String, talker: String, message: MessageMetadata, config: AppConfig,
        showAnalysis: Boolean, showReplies: Boolean, entry: Entry) {
        if (entry.loading || entry.repliesLoading) return
        val text = message.analysisText()?.trim().orEmpty()
        if (text.isEmpty() || text.startsWith("【语音")) return
        val id = message.messageId
        val at = message.createdAt
        if (showAnalysis && entry.analysis == null && entry.analysisError == null && probedAnalysis.add(key)) {
            val cached = if (message.isSend == 1) {
                if (config.analyzeSent) AnalysisRepository.cached(
                    AnalysisRepository.sentKey(talker, id, at, text, config)) else null
            } else AnalysisRepository.cached(
                AnalysisRepository.analysisKey(talker, id, at, text, config))
            if (cached != null) entry.analysis = cached
        }
        if (showReplies && entry.replies.isEmpty() && entry.repliesError == null && probedReplies.add(key)) {
            AnalysisRepository.cachedReplies(AnalysisRepository.repliesKey(talker, id, at, text, config, config.replyCount))
                ?.let { entry.replies = it }
        }
    }

    // ------------------------------------------------------------ 自动触发（仅底部最后一条）

    fun auto(context: ChatContext, config: AppConfig, talker: String, allowed: () -> Boolean) {
        if (!License.isActive(config)) return
        val key = keyOf(talker, context.target.messageId, context.target.createdAt)
        val entry = entry(key)
        val analyzeWanted = !context.target.isSend || config.analyzeSent
        if (config.autoAnalyze && analyzeWanted && !entry.loading && entry.analysis == null && entry.analysisError == null) {
            val jobKey = "analysis:$key"
            if (jobs[jobKey]?.isActive != true) {
                jobs[jobKey] = scope.launch {
                    runAnalysis(context, config, talker, entry, "", allowed)
                }
            }
        }
        val repliesWanted = !context.target.isSend || config.repliesForSent
        if (config.autoReplies && repliesWanted && !entry.repliesLoading && entry.replies.isEmpty() &&
            entry.repliesError == null) {
            val jobKey = "replies:$key"
            if (jobs[jobKey]?.isActive != true) {
                jobs[jobKey] = scope.launch {
                    runReplies(context, config, talker, entry, allowed)
                }
            }
        }
    }

    // ------------------------------------------------------------ 手动触发（长按 / +号菜单）

    fun manual(context: ChatContext, config: AppConfig, talker: String, allowed: () -> Boolean, hint: String = "") {
        val key = keyOf(talker, context.target.messageId, context.target.createdAt)
        val entry = entry(key)
        entry.manual = true
        if (!License.isActive(config)) {
            entry.analysisError = License.REQUIRED_HINT
            entry.loading = false
            return
        }
        if (entry.loading) return
        jobs["analysis:$key"] = scope.launch { runAnalysis(context, config, talker, entry, hint, allowed) }
    }

    fun manualReplies(context: ChatContext, config: AppConfig, talker: String, allowed: () -> Boolean) {
        val key = keyOf(talker, context.target.messageId, context.target.createdAt)
        val entry = entry(key)
        if (!License.isActive(config)) {
            entry.repliesError = License.REQUIRED_HINT
            entry.repliesLoading = false
            return
        }
        if (entry.repliesLoading) return
        jobs["replies:$key"] = scope.launch { runReplies(context, config, talker, entry, allowed) }
    }

    // ------------------------------------------------------------ 执行

    private suspend fun runAnalysis(context: ChatContext, config: AppConfig, talker: String,
        entry: Entry, hint: String, allowed: () -> Boolean) {
        entry.loading = true
        entry.analysisError = null
        MessageSniffer.refresh()
        try {
            val prepared = transcribe(context, allowed)
            if (!allowed()) return
            val result = if (prepared.target.isSend) {
                AnalysisRepository.analyzeSent(prepared, config) { system, user ->
                    client().complete(system, user, 1800)
                }
            } else {
                AnalysisRepository.analyze(prepared, config, hint) { system, user ->
                    client().complete(system, user, 1800)
                }
            }
            entry.analysis = result.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            entry.analysisError = friendly(e)
            MoodLog.e("ANALYSIS_FAILED 分析失败", e)
        } finally {
            entry.loading = false
            MessageSniffer.refresh()
        }
    }

    private suspend fun runReplies(context: ChatContext, config: AppConfig, talker: String,
        entry: Entry, allowed: () -> Boolean) {
        entry.repliesLoading = true
        entry.repliesAsked = true
        entry.repliesError = null
        MessageSniffer.refresh()
        try {
            val prepared = transcribe(context, allowed)
            if (!allowed()) return
            val items = AnalysisRepository.replies(prepared, config, config.replyCount) { system, user ->
                client().complete(system, user, 900)
            }
            entry.replies = items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            entry.repliesError = friendly(e)
            MoodLog.e("REPLIES_FAILED 可选回复生成失败", e)
        } finally {
            entry.repliesLoading = false
            MessageSniffer.refresh()
        }
    }

    /** 语音先转文字，再参与上下文分析（需求 18）。 */
    private suspend fun transcribe(context: ChatContext, allowed: () -> Boolean): ChatContext {
        if (context.turns.none { it.voice != null }) return context
        val turns = context.turns.map { turn ->
            val voice = turn.voice ?: return@map turn
            val text = try {
                NativeVoiceBridge.transcribe(voice, allowed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MoodLog.w("VOICE_TRANSCRIBE_SKIPPED ${e.javaClass.simpleName}")
                null
            }
            turn.copy(text = text ?: VoiceText.FAILED)
        }
        val target = turns.lastOrNull() ?: context.target
        check(!(target.voice != null && target.text == VoiceText.FAILED)) { "语音未能转写，请播放一次或稍后重试" }
        return context.copy(turns = turns, target = target)
    }

    private fun friendly(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: "网络异常，请稍后重试"
}
