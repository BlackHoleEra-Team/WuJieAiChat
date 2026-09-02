package team.bhe.bhaistudio.data.repository

import kotlinx.coroutines.flow.Flow
import team.bhe.bhaistudio.data.db.dao.ConversationDao
import team.bhe.bhaistudio.data.db.entity.ConversationEntity
import team.bhe.bhaistudio.data.db.entity.ConversationType

/**
 * 会话仓库
 *
 * 桌面端没有会话概念（会话隐式等于联系人），
 * 这里承担一个关键职责：**把"联系人 ↔ 单聊会话"的映射关系维护起来**，
 * 让上层可以像桌面端一样按联系人取会话，同时不丧失群聊扩展能力。
 */
class ConversationRepository(
    private val dao: ConversationDao
) {

    fun observeAll(): Flow<List<ConversationEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<ConversationEntity?> = dao.observeById(id)

    suspend fun getById(id: String): ConversationEntity? = dao.getById(id)

    /**
     * 获取联系人的单聊会话；不存在则创建。
     *
     * 这是桌面端"打开联系人即进入其唯一会话"行为的等价实现。
     */
    suspend fun getOrCreateSingle(contactId: String, title: String): ConversationEntity {
        dao.getSingleByContact(contactId)?.let { return it }

        val now = System.currentTimeMillis()
        val entity = ConversationEntity(
            id = "single-$contactId",
            title = title,
            type = ConversationType.SINGLE,
            memberIds = listOf(contactId),
            pinnedContactId = contactId,
            createdAt = now,
            lastMessageAt = now
        )
        dao.insert(entity)
        return entity
    }

    /** 新建群聊（多 Agent 交互预留入口） */
    suspend fun createGroup(title: String, memberIds: List<String>): ConversationEntity {
        val now = System.currentTimeMillis()
        val entity = ConversationEntity(
            id = "group-$now",
            title = title,
            type = ConversationType.GROUP,
            memberIds = memberIds,
            createdAt = now,
            lastMessageAt = now
        )
        dao.insert(entity)
        return entity
    }

    suspend fun update(conversation: ConversationEntity) = dao.update(conversation)

    /** 刷新列表预览，避免列表页 join 全表消息 */
    suspend fun updatePreview(id: String, preview: String) =
        dao.updatePreview(id, preview, System.currentTimeMillis())

    /** 清空会话预览（用于清空聊天记录后） */
    suspend fun clearPreview(id: String) = dao.updatePreview(id, "", 0L)

    /** 更新上下文压缩摘要 */
    suspend fun updateCompressedSummary(id: String, summary: String) {
        dao.getById(id)?.let { dao.update(it.copy(compressedSummary = summary)) }
    }

    suspend fun delete(id: String) = dao.deleteById(id)
}
