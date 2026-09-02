package team.bhe.bhaistudio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import team.bhe.bhaistudio.data.db.dao.ContactDao
import team.bhe.bhaistudio.data.db.dao.ConversationDao
import team.bhe.bhaistudio.data.db.dao.MemoryDao
import team.bhe.bhaistudio.data.db.dao.MessageDao
import team.bhe.bhaistudio.data.db.dao.ProviderConfigDao
import team.bhe.bhaistudio.data.db.dao.SearchConfigDao
import team.bhe.bhaistudio.data.db.entity.ContactEntity
import team.bhe.bhaistudio.data.db.entity.ConversationEntity
import team.bhe.bhaistudio.data.db.entity.MemoryEntity
import team.bhe.bhaistudio.data.db.entity.MessageEntity
import team.bhe.bhaistudio.data.db.entity.ProviderConfigEntity
import team.bhe.bhaistudio.data.db.entity.SearchConfigEntity

/**
 * 应用数据库
 *
 * 六张表对应移植自桌面端的六类数据：
 *   contact         角色（ContactConfig 目录下的 {id}.json）
 *   conversation    会话（桌面端无此概念，为群聊预留）
 *   message         消息（msg 目录下的 {contactId}.wjm，去掉 100 条上限）
 *   memory          长期记忆（long-term-memories 目录下的 {contactId}_memories.json）
 *   provider_config 服务商配置（桌面端三家 API 类 + localStorage.apiKeys 的合体）
 *   search_config   自定义联网搜索配置（Firecrawl / Tavily / Bing / serper）
 *
 * 版本从 3 开始，后续每次改表结构都要写 Migration 并 +1，
 * 否则 Room 会直接抛异常导致老用户崩溃。
 */
@Database(
    entities = [
        ContactEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        ProviderConfigEntity::class,
        SearchConfigEntity::class
    ],
    version = 12,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun providerConfigDao(): ProviderConfigDao
    abstract fun searchConfigDao(): SearchConfigDao
}
