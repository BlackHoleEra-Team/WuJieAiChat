package team.bhe.bhaistudio.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息
 *
 * 对应桌面端 msg/{contactId}.wjm 里的数组元素。
 *
 * 桌面端的三个问题在这里一并解决：
 *   1. **100 条上限导致历史丢失** → Room + Paging3 分页查询，不再截断
 *   2. **type 用字符串 "user"/"ai"** → 改为 [MessageRole] 枚举
 *   3. **分段回复的多个气泡无法归组** → 增加 [replyGroupId] / [replyIndex]
 *
 * 关于分段回复的建模：
 * AI 一次完整回复若被切成 N 段，会落库 N 条 [MessageEntity]，
 * 它们共享同一个 [replyGroupId]，靠 [replyIndex] 排序。
 * UI 层据此判断"是否与上一条连续"，从而收紧圆角形成成组观感。
 *
 * @param conversationId 所属会话
 * @param role 发送者角色
 * @param content 正文（AI 消息为 Markdown）
 * @param thinkingContent 深度思考内容（reasoning_content），仅 AI 且开启深度思考时有值
 * @param replyGroupId 同一轮回复的分组 id；非分段回复时可为 null
 * @param replyIndex 在分组内的序号，从 0 开始
 */
@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "createdAt"]),
        Index("replyGroupId")
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,

    val conversationId: String,
    val role: MessageRole,

    val content: String = "",
    val thinkingContent: String? = null,

    val createdAt: Long = System.currentTimeMillis(),

    val replyGroupId: String? = null,
    val replyIndex: Int = 0
)

enum class MessageRole {
    /** 用户发出 */
    USER,

    /** AI 回复 */
    AI
}

/** 生成消息 id：桌面端为 `msg-<时间戳>`，保持一致便于迁移 */
fun newMessageId(): String = "msg-${System.currentTimeMillis()}-${(0..9999).random()}"
