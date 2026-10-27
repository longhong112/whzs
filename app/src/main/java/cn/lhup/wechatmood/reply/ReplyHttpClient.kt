package cn.lhup.wechatmood.reply

import cn.lhup.wechatmood.analysis.WorkBuddy
import cn.lhup.wechatmood.core.AppConfigStore
import org.json.JSONObject

/** 回复与话题生成统一走 WorkBuddy 流式接口。 */
class ReplyHttpClient {
    private val client = WorkBuddy.Client(
        currentConfig = { AppConfigStore.load() },
        onTokens = { tokens ->
            AppConfigStore.mutate { config ->
                config.withTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt,
                    tokens.userId, tokens.enterpriseId, tokens.domain, config.revision)
            }
        },
    )

    suspend fun generate(settings: ReplySettings, context: ReplyContext, draft: String, direction: String,
        knowledge: String, previous: String = "", focusMessageId: Long? = null,
        relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED, customRelationship: String = ""): ReplySuggestion {
        check(settings.isConfigured) { "请先在设置页配置模型服务" }
        val payload = ReplyProtocol.payload(settings, context, draft, direction, knowledge, previous, focusMessageId, relationship, customRelationship)
        return ReplyProtocol.parseContent(complete(payload))
    }

    suspend fun findTopics(settings: ReplySettings, context: ReplyContext, draft: String, notes: String,
        knowledge: String, relationship: ReplyRelationship, time: TopicTimeContext,
        previous: List<TopicSuggestion> = emptyList(), customRelationship: String = ""): List<TopicSuggestion> {
        check(settings.isConfigured) { "请先在设置页配置模型服务" }
        return TopicProtocol.parseContent(complete(
            TopicProtocol.payload(settings, context, draft, notes, knowledge, relationship, time, previous, customRelationship)
        ))
    }

    private suspend fun complete(payload: JSONObject): String {
        val messages = payload.getJSONArray("messages")
        val system = messages.getJSONObject(0).optString("content")
        val user = messages.optJSONObject(1)?.optString("content").orEmpty()
        return client.complete(system, user, 1400)
    }
}
