package cn.lhup.wechatmood.reply

import cn.lhup.wechatmood.core.AppConfig
import cn.lhup.wechatmood.core.WorkBuddyRegion

/** 回复模型统一走当前选择的模型服务（WorkBuddy 或自定义 OpenAI 兼容接口）。 */
class ReplySettings private constructor(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val loggedIn: Boolean,
    val region: WorkBuddyRegion,
) {
    val isConfigured get() = loggedIn && model.isNotBlank()

    companion object {
        fun empty() = ReplySettings("", "", "", false, WorkBuddyRegion.CN)

        fun fromConfig(config: AppConfig): ReplySettings = ReplySettings(
            endpoint = if (config.useCustom) config.customBaseUrl else config.workBuddyRegion.baseUrl,
            apiKey = if (config.useCustom) config.customApiKey else "",
            model = if (config.useCustom) config.customModel else config.model,
            loggedIn = config.canAnalyze,
            region = config.workBuddyRegion,
        )
    }
}
