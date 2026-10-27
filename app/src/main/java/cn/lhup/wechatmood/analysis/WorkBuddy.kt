package cn.lhup.wechatmood.analysis

import cn.lhup.wechatmood.core.AppConfig
import cn.lhup.wechatmood.core.WorkBuddyRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WorkBuddyTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val enterpriseId: String,
    val domain: String,
)

sealed class LoginStart {
    data class Ready(val url: String, val state: String) : LoginStart()
    data class Failed(val message: String) : LoginStart()
}

sealed class LoginPoll {
    object Pending : LoginPoll()
    data class Success(val tokens: WorkBuddyTokens) : LoginPoll()
    data class Failed(val message: String) : LoginPoll()
}

/** 上游模型目录中的一项。 */
data class RemoteModel(val id: String, val name: String)

/**
 * WorkBuddy（腾讯 CodeBuddy）接口的最小实现：
 * 国内 copilot.tencent.com，国际 workbuddy.ai；上游拒绝非流式请求，因此始终 stream=true。
 */
object WorkBuddy {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun baseOf(region: WorkBuddyRegion) = region.baseUrl.trimEnd('/')

    private fun originOf(region: WorkBuddyRegion) =
        if (region == WorkBuddyRegion.GLOBAL) "https://www.workbuddy.ai" else "https://www.codebuddy.cn"

