package cn.lhup.wechatmood.analysis

import cn.lhup.wechatmood.hook.MessageMetadata
import cn.lhup.wechatmood.voice.VoiceSource
import cn.lhup.wechatmood.voice.VoiceText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatTurn(
    val speaker: String,
    val text: String,
    val createdAt: Long,
    val messageId: Long,
    val isSend: Boolean,
    val voice: VoiceSource? = null,
    val quoted: String? = null,
)

data class ChatContext(
    val talker: String,
    val turns: List<ChatTurn>,
    val target: ChatTurn,
    val currentTime: Long = System.currentTimeMillis(),
)

object TimeText {
    private val format = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    @Synchronized
    fun format(millis: Long): String = if (millis <= 0) "时间未知" else format.format(Date(millis))

    @Synchronized
    fun full(millis: Long): String = if (millis <= 0) "时间未知"
    else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
}

/** 从当前适配器位置向前回溯，得到连续上下文；limit <= 0 表示不限条数，仅受字数上限约束。 */
object ChatContexts {
    const val MAX_CHARACTERS = 14000

    fun collect(
        message: MessageMetadata,
        position: Int,
        limit: Int,
        itemAt: (Int) -> MessageMetadata?,
    ): ChatContext? {
        if (message.isSend !in 0..1 || message.talker.isBlank()) return null
        val targetText = message.analysisText()?.trim().orEmpty()
        if (targetText.isEmpty()) return null
        val target = ChatTurn(
            speaker = message.speaker(),
            text = targetText,
            createdAt = message.createdAt,
            messageId = message.messageId,
            isSend = message.isSend == 1,
            voice = message.voiceSource(),
            quoted = message.quotedMessage()?.text?.trim()?.takeIf(String::isNotEmpty),
        )
        val previous = mutableListOf<ChatTurn>()
        var cursor = position - 1
        var characters = targetText.length
        while (cursor >= 0 && (limit <= 0 || previous.size < limit) && characters < MAX_CHARACTERS) {
            val record = runCatching { itemAt(cursor) }.getOrNull()
            cursor--
            if (record == null || record.talker != message.talker || record.isSend !in 0..1) continue
            val text = record.analysisText()?.trim().orEmpty()
            if (text.isEmpty()) continue
            val size = text.length + (record.quotedMessage()?.text?.length ?: 0)
            if (characters + size > MAX_CHARACTERS) break
            characters += size
            previous += ChatTurn(
                speaker = record.speaker(),
                text = text,
                createdAt = record.createdAt,
                messageId = record.messageId,
                isSend = record.isSend == 1,
                voice = record.voiceSource(),
                quoted = record.quotedMessage()?.text?.trim()?.takeIf(String::isNotEmpty),
            )
        }
        val ordered = previous.asReversed() + target
        return ChatContext(message.talker, ordered, target)
    }
}

object PromptBuilder {
    private fun formatTurn(turn: ChatTurn, index: Int): String = buildString {
        append(index + 1).append(". [").append(TimeText.full(turn.createdAt)).append("] ")
        append(turn.speaker).append("：").append(turn.text.replace('\n', ' '))
        turn.quoted?.let { append("（引用：").append(it.replace('\n', ' ')).append("）") }
    }

    fun chat(context: ChatContext): String = buildString {
        append("当前系统时间：").append(TimeText.full(context.currentTime)).append('\n')
        append("聊天对象：").append(contactLabel(context.talker)).append('\n')
        append("【聊天记录（时间升序）】\n")
        context.turns.forEachIndexed { index, turn -> append(formatTurn(turn, index)).append('\n') }
        append("\n【目标消息】\n")
        append('[').append(TimeText.full(context.target.createdAt)).append("] ")
        append(context.target.speaker).append("：").append(context.target.text)
        context.target.quoted?.let { append("\n（引用的消息：").append(it).append("）") }
    }

    fun replies(context: ChatContext): String = buildString {
        append("当前系统时间：").append(TimeText.full(context.currentTime)).append('\n')
        append("聊天对象：").append(contactLabel(context.talker)).append('\n')
        append("【聊天记录（时间升序）】\n")
        context.turns.forEachIndexed { index, turn -> append(formatTurn(turn, index)).append('\n') }
        append("\n请为最后一条消息生成可选回复。")
    }

    fun assistant(context: ChatContext, instruction: String): String = buildString {
        append("当前系统时间：").append(TimeText.full(context.currentTime)).append('\n')
        append("聊天对象：").append(contactLabel(context.talker)).append('\n')
        if (instruction.isNotBlank()) append("本次补充要求：").append(instruction.trim()).append('\n')
        append("【聊天记录（时间升序）】\n")
        context.turns.forEachIndexed { index, turn -> append(formatTurn(turn, index)).append('\n') }
    }

    fun contactLabel(talker: String): String = when {
        talker.endsWith("@chatroom") -> "群聊"
        talker.startsWith("gh_") -> "公众号"
        else -> "微信联系人"
    }
}

/** 解析编号回复；兼容模型偶尔输出的“-”“·”项目符号。 */
object ReplyParser {
    private val numbered = Regex("^\\s*(?:\\d+\\s*[.、)]|[-·*])\\s*(.+)$")

    fun parse(text: String, count: Int): List<String> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val items = lines.mapNotNull { line -> numbered.find(line)?.groupValues?.get(1)?.trim() }
        val source = if (items.isNotEmpty()) items else lines
        return source.map { it.trim().trim('“', '”', '"') }
            .filter { it.isNotEmpty() && it.length <= 300 }
            .distinct()
            .take(count)
    }
}

fun ChatTurn.isPlaceholder(): Boolean = voice != null && (text == VoiceText.WAITING || text == VoiceText.FAILED)
