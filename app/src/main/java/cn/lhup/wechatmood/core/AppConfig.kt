package cn.lhup.wechatmood.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class WorkBuddyRegion(val id: String, val label: String, val baseUrl: String) {
    CN("cn", "国内版", "https://copilot.tencent.com"),
    GLOBAL("global", "国际版", "https://www.workbuddy.ai");

    companion object {
        fun from(id: String?): WorkBuddyRegion = entries.firstOrNull { it.id == id } ?: CN
    }
}

/** 四套可编辑提示词，对应四类智能功能。 */
data class PromptSettings(
    val analysis: String = DEFAULT_ANALYSIS,
    val sent: String = DEFAULT_SENT,
    val replies: String = DEFAULT_REPLIES,
    val assistant: String = DEFAULT_ASSISTANT,
    val topic: String = DEFAULT_TOPIC,
) {
    companion object {
        val DEFAULT_ANALYSIS = """
你是一名资深的中文社交沟通分析师。你会收到一段按时间升序排列的微信聊天记录，以及当前系统时间。请只针对【目标消息】进行分析，并结合上下文判断。
输出四段纯文本，每段以【】标题开头：
【语义解读】用一两句话说明这条消息字面上的意思。
【真实意图】判断对方最可能的真实意图、潜台词与期待；如有必要给出次要可能。
【情绪与关系】概括情绪状态、亲疏变化和值得留意的信号，保持简短。
【回复建议】给出 1-3 条可以直接发送的中文回复，并用括号注明语气。
要求：
- 不要臆造聊天记录里不存在的信息；时间间隔可作为判断依据。
- 不评判对错、不堆砌套话，语气自然具体。
- 只使用简体中文纯文本，不要使用 Markdown 表格或代码块，不要输出 JSON。
""".trim()

        val DEFAULT_SENT = """
你是一名沟通复盘教练。用户（记录中的“我”）已经发出了【目标消息】，请评估这条已发送消息是否得体。
输出四段纯文本，每段以【】标题开头：
【回应度】是否回应了对方消息的核心诉求，是否答非所问。
【得体性】语气、长度、边界感是否合适，是否给对方造成压力。
【风险点】指出可能引起误解或冷场的表达；没有风险就写“无”。
【改写建议】给出 1-2 条更合适的替代表达。
要求：客观、具体、简短；只使用简体中文纯文本，不要输出 JSON。
""".trim()

        val DEFAULT_REPLIES = """
你是一名中文聊天回复生成器。请根据聊天记录，为最后一条消息生成 {count} 条可以直接发送的回复。
要求：
- 风格尽量不同（例如：呼应共情、轻松幽默、推进话题、简洁回应）。
- 每条不超过 40 个汉字，语气自然，不要出现“作为AI”等字样。
- 每条单独一行，以“1. ”“2. ”这样的编号开头，只输出编号行，不要任何解释或标题。
""".trim()

        val DEFAULT_ASSISTANT = """
你是用户的微信聊天军师，帮助用户在自然、不冒犯的前提下把对话聊下去。
基本原则：
- 先接住对方的情绪和核心信息，再表达自己的观点。
- 少评判、少说教，多用具体的细节回应，必要时用一个轻问题把话题递回去。
- 不编造事实，不油腻、不卑微、不查户口式提问。
请结合对方身份、聊天背景和最近的聊天记录，输出 3 条可直接发送的回复，每条一行并以“1. ”编号。
""".trim()

        val DEFAULT_TOPIC = """
你是聊天话题策划。请根据聊天记录和当前时间，给出 3 个自然、低压力、与上下文相关的新话题。
要求：每个话题一行，以“1. ”编号；每个话题附一句可直接发送的引导语；不要重复最近已经聊过的内容。
""".trim()
    }
}

