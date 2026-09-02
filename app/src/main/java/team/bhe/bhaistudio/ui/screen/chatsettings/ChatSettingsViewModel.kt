package team.bhe.bhaistudio.ui.screen.chatsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import team.bhe.bhaistudio.ai.model.ContextUsage
import team.bhe.bhaistudio.data.db.entity.ContactEntity
import team.bhe.bhaistudio.data.repository.ChatRepository
import team.bhe.bhaistudio.data.repository.ContactRepository
import team.bhe.bhaistudio.data.repository.ConversationRepository
import team.bhe.bhaistudio.data.repository.SettingsRepository

/**
 * 对话设置页（聊天页 ⋮ 进入）——<Name> - 设置
 *
 * 与「角色编辑」是两码事：这里是**对话**的设置，
 * 目前承载上下文窗口占用、压缩入口与总 token 统计。
 */
class ChatSettingsViewModel(
    private val chatRepository: ChatRepository,
    private val contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private var initialized = false
    private var conversationId = ""

    private val _contact = MutableStateFlow<ContactEntity?>(null)
    val contact: StateFlow<ContactEntity?> = _contact.asStateFlow()

    private val _contextUsage = MutableStateFlow(ContextUsage(0, 0))
    val contextUsage: StateFlow<ContextUsage> = _contextUsage.asStateFlow()

    private val _compressing = MutableStateFlow(false)
    val compressing: StateFlow<Boolean> = _compressing.asStateFlow()

    /** 最近一次压缩结果：true=成功 false=失败 null=还没压缩过 */
    private val _compressResult = MutableStateFlow<Boolean?>(null)
    val compressResult: StateFlow<Boolean?> = _compressResult.asStateFlow()

    /** 累计消耗 token（估算） */
    val totalTokens: StateFlow<Long> = settingsRepository.totalTokens
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 由 Screen 传入 contactId（Navigation3 不自动填充 SavedStateHandle） */
    fun initialize(contactId: String) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val contact = contactRepository.getById(contactId) ?: return@launch
            _contact.value = contact
            conversationId = conversationRepository.getOrCreateSingle(contactId, contact.name).id
            refresh()
        }
    }

    /** 重新估算上下文占用（本地估算，不调 API） */
    fun refresh() {
        viewModelScope.launch {
            val contact = _contact.value ?: return@launch
            if (conversationId.isBlank()) return@launch
            _contextUsage.value = chatRepository.estimateContextUsage(contact, conversationId)
        }
    }

    /** 压缩当前会话上下文 */
    fun compress() {
        viewModelScope.launch {
            val contact = _contact.value ?: return@launch
            if (conversationId.isBlank()) return@launch
            _compressing.value = true
            val result = chatRepository.compressContext(contact, conversationId)
            _compressing.value = false
            _compressResult.value = result.isSuccess
            refresh()
        }
    }
}
