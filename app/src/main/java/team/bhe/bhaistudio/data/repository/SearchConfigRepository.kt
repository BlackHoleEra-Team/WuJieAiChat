package team.bhe.bhaistudio.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import team.bhe.bhaistudio.data.db.dao.SearchConfigDao
import team.bhe.bhaistudio.data.db.entity.SearchConfigEntity
import team.bhe.bhaistudio.data.db.entity.SearchProvider

/**
 * 自定义联网搜索配置仓库
 *
 * 密钥用 [KeyCrypto]（Android Keystore）加密落库，
 * 对外暴露的列表与查询结果都是**解密后的明文**（仅在内存流转）。
 *
 * 允许多条配置，取用时按添加顺序返回第一个。
 */
class SearchConfigRepository(
    private val dao: SearchConfigDao
) {

    /** 全部配置（解密后），按添加顺序 */
    fun observeAll(): Flow<List<SearchConfigEntity>> =
        dao.observeAll().map { list -> list.map { it.withDecryptedKey() } }

    /** 全部配置（解密后），按添加顺序 */
    suspend fun listAll(): List<SearchConfigEntity> =
        dao.listAll().map { it.withDecryptedKey() }

    /** 取第一条配置（自动使用的那个），没有则 null */
    suspend fun first(): SearchConfigEntity? =
        listAll().firstOrNull()

    /** 是否已配置自定义搜索 */
    suspend fun hasAny(): Boolean = dao.count() > 0

    suspend fun add(provider: SearchProvider, plainKey: String, name: String = "") {
        val key = plainKey.trim()
        if (key.isEmpty()) return
        dao.insert(
            SearchConfigEntity(
                id = "search-${System.currentTimeMillis()}",
                provider = provider,
                apiKey = KeyCrypto.encrypt(key),
                name = name.trim(),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    private fun SearchConfigEntity.withDecryptedKey(): SearchConfigEntity =
        copy(apiKey = KeyCrypto.decrypt(apiKey) ?: "")
}