data class AppConfig(
    val revision: Long = 0,
    val region: String = WorkBuddyRegion.CN.id,
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val userId: String = "",
    val enterpriseId: String = "",
    val domain: String = "",
    val model: String = "default-model",
    val useCustom: Boolean = false,
    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val customModel: String = "",
    val autoAnalyze: Boolean = false,
    val autoReplies: Boolean = true,
    val analyzeSent: Boolean = false,
    val showMessageTime: Boolean = false,
    val repliesForSent: Boolean = false,
    val replyCount: Int = 3,
    val replyKeepMessages: Int = 1,
    val analysisKeepMessages: Int = 0,
    val sentKeepMessages: Int = 0,
    val contextMessages: Int = 10,
    val replyHistoryMessages: Int = 12,
    val license: String = "",
    val prompts: PromptSettings = PromptSettings(),
    val defaultsVersion: Int = 1,
) {
    val workBuddyRegion: WorkBuddyRegion get() = WorkBuddyRegion.from(region)
    val loggedIn: Boolean get() = accessToken.isNotBlank()
    val canAnalyze: Boolean get() = if (useCustom)
        customBaseUrl.isNotBlank() && customApiKey.isNotBlank() && customModel.isNotBlank()
    else accessToken.isNotBlank()
    fun withTokens(token: String, refresh: String, expiresAt: Long, userId: String,
        enterpriseId: String, domain: String, revision: Long): AppConfig = copy(
        accessToken = token, refreshToken = refresh, expiresAt = expiresAt, userId = userId,
        enterpriseId = enterpriseId, domain = domain, revision = revision)

    fun normalized(): AppConfig = copy(
        replyCount = replyCount.coerceIn(1, 10),
        replyKeepMessages = replyKeepMessages.coerceIn(0, 50),
        analysisKeepMessages = analysisKeepMessages.coerceIn(0, 50),
        sentKeepMessages = sentKeepMessages.coerceIn(0, 50),
        contextMessages = contextMessages.coerceIn(0, 999),
        replyHistoryMessages = replyHistoryMessages.coerceIn(3, 60),
        model = model.trim().ifEmpty { "default-model" },
        customBaseUrl = customBaseUrl.trim().trimEnd('/'),
        customApiKey = customApiKey.trim(),
        customModel = customModel.trim(),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("revision", revision)
        put("region", region)
        put("accessToken", accessToken)
        put("refreshToken", refreshToken)
        put("expiresAt", expiresAt)
        put("userId", userId)
        put("enterpriseId", enterpriseId)
        put("domain", domain)
        put("model", model)
        put("useCustom", useCustom)
        put("customBaseUrl", customBaseUrl)
        put("customApiKey", customApiKey)
        put("customModel", customModel)
        put("autoAnalyze", autoAnalyze)
        put("autoReplies", autoReplies)
        put("analyzeSent", analyzeSent)
        put("showMessageTime", showMessageTime)
        put("repliesForSent", repliesForSent)
        put("replyCount", replyCount)
        put("replyKeepMessages", replyKeepMessages)
        put("analysisKeepMessages", analysisKeepMessages)
        put("sentKeepMessages", sentKeepMessages)
        put("contextMessages", contextMessages)
        put("replyHistoryMessages", replyHistoryMessages)
        put("license", license)
        put("defaultsVersion", defaultsVersion)
        put("prompts", JSONObject().apply {
            put("analysis", prompts.analysis)
            put("sent", prompts.sent)
            put("replies", prompts.replies)
            put("assistant", prompts.assistant)
            put("topic", prompts.topic)
        })
    }

    companion object {
        fun fromJson(json: JSONObject): AppConfig {
            val defaults = AppConfig()
            val prompts = json.optJSONObject("prompts") ?: JSONObject()
            // 旧版本没有 defaultsVersion：升级后一次性套用新的默认开关（关闭自动分析 / 开启可选回复 / 关闭复盘）。
            val legacy = !json.has("defaultsVersion")
            return AppConfig(
                revision = json.optLong("revision", 0),
                region = WorkBuddyRegion.from(json.optString("region", defaults.region)).id,
                accessToken = json.optString("accessToken", ""),
                refreshToken = json.optString("refreshToken", ""),
                expiresAt = json.optLong("expiresAt", 0),
                userId = json.optString("userId", ""),
                enterpriseId = json.optString("enterpriseId", ""),
                domain = json.optString("domain", ""),
                model = json.optString("model", defaults.model),
                useCustom = json.optBoolean("useCustom", defaults.useCustom),
                customBaseUrl = json.optString("customBaseUrl", ""),
                customApiKey = json.optString("customApiKey", ""),
                customModel = json.optString("customModel", ""),
                autoAnalyze = if (legacy) defaults.autoAnalyze else json.optBoolean("autoAnalyze", defaults.autoAnalyze),
                autoReplies = if (legacy) defaults.autoReplies else json.optBoolean("autoReplies", defaults.autoReplies),
                analyzeSent = if (legacy) defaults.analyzeSent else json.optBoolean("analyzeSent", defaults.analyzeSent),
                showMessageTime = json.optBoolean("showMessageTime", defaults.showMessageTime),
                repliesForSent = json.optBoolean("repliesForSent", defaults.repliesForSent),
                replyCount = json.optInt("replyCount", defaults.replyCount),
                replyKeepMessages = json.optInt("replyKeepMessages", defaults.replyKeepMessages),
                analysisKeepMessages = json.optInt("analysisKeepMessages", defaults.analysisKeepMessages),
                sentKeepMessages = json.optInt("sentKeepMessages", defaults.sentKeepMessages),
                contextMessages = json.optInt("contextMessages", defaults.contextMessages),
                replyHistoryMessages = json.optInt("replyHistoryMessages", defaults.replyHistoryMessages),
                license = json.optString("license", ""),
                defaultsVersion = defaults.defaultsVersion,
                prompts = PromptSettings(
                    analysis = prompts.optString("analysis", PromptSettings.DEFAULT_ANALYSIS).ifBlank { PromptSettings.DEFAULT_ANALYSIS },
                    sent = prompts.optString("sent", PromptSettings.DEFAULT_SENT).ifBlank { PromptSettings.DEFAULT_SENT },
                    replies = prompts.optString("replies", PromptSettings.DEFAULT_REPLIES).ifBlank { PromptSettings.DEFAULT_REPLIES },
                    assistant = prompts.optString("assistant", PromptSettings.DEFAULT_ASSISTANT).ifBlank { PromptSettings.DEFAULT_ASSISTANT },
                    topic = prompts.optString("topic", PromptSettings.DEFAULT_TOPIC).ifBlank { PromptSettings.DEFAULT_TOPIC },
                ),
            ).normalized()
        }
    }
}

