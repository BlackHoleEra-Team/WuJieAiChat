package team.bhe.bhaistudio.ui.screen.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import team.bhe.bhaistudio.data.db.entity.MemoryEntity
import team.bhe.bhaistudio.data.repository.MemoryRepository

/**
 * 记忆管理页
 *
 * 记忆分短期（[MemoryEntity.memoryType] SHORT_TERM）与长期（LONG_TERM）两层，
 * 由页面标签页切换查看。生成方式：手动（聊天页「存为记忆」）/ 自动（save_memory 工具）。
 */
class MemoryViewModel(
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private var contactId = ""
    private var initialized = false

    private val _memories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val memories: StateFlow<List<MemoryEntity>> = _memories.asStateFlow()

    /** 由 Screen 传入 contactId（Navigation3 不自动填充 SavedStateHandle） */
    fun initialize(contactId: String) {
        if (initialized) return
        initialized = true
        this.contactId = contactId
        viewModelScope.launch {
            memoryRepository.observeByContact(contactId).collect { _memories.value = it }
        }
    }

    /** 删除单条：进拾忆区（可反悔恢复） */
    fun delete(id: Long) {
        viewModelScope.launch { memoryRepository.recycle(id) }
    }

    /** 清空该角色全部记忆：全部进拾忆区（可反悔恢复） */
    fun clearAll() {
        viewModelScope.launch { memoryRepository.recycleContact(contactId) }
    }
}
