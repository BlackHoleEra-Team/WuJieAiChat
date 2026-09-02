package team.bhe.bhaistudio.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话
 *
 * 桌面端没有这个概念（会话隐式等于联系人），Android 版显式建表，
 * 目的是**为多 Agent 群聊预留位置**：
 *   - [type] = SINGLE 时，[memberIds] 只有一个联系人（等价桌面端行为）
 *   - [type] = GROUP  时，[memberIds] 是多个联系人，即群聊
 *
 * @param id 会话主键
 * @param title 会话标题。单聊时跟随联系人昵称，群聊时用户自定义
 * @param type 见 [ConversationType]
 * @param memberIds 成员联系人 id 列表。Room 不直接支持 List，由 Converters 转 JSON
 * @param pinnedContactId 单聊时指向对应联系人，便于按联系人快速查会话；群聊为 null
 * @param lastMessage 最后一条消息预览，用于列表展示（避免 join 查询）
 * @param lastMessageAt 最后消息时间，用于列表排序
 */
@Entity(
    tableName = "conversation",
    indices = [Index("lastMessageAt"), Index("pinnedContactId")]
)
data class ConversationEntity(
    @PrimaryKey val id: String,

    val title: String = "",
    val type: ConversationType = ConversationType.SINGLE,

    val memberIds: List<String> = emptyList(),
    val pinnedContactId: String? = null,

    val lastMessage: String = "",
    val lastMessageAt: Long = System.currentTimeMillis(),

    /**
     * 上下文压缩摘要：手动/自动压缩后存这里，
     * 构造请求时注入 system prompt 作为被压缩掉的历史的替代。
     */
    val compressedSummary: String = "",

    val createdAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false
)

enum class ConversationType {
    /** 单聊：一个人 + 一个 AI 角色 */
    SINGLE,

    /** 群聊：一个人 + 多个 AI 角色（多 Agent 交互） */
    GROUP
}
