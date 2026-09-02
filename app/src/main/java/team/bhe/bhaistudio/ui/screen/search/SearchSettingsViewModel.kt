package team.bhe.bhaistudio.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import team.bhe.bhaistudio.data.db.entity.SearchConfigEntity
import team.bhe.bhaistudio.data.db.entity.SearchProvider
import team.bhe.bhaistudio.data.repository.SearchConfigRepository

/**
 * 网络搜索设置页——配置自定义联网搜索 API
 *
 * 支持 Firecrawl / Tavily Search / Bing Web Search API / serper.dev。
 * 允许多条配置，聊天时**按添加顺序取第一条**使用。
 */
class SearchSettingsViewModel(
    private val repository: SearchConfigRepository
) : ViewModel() {

    val configs: StateFlow<List<SearchConfigEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(provider: SearchProvider, key: String) {
        viewModelScope.launch { repository.add(provider, key) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
