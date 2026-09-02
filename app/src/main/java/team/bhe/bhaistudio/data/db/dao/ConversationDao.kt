package team.bhe.bhaistudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import team.bhe.bhaistudio.data.db.entity.ConversationEntity
import team.bhe.bhaistudio.data.db.entity.ConversationType

@Dao
interface ConversationDao {

    /** 会话列表：置顶优先、最后消息时间倒序 */
    @Query("SELECT * FROM conversation ORDER BY isPinned DESC, lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    /** 单聊：按联系人找会话（桌面端"一个联系人一段会话"的等价查询） */
    @Query("SELECT * FROM conversation WHERE pinnedContactId = :contactId LIMIT 1")
    suspend fun getSingleByContact(contactId: String): ConversationEntity?

    /** 联系人的全部会话（单聊 + 群聊成员），删除联系人时用于级联清理 */
    @Query("SELECT * FROM conversation WHERE pinnedContactId = :contactId")
    suspend fun listByPinnedContact(contactId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE type = :type ORDER BY lastMessageAt DESC")
    fun observeByType(type: ConversationType): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    /** 刷新列表预览。发消息后调用，避免列表 join 查询全部消息 */
    @Query(
        """
        UPDATE conversation 
        SET lastMessage = :preview, lastMessageAt = :time 
        WHERE id = :id
        """
    )
    suspend fun updatePreview(id: String, preview: String, time: Long)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun deleteById(id: String)
}