/** 配置文件读写；两个进程按文件修改时间判断是否需要重新加载。 */
object AppConfigStore {
    @Volatile private var cached: AppConfig = AppConfig()
    @Volatile private var cachedStamp: Long = -1

    fun load(force: Boolean = false): AppConfig {
        val file = SharedStorage.configFile()
        val stamp = if (file.isFile) file.lastModified() else 0L
        if (!force && stamp == cachedStamp) return cached
        if (!file.isFile) {
            cachedStamp = 0L
            return cached
        }
        val loaded = runCatching { AppConfig.fromJson(JSONObject(file.readText())) }
            .getOrElse { cached }
        cached = loaded
        cachedStamp = stamp
        return loaded
    }

    fun save(config: AppConfig): AppConfig {
        val normalized = config.normalized()
        val next = normalized.copy(revision = maxOf(normalized.revision, cached.revision) + 1)
        write(next)
        cached = next
        cachedStamp = SharedStorage.configFile().lastModified()
        return next
    }

    fun mutate(change: (AppConfig) -> AppConfig): AppConfig {
        synchronized(this) {
            val current = load(force = true)
            return save(change(current))
        }
    }

    private fun write(config: AppConfig) {
        val file: File = SharedStorage.configFile()
        val text = config.toJson().toString(2)
        val temp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        }.onFailure {
            file.writeText(text)
            temp.delete()
        }
    }
}
