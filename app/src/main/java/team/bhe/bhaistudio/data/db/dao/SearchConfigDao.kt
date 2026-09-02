package team.bhe.bhaistudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import team.bhe.bhaistudio.data.db.entity.SearchConfigEntity

@Dao
interface SearchConfigDao {

    /** 所有配置，按添加顺序 */
    @Query("SELECT * FROM search_config ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SearchConfigEntity>>

    @Query("SELECT * FROM search_config ORDER BY createdAt ASC")
    suspend fun listAll(): List<SearchConfigEntity>

    @Insert
    suspend fun insert(config: SearchConfigEntity)

    @Query("DELETE FROM search_config WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM search_config")
    suspend fun count(): Int
}
