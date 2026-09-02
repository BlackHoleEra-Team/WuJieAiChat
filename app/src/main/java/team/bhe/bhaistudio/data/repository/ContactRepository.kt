package team.bhe.bhaistudio.data.repository

import kotlinx.coroutines.flow.Flow
import team.bhe.bhaistudio.data.db.dao.ContactDao
import team.bhe.bhaistudio.data.db.dao.ConversationDao
import team.bhe.bhaistudio.data.db.dao.MemoryDao
import team.bhe.bhaistudio.data.db.entity.ContactEntity
import java.util.UUID

/**
 * 角色（联系人）仓库
 *
 * 对应桌面端的 loadContacts / saveContacts / saveContact（js/index.js）。
 */
class ContactRepository(
    private val dao: ContactDao,
    private val conversationDao: ConversationDao,
    private val memoryDao: MemoryDao
) {

    fun observeAll(): Flow<List<ContactEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<ContactEntity?> = dao.observeById(id)

    suspend fun getById(id: String): ContactEntity? = dao.getById(id)

    /**
     * 新建角色。
     *
     * id 沿用桌面端的"时间戳字符串"方案，便于将来从桌面端迁移数据。
     */
    suspend fun create(contact: ContactEntity): ContactEntity {
        val entity = contact.copy(
            id = contact.id.ifBlank { System.currentTimeMillis().toString() },
            createdAt = System.currentTimeMillis()
        )
        dao.insert(entity)
        return entity
    }

    suspend fun update(contact: ContactEntity) = dao.update(contact)

    /** 标记活跃时间，用于列表"最近联系"排序 */
    suspend fun touch(id: String) = dao.touch(id)

    /**
     * 删除角色并级联清理关联数据，避免留下悬空引用：
     *   · 该联系人的会话（消息表外键 ON DELETE CASCADE 会自动清除全部消息）
     *   · 该联系人的长期记忆
     */
    suspend fun delete(id: String) {
        conversationDao.listByPinnedContact(id).forEach { conversationDao.deleteById(it.id) }
        dao.deleteById(id)
        memoryDao.deleteByContact(id)
    }

    suspend fun count(): Int = dao.count()

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
