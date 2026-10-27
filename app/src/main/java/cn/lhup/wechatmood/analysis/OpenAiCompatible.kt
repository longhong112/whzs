package cn.lhup.wechatmood.analysis

import cn.lhup.wechatmood.core.AppConfig
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 用户自填的 OpenAI 兼容接口：只用于对话与模型列表，不参与 WorkBuddy 登录。 */
object OpenAiCompatible {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun chatUrl(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    private fun modelsUrl(base: String): String {
        val trimmed = base.trim().trimEnd('/').removeSuffix("/chat/completions")
        return "$trimmed/models"
    }

    private fun headers(config: AppConfig): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json, text/event-stream",
        "Authorization" to "Bearer ${config.customApiKey}",
    )

    suspend fun complete(config: AppConfig, systemPrompt: String, userPrompt: String,
        maxTokens: Int = 1600): String {
        check(config.customBaseUrl.isNotBlank()) { "请先填写自定义接口地址" }
        check(config.customApiKey.isNotBlank()) { "请先填写自定义接口的 API Key" }
        check(config.customModel.isNotBlank()) { "请先选择或填写模型名称" }
        val body = JSONObject().apply {
            put("model", config.customModel)
            put("stream", false)
            put("temperature", 0.7)
            put("max_tokens", maxTokens)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }.toString()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(chatUrl(config.customBaseUrl))
                .post(body.toRequestBody(JSON))
                .apply { headers(config).forEach { (key, value) -> header(key, value) } }
                .build()
            client.newCall(request).await().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(errorText(response.code, raw))
                val contentType = response.header("Content-Type").orEmpty()
                val text = if (contentType.contains("event-stream") || raw.trimStart().startsWith("data:"))
                    parseSse(raw) else parseJson(raw)
                text?.takeIf { it.isNotBlank() } ?: error("接口返回了空内容")
            }
        }
    }

    /** 读取 OpenAI 兼容接口的模型列表。 */
    suspend fun models(config: AppConfig): List<RemoteModel> {
        check(config.customBaseUrl.isNotBlank()) { "请先填写自定义接口地址" }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(modelsUrl(config.customBaseUrl))
                .get()
                .apply { headers(config).forEach { (key, value) -> header(key, value) } }
                .build()
            client.newCall(request).await().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(errorText(response.code, raw))
                val json = runCatching { JSONObject(raw) }.getOrElse {
                    error("模型接口返回的不是 JSON：${raw.trim().take(120)}")
                }
                val data = json.optJSONArray("data") ?: json.optJSONArray("models")
                if (data == null) error("模型列表为空或格式不兼容")
                val result = mutableListOf<RemoteModel>()
                val seen = mutableSetOf<String>()
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id").ifBlank { item.optString("name") }.trim()
                    if (id.isEmpty() || !seen.add(id)) continue
                    result += RemoteModel(id, item.optString("name").ifBlank { id })
                }
                result
            }
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

    private fun parseJson(payload: String): String? {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")?.optString("content").orEmpty()
        if (message.isNotBlank()) return message
        return contentOf(json).takeIf { it.isNotBlank() }
    }

    private fun contentOf(json: JSONObject): String {
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        return choice.optJSONObject("delta")?.optString("content").orEmpty()
            .ifBlank { choice.optJSONObject("message")?.optString("content").orEmpty() }
            .ifBlank { choice.optString("text") }
    }

    private fun errorText(code: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val message = json?.optJSONObject("error")?.optString("message").orEmpty()
            .ifBlank { json?.optString("message").orEmpty() }
        return if (message.isNotBlank()) "$message（HTTP $code）"
        else "请求失败（HTTP $code）${body.trim().take(160)}".trim()
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
