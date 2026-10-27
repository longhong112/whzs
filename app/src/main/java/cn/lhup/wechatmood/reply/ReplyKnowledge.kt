package cn.lhup.wechatmood.reply

import android.content.Context
import cn.lhup.wechatmood.core.AppConfigStore

/**
 * 沟通方法不再读取打包的 skill 资料，而是使用设置页可编辑的“军师提示词”。
 * 保留原接口，避免聊天面板大面积改动。
 */
object ReplyKnowledge {
    @Synchronized
    fun load(context: Context, relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED): String =
        AppConfigStore.load().prompts.assistant
}
