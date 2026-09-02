package team.bhe.bhaistudio.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 用户资料（"我的"页顶部卡片）
 *
 * 对应老代码 SharedPreferences("user_profile") 的 nickname / avatar，
 * Android 版统一放进 DataStore，类型安全且支持 Flow。
 */
class UserProfileRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** 昵称，空串表示未设置（UI 用默认值兜底） */
    val nickname: Flow<String> =
        dataStore.data.map { it[Keys.NICKNAME] ?: "" }

    /** 自定义头像 content uri，空串表示用首字符占位 */
    val avatarUri: Flow<String> =
        dataStore.data.map { it[Keys.AVATAR_URI] ?: "" }

    suspend fun setNickname(value: String) =
        dataStore.edit { it[Keys.NICKNAME] = value }

    suspend fun setAvatarUri(value: String) =
        dataStore.edit { it[Keys.AVATAR_URI] = value }

    private object Keys {
        val NICKNAME = stringPreferencesKey("user_nickname")
        val AVATAR_URI = stringPreferencesKey("user_avatar_uri")
    }
}
