package team.bhe.bhaistudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import team.bhe.bhaistudio.data.db.entity.ProviderConfigEntity

@Dao
interface ProviderConfigDao {

    @Query("SELECT * FROM provider_config ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_config WHERE id = :id")
    suspend fun getById(id: String): ProviderConfigEntity?

    @Query("SELECT * FROM provider_config WHERE id = :id")
    fun observeById(id: String): Flow<ProviderConfigEntity?>

    @Query("SELECT * FROM provider_config ORDER BY createdAt ASC")
    suspend fun listAll(): List<ProviderConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<ProviderConfigEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ProviderConfigEntity)

    @Query("UPDATE provider_config SET models = :models, name = :name, baseUrl = :baseUrl, chatPath = :chatPath WHERE id = :id")
    suspend fun update(
        id: String,
        models: List<String>,
        name: String,
        baseUrl: String,
        chatPath: String
    )

    @Query("SELECT COUNT(*) FROM provider_config")
    suspend fun count(): Int

    @Query("DELETE FROM provider_config WHERE id = :id")
    suspend fun deleteById(id: String)
}
