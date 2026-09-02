package team.bhe.bhaistudio.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import team.bhe.bhaistudio.data.db.entity.MemorySourceMessage

/**
 * Room 类型转换器
 *
 * Room 无法直接存储集合，这里统一序列化为 JSON 文本。
 * 使用 kotlinx.serialization，与网络层共用同一套 JSON 配置。
 */
class Converters {

    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromStringList(value: List<String>?): String =
        if (value == null) "[]" else json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String?): List<String> =
        value?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(it) } ?: emptyList()

    @TypeConverter
    fun fromMemorySourceList(value: List<MemorySourceMessage>?): String =
        if (value == null) "[]" else json.encodeToString(value)

    @TypeConverter
    fun toMemorySourceList(value: String?): List<MemorySourceMessage> =
        value?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(it) } ?: emptyList()
}