    private fun commonHeaders(region: WorkBuddyRegion = WorkBuddyRegion.CN): Map<String, String> {
        val origin = originOf(region)
        return mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to origin,
            "Referer" to "$origin/",
            "User-Agent" to "CLI/2.63.2 CodeBuddy/2.63.2",
            "X-Product" to "SaaS",
            "X-Request-Id" to UUID.randomUUID().toString(),
        )
    }

    private fun chatHeaders(config: AppConfig, body: String? = null): Map<String, String> = buildMap {
        putAll(commonHeaders(config.workBuddyRegion))
        put("Accept", "text/event-stream")
        put("Authorization", "Bearer ${config.accessToken}")
        if (config.userId.isNotBlank()) put("X-User-Id", config.userId)
        if (config.enterpriseId.isNotBlank()) {
            put("X-Enterprise-Id", config.enterpriseId)
            put("X-Tenant-Id", config.enterpriseId)
        }
        if (config.domain.isNotBlank()) put("X-Domain", config.domain)
        if (config.workBuddyRegion == WorkBuddyRegion.GLOBAL) put("X-Agent-Type", "main")
        if (body != null) putAll(conversationHeaders(body))
    }

    /** 上游对话端点必需的会话头族；X-Conversation-ID 在会话内保持稳定。 */
    private fun conversationHeaders(body: String): Map<String, String> {
        val conversationId = stableConversationId(body).ifEmpty { FALLBACK_CONVERSATION_ID }
        val requestId = UUID.randomUUID().toString()
        return mapOf(
            "X-Conversation-ID" to conversationId,
            "X-Conversation-Request-ID" to UUID.randomUUID().toString(),
            "X-Conversation-Message-ID" to UUID.randomUUID().toString(),
            "X-Request-ID" to requestId,
            "X-Trace-ID" to requestId,
            "X-Agent-Intent" to "craft",
            "X-Agent-Purpose" to "conversation",
            "X-IDE-Type" to "CLI",
            "X-IDE-Name" to "CLI",
            "x-codebuddy-request" to "1",
        )
    }

    private fun stableConversationId(body: String): String {
        val messages = runCatching { JSONObject(body).optJSONArray("messages") }.getOrNull() ?: return ""
        val seed = StringBuilder()
        var firstUser: String? = null
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "system", "developer" -> seed.append("system:").append(message.optString("content")).append('\n')
                "user" -> if (firstUser == null) firstUser = message.optString("content")
            }
        }
        if (firstUser != null) seed.append("user:").append(firstUser).append('\n')
        if (seed.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toString().toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private const val FALLBACK_CONVERSATION_ID = "00000000-0000-0000-0000-000000000000"

    // ---------------------------------------------------------------- 登录

    suspend fun startLogin(region: WorkBuddyRegion): LoginStart = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseOf(region)}/v2/plugin/auth/state?platform=CLI")
            .post(JSONObject().put("platform", "CLI").toString().toRequestBody(JSON))
            .apply { commonHeaders(region).forEach { (k, v) -> header(k, v) } }
            .build()
        runCatching {
            client.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use LoginStart.Failed(errorMessage(response.code, body))
                val data = envelope(body)
                val state = data.optString("state")
                val url = data.optString("authUrl").ifBlank {
                    data.optString("url").ifBlank { data.optString("verificationUrl") }
                }
                if (state.isBlank() || url.isBlank()) LoginStart.Failed("登录响应缺少 state 或授权地址")
                else LoginStart.Ready(url, state)
            }
        }.getOrElse { LoginStart.Failed("无法连接 ${region.baseUrl}：${it.message ?: "网络错误"}") }
    }

    suspend fun pollLogin(region: WorkBuddyRegion, state: String): LoginPoll = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseOf(region)}/v2/plugin/auth/token?state=$state")
            .get()
            .apply { commonHeaders(region).forEach { (k, v) -> header(k, v) } }
            .build()
        runCatching {
            client.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 202 || response.code == 404) return@use LoginPoll.Pending
                if (!response.isSuccessful) {
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    val pending = json?.optInt("code", -1) in setOf(20001, 20002, 20003, 40001)
                    return@use if (pending) LoginPoll.Pending else LoginPoll.Failed(errorMessage(response.code, body))
                }
                val data = envelope(body)
                val token = data.optString("accessToken").ifBlank { data.optString("token") }
                if (token.isBlank()) LoginPoll.Pending else LoginPoll.Success(tokensOf(data, token))
            }
        }.getOrElse { LoginPoll.Failed(it.message ?: "网络错误") }
    }

    // ---------------------------------------------------------------- 模型目录

    /** 读取当前线路可用的模型；国内走控制台目录，国际走客户端 product config。 */
    suspend fun models(config: AppConfig): List<RemoteModel> = withContext(Dispatchers.IO) {
        check(config.accessToken.isNotBlank()) { "请先登录 WorkBuddy" }
        val region = config.workBuddyRegion
        val path = if (region == WorkBuddyRegion.GLOBAL) "/v3/config" else "/console/enterprises/personal/models"
        val headers = buildMap {
            putAll(chatHeaders(config))
            put("Accept", "application/json, text/plain, */*")
            if (region == WorkBuddyRegion.GLOBAL) put("X-Agent-Type", "main")
        }
        val request = Request.Builder()
            .url("${baseOf(region)}$path")
            .get()
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(errorMessage(response.code, body))
            val data = envelope(body)
            parseModels(data)
        }
    }

    private fun parseModels(data: JSONObject): List<RemoteModel> {
        val cliIds = mutableSetOf<String>()
        data.optJSONArray("agents")?.let { agents ->
            for (index in 0 until agents.length()) {
                val agent = agents.optJSONObject(index) ?: continue
                if (agent.optString("name") != "cli") continue
                agent.optJSONArray("models")?.let { ids ->
                    for (i in 0 until ids.length()) ids.optString(i).takeIf(String::isNotBlank)?.let(cliIds::add)
                }
            }
        }
        val result = mutableListOf<RemoteModel>()
        val seen = mutableSetOf<String>()
        data.optJSONArray("models")?.let { models ->
            for (index in 0 until models.length()) {
                val item = models.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isEmpty() || !seen.add(id)) continue
                if (cliIds.isNotEmpty() && id !in cliIds) continue
                if (item.optBoolean("disabled")) continue
                result += RemoteModel(id, item.optString("name").ifBlank { id })
            }
        }
        return result
    }

    // ---------------------------------------------------------------- Token 刷新

    private suspend fun refresh(config: AppConfig): WorkBuddyTokens? {
        if (config.refreshToken.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseOf(config.workBuddyRegion)}/v2/plugin/auth/token/refresh")
                .post("{}".toRequestBody(JSON))
                .apply {
                    commonHeaders(config.workBuddyRegion).forEach { (k, v) -> header(k, v) }
                    header("X-Refresh-Token", config.refreshToken)
                    header("Authorization", "Bearer ${config.accessToken}")
                }
                .build()
            runCatching {
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) return@use null
                    val data = envelope(response.body?.string().orEmpty())
                    val token = data.optString("accessToken").ifBlank { data.optString("token") }
                    if (token.isBlank()) null else tokensOf(data, token)
                }
            }.getOrNull()
        }
    }

    // ---------------------------------------------------------------- 对话

    class Client(
        private val currentConfig: () -> AppConfig,
        private val onTokens: (WorkBuddyTokens) -> Unit = {},
    ) {
        suspend fun complete(systemPrompt: String, userPrompt: String, maxTokens: Int = 1600): String {
            val config = currentConfig().normalized()
            if (config.useCustom) {
                return OpenAiCompatible.complete(config, systemPrompt, userPrompt, maxTokens)
            }
            if (config.accessToken.isBlank()) error("请先在设置页登录 WorkBuddy")
            val prepared = if (config.expiresAt in 1 until System.currentTimeMillis() + 60_000) {
                refresh(config)?.let { onTokens(it) }
                currentConfig().normalized()
            } else config
            val body = JSONObject().apply {
                put("model", prepared.model.ifBlank { "default-model" })
                put("stream", true)
                put("temperature", 0.7)
                put("max_tokens", maxTokens)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                })
            }.toString()
            var text = runChat(prepared, body)
            var failure = lastFailure
            if (text == null) {
                val renewed = refresh(prepared)
                val latest = if (renewed != null) {
                    onTokens(renewed)
                    currentConfig().normalized()
                } else currentConfig().normalized()
                text = runChat(latest, body)
                if (text == null) failure = lastFailure
            }
            return text ?: error("WorkBuddy 请求失败：${failure.ifBlank { "请重新登录或稍后重试" }}")
        }

        private var lastFailure: String = ""

        private suspend fun runChat(config: AppConfig, body: String): String? = withContext(Dispatchers.IO) {
            lastFailure = ""
            val request = Request.Builder()
                .url("${baseOf(config.workBuddyRegion)}/v2/chat/completions")
                .post(body.toRequestBody(JSON))
                .apply { chatHeaders(config, body).forEach { (k, v) -> header(k, v) } }
                .build()
            runCatching {
                client.newCall(request).await().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        lastFailure = errorMessage(response.code, raw)
                        return@use null
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    if (contentType.contains("event-stream") || raw.trimStart().startsWith("data:")) {
                        parseSse(raw)
                    } else {
                        parseJsonReply(raw)
                    }
                }
            }.onFailure { lastFailure = it.message ?: it.javaClass.simpleName }.getOrNull()
        }
    }

    private fun parseSse(payload: String): String? {
        val builder = StringBuilder()
        payload.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val data = trimmed.removePrefix("data:").trim()
            if (data == "[DONE]") return@forEach
            val json = runCatching { JSONObject(data) }.getOrNull() ?: return@forEach
            builder.append(contentOf(json))
        }
        return builder.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun parseJsonReply(payload: String): String? {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val choices = json.optJSONArray("choices")
        val message = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (message.isNotBlank()) return message
        val direct = json.optString("content").ifBlank { json.optString("text") }
        return direct.ifBlank { contentOf(json) }.takeIf { it.isNotBlank() }
    }

    /** 兼容 OpenAI SSE 与 CodeBuddy 的包裹式事件。 */
    private fun contentOf(json: JSONObject): String {
        val choices = json.optJSONArray("choices")
        val delta = choices?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
        if (delta.isNotEmpty()) return delta
        val message = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (message.isNotEmpty()) return message
        val data = json.optJSONObject("data") ?: return ""
        val nestedChoices = data.optJSONArray("choices")
        val nested = nestedChoices?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
        if (nested.isNotEmpty()) return nested
        return data.optString("content").ifBlank { data.optString("text") }
    }

    private fun envelope(payload: String): JSONObject {
        val json = runCatching { JSONObject(payload) }.getOrElse { return JSONObject() }
        return json.optJSONObject("data") ?: json
    }

    private fun tokensOf(data: JSONObject, token: String): WorkBuddyTokens {
        val expiresIn = data.optLong("expiresIn", data.optLong("expireIn", 0))
        val expiresAt = data.optLong("expiresAt", data.optLong("expireAt", 0)).takeIf { it > 0 }
            ?: if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0
        return WorkBuddyTokens(
            accessToken = token,
            refreshToken = data.optString("refreshToken"),
            expiresAt = expiresAt,
            userId = data.optString("userId").ifBlank { data.optString("uid") }
                .ifBlank { jwtClaim(token, "user_id", "userId", "uid", "sub") },
            enterpriseId = data.optString("enterpriseId").ifBlank { data.optString("entId") }
                .ifBlank { jwtClaim(token, "tenant_id", "tenantId", "enterprise_id", "enterpriseId") },
            domain = data.optString("domain"),
        )
    }

    /** 登录响应偶尔缺少用户或企业标识，从 access_token 的 JWT 载荷补齐。 */
    private fun jwtClaim(token: String, vararg names: String): String {
        val payload = token.split('.').getOrNull(1)?.takeIf(String::isNotBlank) ?: return ""
        val json = runCatching {
            JSONObject(String(android.util.Base64.decode(payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)))
        }.getOrNull() ?: return ""
        names.forEach { name -> json.optString(name).takeIf(String::isNotBlank)?.let { return it } }
        return ""
    }

    private fun errorMessage(code: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val message = json?.optString("msg").orEmpty().ifBlank { json?.optString("message").orEmpty() }
        return when {
            message.isNotBlank() -> "$message（HTTP $code）"
            code == 401 || code == 403 -> "登录已失效，请重新登录 WorkBuddy（HTTP $code）"
            else -> "请求失败（HTTP $code）"
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
        continuation.invokeOnCancellation { runCatching { cancel() } }
    }
}
