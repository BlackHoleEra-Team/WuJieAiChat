package team.bhe.bhaistudio.ui.screen.recycled

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import team.bhe.bhaistudio.data.db.entity.MemoryEntity
import team.bhe.bhaistudio.data.repository.ContactRepository
import team.bhe.bhaistudio.data.repository.MemoryRepository
import team.bhe.bhaistudio.data.repository.SettingsRepository

/**
 * 拾忆区（被遗忘/删除的记忆）ViewModel。
 *
 * 入口在「我的」页。列表跨角色展示，附角色名；
 * 支持找回（恢复并重新计时）、立即清空、设置自动清空时间。
 */
class RecycledViewModel(
    context: Application,
    private val memoryRepository: MemoryRepository,
    private val settingsRepository: SettingsRepository,
    private val contactRepository: ContactRepository
) : AndroidViewModel(context) {

    data class Item(val memory: MemoryEntity, val contactName: String)

    /** 拾忆记忆列表（带角色名），最新进拾忆在前 */
    val items: StateFlow<List<Item>> = combine(
        memoryRepository.observeAllRecycled(),
        contactRepository.observeAll()
    ) { memories, contacts ->
        val names = contacts.associate { it.id to it.name }
        memories.map { Item(it, names[it.contactId] ?: it.contactId) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 自动清空时间（天），0 = 从不 */
    val retentionDays: StateFlow<Int> = settingsRepository.recycleRetentionDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    /** 找回：恢复回 ACTIVE 并重新计时 */
    fun restore(id: Long) {
        viewModelScope.launch { memoryRepository.restore(id) }
    }

    /** 设置自动清空时间（天），立即同步到内存仓库 */
    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            settingsRepository.setRecycleRetentionDays(days)
            memoryRepository.recycleRetentionDays = days
        }
    }

    /** 立即清空拾忆区 */
    fun clearAll() {
        viewModelScope.launch { memoryRepository.emptyRecycled() }
    }
}
