package cn.lhup.wechatmood.analysis

import cn.lhup.wechatmood.core.AiCacheStore
import cn.lhup.wechatmood.core.AppConfig
import org.json.JSONArray

data class AiText(val text: String, val cached: Boolean)

/** 所有智能能力统一走 LLM；结果按目标消息缓存，重新进入聊天不会重复请求。 */
object AnalysisRepository {
    private const val VERSION = "llm-v1"

    private fun key(kind: String, talker: String, messageId: Long, createdAt: Long, text: String,
        prompt: String, model: String, extra: String = ""): String {
        val parts = mutableListOf(VERSION, kind, model, talker, messageId.toString(), createdAt.toString(), text)
        if (extra.isNotEmpty()) parts += extra
        parts += prompt
        return AiCacheStore.key(*parts.toTypedArray())
    }

    fun analysisKey(talker: String, messageId: Long, createdAt: Long, text: String,
        config: AppConfig, hint: String = ""): String =
        key("analysis", talker, messageId, createdAt, text, config.prompts.analysis, config.model, hint)

    fun sentKey(talker: String, messageId: Long, createdAt: Long, text: String, config: AppConfig): String =
        key("sent", talker, messageId, createdAt, text, config.prompts.sent, config.model)

    fun repliesKey(talker: String, messageId: Long, createdAt: Long, text: String,
        config: AppConfig, count: Int): String =
        key("replies", talker, messageId, createdAt, text, config.prompts.replies, config.model, count.toString())

    fun cached(key: String): String? = AiCacheStore.load(key)?.takeIf(String::isNotBlank)

    fun cachedReplies(key: String): List<String>? = AiCacheStore.load(key)?.let { payload ->
        runCatching {
            val array = JSONArray(payload)
            buildList { for (i in 0 until array.length()) add(array.getString(i)) }
        }.getOrNull()?.takeIf(List<String>::isNotEmpty)
    }

    suspend fun analyze(
        context: ChatContext,
        config: AppConfig,
        hint: String = "",
        complete: suspend (system: String, user: String) -> String,
    ): AiText {
        val key = analysisKey(context.talker, context.target.messageId, context.target.createdAt,
            context.target.text, config, hint)
        cached(key)?.let { return AiText(it, true) }
        val user = buildString {
            append(PromptBuilder.chat(context))
            if (hint.isNotBlank()) append("\n\n【本次补充要求】").append(hint.trim())
        }
        val text = complete(config.prompts.analysis, user).trim()
        require(text.isNotBlank()) { "模型返回了空的分析结果" }
        AiCacheStore.save(key, text)
        return AiText(text, false)
    }

    suspend fun analyzeSent(
        context: ChatContext,
        config: AppConfig,
        complete: suspend (system: String, user: String) -> String,
    ): AiText {
        val key = sentKey(context.talker, context.target.messageId, context.target.createdAt,
            context.target.text, config)
        cached(key)?.let { return AiText(it, true) }
        val text = complete(config.prompts.sent, PromptBuilder.chat(context)).trim()
        require(text.isNotBlank()) { "模型返回了空的复盘结果" }
        AiCacheStore.save(key, text)
        return AiText(text, false)
    }

    suspend fun replies(
        context: ChatContext,
        config: AppConfig,
        count: Int,
        complete: suspend (system: String, user: String) -> String,
    ): List<String> {
        val key = repliesKey(context.talker, context.target.messageId, context.target.createdAt,
            context.target.text, config, count)
        cachedReplies(key)?.let { return it }
        val system = config.prompts.replies.replace("{count}", count.coerceAtLeast(1).toString())
        val text = complete(system, PromptBuilder.replies(context))
        val items = ReplyParser.parse(text, count)
        require(items.isNotEmpty()) { "模型没有给出可用的回复" }
        AiCacheStore.save(key, JSONArray(items).toString())
        return items
    }

    suspend fun assistant(
        context: ChatContext,
        instruction: String,
        count: Int,
        config: AppConfig,
        complete: suspend (system: String, user: String) -> String,
    ): List<String> {
        val text = complete(config.prompts.assistant, PromptBuilder.assistant(context, instruction))
        return ReplyParser.parse(text, count.coerceAtLeast(1))
    }

    suspend fun topics(
        context: ChatContext,
        config: AppConfig,
        complete: suspend (system: String, user: String) -> String,
    ): List<String> {
        val text = complete(config.prompts.topic, PromptBuilder.assistant(context, ""))
        return ReplyParser.parse(text, 3)
    }
}
