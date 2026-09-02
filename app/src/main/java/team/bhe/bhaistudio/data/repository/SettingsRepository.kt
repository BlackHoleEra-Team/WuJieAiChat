package team.bhe.bhaistudio.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 应用设置
 *
 * 对应桌面端 localStorage 的 `chatSettings` 及若干独立键。
 * Android 版改用 DataStore——类型安全、支持 Flow，避免桌面端那种
 * "到处 localStorage.getItem 后再 JSON.parse" 的散落写法。
 *
 * 分段回复的开关已移到角色编辑页（角色扮演/关闭流式），全局不再控制。
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>
) {

    // ── 历史记忆 ──
    val enableHistoryMemory: Flow<Boolean> =
        dataStore.data.map { it[Keys.ENABLE_HISTORY_MEMORY] ?: false }

    /** 自动记忆：主代理通过 save_memory 工具自主决定存什么，静默执行 */
    val enableAutoMemory: Flow<Boolean> =
        dataStore.data.map { it[Keys.ENABLE_AUTO_MEMORY] ?: true }

    /** 参与上下文的历史轮数，桌面端默认 10 */
    val historyMemoryRounds: Flow<Int> =
        dataStore.data.map { it[Keys.HISTORY_MEMORY_ROUNDS] ?: 10 }

    // ── 称呼 ──
    val enableAiUserName: Flow<Boolean> =
        dataStore.data.map { it[Keys.ENABLE_AI_USER_NAME] ?: false }

    val aiUserName: Flow<String> =
        dataStore.data.map { it[Keys.AI_USER_NAME] ?: "" }

    // ── 外观 ──
    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { ThemeMode.from(it[Keys.THEME_MODE]) }

    val followSystemTheme: Flow<Boolean> =
        dataStore.data.map { it[Keys.FOLLOW_SYSTEM_THEME] ?: false }

    val backgroundUri: Flow<String> =
        dataStore.data.map { it[Keys.BACKGROUND_URI] ?: "" }

    val backgroundOpacity: Flow<Float> =
        dataStore.data.map { it[Keys.BACKGROUND_OPACITY] ?: 0.8f }

    /** 是否启用动态取色（Material You）。桌面端无对应概念 */
    val dynamicColor: Flow<Boolean> =
        dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    // ── 语言 ──
    /** 应用语言：跟随系统 / 中文 / 英文 */
    val appLanguage: Flow<AppLanguage> =
        dataStore.data.map { AppLanguage.from(it[Keys.APP_LANGUAGE]) }

    // ── 拾忆区 ──
    /** 拾忆记忆自动清空时间（天）。0 = 永不自动清空，只手动清 */
    val recycleRetentionDays: Flow<Int> =
        dataStore.data.map { it[Keys.RECYCLE_RETENTION_DAYS] ?: 30 }

    // ── Token 统计 ──
    /** 累计消耗的 token（估算），用于聊天页底部"总消耗"统计 */
    val totalTokens: Flow<Long> =
        dataStore.data.map { it[Keys.TOTAL_TOKENS] ?: 0L }

    // ── 写入 ──
    suspend fun setHistoryMemory(enabled: Boolean, rounds: Int) =
        dataStore.edit {
            it[Keys.ENABLE_HISTORY_MEMORY] = enabled
            it[Keys.HISTORY_MEMORY_ROUNDS] = rounds
        }

    suspend fun setAutoMemory(enabled: Boolean) =
        dataStore.edit { it[Keys.ENABLE_AUTO_MEMORY] = enabled }

    suspend fun setAiUserName(enabled: Boolean, name: String) =
        dataStore.edit {
            it[Keys.ENABLE_AI_USER_NAME] = enabled
            it[Keys.AI_USER_NAME] = name
        }

    suspend fun setThemeMode(mode: ThemeMode) =
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setFollowSystemTheme(follow: Boolean) =
        dataStore.edit { it[Keys.FOLLOW_SYSTEM_THEME] = follow }

    suspend fun setBackground(uri: String, opacity: Float) =
        dataStore.edit {
            it[Keys.BACKGROUND_URI] = uri
            it[Keys.BACKGROUND_OPACITY] = opacity
        }

    suspend fun setDynamicColor(enabled: Boolean) =
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setAppLanguage(language: AppLanguage) =
        dataStore.edit { it[Keys.APP_LANGUAGE] = language.name }

    /** 拾忆记忆自动清空时间（天）；0 = 永不自动清空 */
    suspend fun setRecycleRetentionDays(days: Int) =
        dataStore.edit { it[Keys.RECYCLE_RETENTION_DAYS] = days.coerceIn(0, 3650) }

    /** 累计 token（请求 + 响应估算） */
    suspend fun addTokens(delta: Long) {
        if (delta <= 0) return
        dataStore.edit { prefs ->
            val current = prefs[Keys.TOTAL_TOKENS] ?: 0L
            prefs[Keys.TOTAL_TOKENS] = current + delta
        }
    }

    suspend fun resetTokens() =
        dataStore.edit { it.remove(Keys.TOTAL_TOKENS) }

    private object Keys {
        val ENABLE_HISTORY_MEMORY = booleanPreferencesKey("enable_history_memory")
        val HISTORY_MEMORY_ROUNDS = intPreferencesKey("history_memory_rounds")
        val ENABLE_AUTO_MEMORY = booleanPreferencesKey("enable_auto_memory")
        val ENABLE_AI_USER_NAME = booleanPreferencesKey("enable_ai_user_name")
        val AI_USER_NAME = stringPreferencesKey("ai_user_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FOLLOW_SYSTEM_THEME = booleanPreferencesKey("follow_system_theme")
        val BACKGROUND_URI = stringPreferencesKey("background_uri")
        val BACKGROUND_OPACITY = floatPreferencesKey("background_opacity")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val RECYCLE_RETENTION_DAYS = intPreferencesKey("recycle_retention_days")
        val TOTAL_TOKENS = longPreferencesKey("total_tokens")
    }
}

/** 应用语言：跟随系统 / 中文 / 英文 / 日语 / 繁体中文（香港）/ 繁体中文（台湾） */
enum class AppLanguage {
    SYSTEM,
    ZH,
    EN,
    JA,
    ZH_HK,
    ZH_TW;

    companion object {
        fun from(name: String?): AppLanguage =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    companion object {
        fun from(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
