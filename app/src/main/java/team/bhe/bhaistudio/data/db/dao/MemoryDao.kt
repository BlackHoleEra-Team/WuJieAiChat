package team.bhe.bhaistudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import team.bhe.bhaistudio.data.db.entity.MemoryEntity
import team.bhe.bhaistudio.data.db.entity.MemoryType

@Dao
interface MemoryDao {

    /** 某角色未进拾忆区的记忆，最新在前 */
    @Query("SELECT * FROM memory WHERE contactId = :contactId AND state != 'RECYCLED' ORDER BY createTime DESC")
    fun observeByContact(contactId: String): Flow<List<MemoryEntity>>

    /** 某角色某层未进拾忆区的记忆，最新在前 */
    @Query("SELECT * FROM memory WHERE contactId = :contactId AND memoryType = :type AND state != 'RECYCLED' ORDER BY createTime DESC")
    fun observeByType(contactId: String, type: MemoryType): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory WHERE contactId = :contactId AND state != 'RECYCLED' ORDER BY createTime DESC")
    suspend fun listByContact(contactId: String): List<MemoryEntity>

    @Query("SELECT * FROM memory WHERE id = :id")
    suspend fun getById(id: Long): MemoryEntity?

    @Query("SELECT * FROM memory")
    suspend fun listAll(): List<MemoryEntity>

    @Query("SELECT * FROM memory WHERE contactId = :contactId AND memoryType = :type AND state != 'RECYCLED' ORDER BY createTime DESC")
    suspend fun listByType(contactId: String, type: MemoryType): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity)

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory WHERE contactId = :contactId")
    suspend fun deleteByContact(contactId: String)

    @Query("SELECT COUNT(*) FROM memory WHERE contactId = :contactId")
    suspend fun countByContact(contactId: String): Int

    /** 按内容/索引关键词检索（只查未进拾忆区的记忆，供 recall 穿透 ACTIVE+INACTIVE） */
    @Query(
        "SELECT * FROM memory WHERE contactId = :contactId " +
            "AND state != 'RECYCLED' " +
            "AND (summary LIKE '%' || :keyword || '%' OR indexSummary LIKE '%' || :keyword || '%') " +
            "ORDER BY createTime DESC"
    )
    suspend fun searchByKeyword(contactId: String, keyword: String): List<MemoryEntity>

    /** 记一次调取：激活计数 + 刷新最后访问时间 */
    @Query("UPDATE memory SET accessCount = accessCount + 1, lastAccessAt = :time WHERE id = :id")
    suspend fun bumpAccess(id: Long, time: Long)

    /** 某角色进拾忆区的记忆，最近进拾忆在前 */
    @Query("SELECT * FROM memory WHERE contactId = :contactId AND state = 'RECYCLED' ORDER BY recycledAt DESC")
    fun observeRecycled(contactId: String): Flow<List<MemoryEntity>>

    /** 全部角色的拾忆记忆（拾忆页全局列表用），最近进拾忆在前 */
    @Query("SELECT * FROM memory WHERE state = 'RECYCLED' ORDER BY recycledAt DESC")
    fun observeAllRecycled(): Flow<List<MemoryEntity>>
}
