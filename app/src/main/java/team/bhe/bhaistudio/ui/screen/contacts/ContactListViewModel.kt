package team.bhe.bhaistudio.ui.screen.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import team.bhe.bhaistudio.data.db.entity.ContactEntity
import team.bhe.bhaistudio.data.repository.ContactRepository
import team.bhe.bhaistudio.data.repository.ProviderConfigRepository

/**
 * 联系人（AI 角色）列表
 *
 * 桌面端 loadContacts（index.js:4961 renderContacts）的等价实现。
 * 每个角色卡片显示：名字 + 头像 + 服务商名 + 模型名。
 */
class ContactListViewModel(
    private val contactRepository: ContactRepository,
    private val providerConfigRepository: ProviderConfigRepository
) : ViewModel() {

    /** 服务商 id → 显示名，供卡片副标题 */
    private val providerNames: StateFlow<Map<String, String>> =
        providerConfigRepository.observeAll()
            .map { providers -> providers.associate { it.id to it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val items: StateFlow<List<ContactItem>> =
        combine(contactRepository.observeAll(), providerNames) { contacts, names ->
            contacts.map { contact ->
                ContactItem(
                    contact = contact,
                    providerName = names[contact.providerConfigId]
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(contactId: String) {
        viewModelScope.launch { contactRepository.delete(contactId) }
    }
}

data class ContactItem(
    val contact: ContactEntity,
    val providerName: String?
)
