package team.bhe.bhaistudio.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import team.bhe.bhaistudio.data.repository.AppLanguage
import team.bhe.bhaistudio.data.repository.AppLanguageState
import team.bhe.bhaistudio.data.repository.SettingsRepository
import team.bhe.bhaistudio.data.repository.ThemeMode

/**
 * 通用设置页
 *
 * 对应桌面端 localStorage 里的 chatSettings 与外观设置
 *（GeneralSettingsActivity 的等价物，但字段全部来自桌面端新版）。
 */
class SettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {

    val enableHistoryMemory: StateFlow<Boolean> = repository.enableHistoryMemory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val historyMemoryRounds: StateFlow<Int> = repository.historyMemoryRounds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10)

    /** 自动记忆：主代理自主决定存什么（save_memory 工具） */
    val enableAutoMemory: StateFlow<Boolean> = repository.enableAutoMemory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val enableAiUserName: StateFlow<Boolean> = repository.enableAiUserName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val aiUserName: StateFlow<String> = repository.aiUserName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val dynamicColor: StateFlow<Boolean> = repository.dynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 主题模式：跟随系统 / 浅色 / 深色 */
    val themeMode: StateFlow<ThemeMode> = repository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    /** 应用语言：跟随系统 / 中文 / 英文 */
    val appLanguage: StateFlow<AppLanguage> = repository.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.SYSTEM)

    fun setHistoryMemory(enabled: Boolean, rounds: Int) = viewModelScope.launch {
        repository.setHistoryMemory(enabled, rounds)
    }

    fun setAutoMemory(enabled: Boolean) = viewModelScope.launch {
        repository.setAutoMemory(enabled)
    }

    fun setAiUserName(enabled: Boolean, name: String) = viewModelScope.launch {
        repository.setAiUserName(enabled, name)
    }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch {
        repository.setDynamicColor(enabled)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        repository.setThemeMode(mode)
    }

    fun setLanguage(language: AppLanguage) {
        // 先同步更新内存快照：UI 随即 recreate，attachBaseContext 立即拿到新语言
        AppLanguageState.language = language
        viewModelScope.launch { repository.setAppLanguage(language) }
    }
}
