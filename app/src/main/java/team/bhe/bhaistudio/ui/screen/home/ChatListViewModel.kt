package team.bhe.bhaistudio.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import team.bhe.bhaistudio.data.db.entity.ConversationEntity
import team.bhe.bhaistudio.data.db.entity.ConversationType
import team.bhe.bhaistudio.data.repository.ContactRepository
import team.bhe.bhaistudio.data.repository.ConversationRepository

/**
 * 会话列表（首页"聊天"Tab）
 *
 * 对齐桌面端"联系人列表即会话列表"：**每个角色都显示**，有会话的带消息预览，
 * 没有会话（刚创建还没聊过）的也出现——点击进入聊天页时自动建会话。
 */
class ChatListViewModel(
    conversationRepository: ConversationRepository,
    contactRepository: ContactRepository
) : ViewModel() {

    /** 会话 + 联系人合并：所有角色都展示，未聊过的角色生成"虚拟会话"占位 */
    val items: StateFlow<List<ConversationItem>> =
        combine(conversationRepository.observeAll(), contactRepository.observeAll()) { convs, contacts ->
            val convByContact = convs
                .mapNotNull { conv -> conv.pinnedContactId?.let { it to conv } }
                .toMap()
            contacts.map { contact ->
                val conv = convByContact[contact.id]
                ConversationItem(
                    conversation = conv ?: ConversationEntity(
                        id = "single-${contact.id}",
                        title = contact.name,
                        type = ConversationType.SINGLE,
                        memberIds = listOf(contact.id),
                        pinnedContactId = contact.id,
                        lastMessageAt = 0L
                    ),
                    contactName = contact.name,
                    hasChat = conv != null,
                    avatarUri = contact.avatarUri
                )
            }.sortedByDescending { it.conversation.lastMessageAt }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class ConversationItem(
    val conversation: ConversationEntity,
    val contactName: String?,
    /** 是否已有真实会话（否则是未聊过角色的占位） */
    val hasChat: Boolean = true,
    /** 联系人头像 uri（用于列表展示） */
    val avatarUri: String? = null
)
