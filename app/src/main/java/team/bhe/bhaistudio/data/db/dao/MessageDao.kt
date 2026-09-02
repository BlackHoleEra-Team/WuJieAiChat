package team.bhe.bhaistudio.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import team.bhe.bhaistudio.data.db.entity.MessageEntity
import team.bhe.bhaistudio.data.db.entity.MessageRole

@Dao
interface MessageDao {

    /**
     * 会话消息分页源。
     *
     * 倒序返回（最新在前），配合 LazyColumn 的 reverseLayout 使用，
     * 这样聊天界面最新消息在底部，且滚动到顶部时自动加载更早的历史。
     *
     * 这是桌面端"最多 100 条、超出截断"的替代方案——不再丢历史。
     */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    fun pagingByConversation(conversationId: String): PagingSource<Int, MessageEntity>

    /** 会话全部消息（升序），用于导出或构建上下文 */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun listByConversation(conversationId: String): List<MessageEntity>

    /** 会话全部消息（升序）的实时流，聊天记录管理页统计用 */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    /**
     * 取会话最近 [limit] 条消息，升序返回，用于拼装 AI 请求上下文。
     *
     * 桌面端是把历史拼成文本塞进 system prompt 的（index.js:5730），
     * Android 版改为结构化 messages 数组，更符合各服务商的原生协议。
     */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM message WHERE conversationId = :conversationId 
            ORDER BY createdAt DESC LIMIT :limit
        ) ORDER BY createdAt ASC
        """
    )
    suspend fun listRecent(conversationId: String, limit: Int): List<MessageEntity>

    /** 某一轮分段回复的全部消息，按段序返回 */
    @Query("SELECT * FROM message WHERE replyGroupId = :groupId ORDER BY replyIndex ASC")
    suspend fun listByReplyGroup(groupId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("UPDATE message SET content = :content WHERE id = :id")
    suspend fun updateContent(id: String, content: String)

    @Query("UPDATE message SET thinkingContent = :thinking WHERE id = :id")
    suspend fun updateThinking(id: String, thinking: String?)

    @Query("DELETE FROM message WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 按分段回复分组删除（删一条分段消息时整轮一起删） */
    @Query("DELETE FROM message WHERE replyGroupId = :groupId")
    suspend fun deleteByReplyGroup(groupId: String)

    @Query("DELETE FROM message WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM message WHERE conversationId = :conversationId")
    suspend fun countByConversation(conversationId: String): Int

    /**
     * 最后一条 AI 消息，用于"重新生成"。
     * 分段回复时返回最后一段，删除时按 replyGroupId 整组删。
     */
    @Query(
        """
        SELECT * FROM message 
        WHERE conversationId = :conversationId AND role = :role 
        ORDER BY createdAt DESC LIMIT 1
        """
    )
    suspend fun lastByRole(conversationId: String, role: MessageRole): MessageEntity?
}
