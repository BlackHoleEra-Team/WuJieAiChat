package team.bhe.bhaistudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import team.bhe.bhaistudio.data.db.entity.ContactEntity

@Dao
interface ContactDao {

    /** 全部角色，置顶优先、最近活跃优先 */
    @Query("SELECT * FROM contact ORDER BY isPinned DESC, lastActiveAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contact WHERE id = :id")
    fun observeById(id: String): Flow<ContactEntity?>

    @Query("SELECT * FROM contact WHERE id = :id")
    suspend fun getById(id: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Update
    suspend fun update(contact: ContactEntity)

    /** 仅在有实际变化时刷新活跃时间，避免每发一条消息都写库 */
    @Query("UPDATE contact SET lastActiveAt = :time WHERE id = :id")
    suspend fun touch(id: String, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM contact WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM contact")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM contact")
    suspend fun count(): Int
}
