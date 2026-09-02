package team.bhe.bhaistudio.ui.screen.contact

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.data.db.entity.ContactEntity
import team.bhe.bhaistudio.data.repository.ContactRepository
import team.bhe.bhaistudio.data.repository.ProviderConfigRepository
import team.bhe.bhaistudio.data.repository.SearchConfigRepository
import team.bhe.bhaistudio.data.repository.withAppLanguage

/**
 * 角色（联系人）编辑页
 *
 * 表单字段对应桌面端 collectContactData（index.js:4708），
 * 差异：provider 从"三选一"变成"30 家配置选一"（[ContactEntity.providerConfigId]）。
 * 新建与编辑共用同一套表单；聊天页 ⋮ 也进这里（作为"<Name> - 设置"页）。
 *
 * 表单状态直接放 ViewModel 的可变 State——对单页表单足够简洁，
 * 避免为十几个字段写一遍 data class copy。
 */
class ContactEditViewModel(
    context: Application,
    private val contactRepository: ContactRepository,
    private val providerConfigRepository: ProviderConfigRepository,
    private val searchConfigRepository: SearchConfigRepository
) : AndroidViewModel(context) {

    var contactId: String? = null
    var contactName by mutableStateOf("")

    /** 可选服务商配置列表：已设置密钥的置顶 */
    val providers: StateFlow<List<team.bhe.bhaistudio.data.db.entity.ProviderConfigEntity>> =
        providerConfigRepository.observeAll()
            .map { list -> list.sortedByDescending { it.maskedApiKey.isNotBlank() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 是否已配置自定义搜索 API。
     * 实时观察数据库：从「网络搜索设置」添加后返回，此处自动刷新，无需重启应用。
     */
    val hasSearchConfig: StateFlow<Boolean> =
        searchConfigRepository.observeAll()
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── 表单状态 ──
    var name by mutableStateOf("")
    var avatarUri by mutableStateOf<String?>(null)
    var providerConfigId by mutableStateOf("")
    var model by mutableStateOf("")
    var systemPrompt by mutableStateOf("")
    var roleplay by mutableStateOf(false)
    var disableStreaming by mutableStateOf(false)
    var enableDelay by mutableStateOf(false)
    var webSearch by mutableStateOf(false)
    var useCustomSearch by mutableStateOf(false)
    var deepThink by mutableStateOf(false)
    var advancedEnabled by mutableStateOf(false)
    var topP by mutableFloatStateOf(0.8f)
    var temperature by mutableFloatStateOf(0.7f)
    var thinkingBudget by mutableStateOf(4000)
    var contextWindow by mutableStateOf(0)

    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** 从裁切页返回导致的重组会重跑 initialize，此标志跳过那一次，保住刚裁切好的头像 */
    var skipNextInit: Boolean = false

    /**
     * 由 Screen 传入 contactId（Navigation3 不自动填充 SavedStateHandle）。
     *
     * 每次进入都要重新执行：ViewModel 在导航间会被复用，
     * 如果用一次性标志位阻断，先新建再编辑就会沿用 null 的 contactId，
     * 导致保存时"又创建了一个新角色"而不是更新原来的。
     *
     * @param id null = 新建，此时清空表单避免残留上一个角色的参数
     * @param importJson 扫码得到的角色卡片 JSON，非空时预填表单（仅新建场景生效）
     */
    fun initialize(id: String?, importJson: String? = null) {
        contactId = id
        if (id == null) {
            resetForm()
            if (!importJson.isNullOrBlank()) importFromCard(importJson)
            return
        }
        viewModelScope.launch {
            val contact = contactRepository.getById(id) ?: return@launch
            contactName = contact.name
            name = contact.name
            avatarUri = contact.avatarUri
            providerConfigId = contact.providerConfigId
            model = contact.model
            systemPrompt = contact.systemPrompt
            roleplay = contact.roleplay
            disableStreaming = contact.disableStreaming
            enableDelay = contact.enableDelay
            webSearch = contact.webSearch
            useCustomSearch = contact.useCustomSearch
            deepThink = contact.deepThink
            topP = contact.topP
            temperature = contact.temperature
            thinkingBudget = contact.thinkingBudget
            contextWindow = contact.contextWindow
        }
    }

    /**
     * 用扫码得到的角色卡片预填表单。
     *
     * 字段缺失时保持默认值，模型名 / 服务商需要用户自己选——
     * 卡片里带的模型对方未必有对应服务商。
     */
    private fun importFromCard(json: String) {
        val card = runCatching { JSONObject(json) }.getOrNull() ?: return
        name = card.optString("name").orEmpty()
        systemPrompt = card.optString("systemPrompt").orEmpty()
        model = card.optString("model").orEmpty()
        roleplay = card.optBoolean("roleplay", false)
        disableStreaming = card.optBoolean("disableStreaming", false)
        enableDelay = card.optBoolean("enableDelay", false)
        webSearch = card.optBoolean("webSearch", false)
        deepThink = card.optBoolean("deepThink", false)
        temperature = card.optDouble("temperature", 0.7).toFloat()
        topP = card.optDouble("topP", 0.8).toFloat()
        thinkingBudget = card.optInt("thinkingBudget", 4000)
        if (card.has("temperature") || card.has("topP") || card.has("thinkingBudget")) {
            advancedEnabled = true
        }
    }

    /** 清空表单（新建角色 / ViewModel 复用时避免串数据） */
    private fun resetForm() {
        contactName = ""
        name = ""
        avatarUri = null
        providerConfigId = ""
        model = ""
        systemPrompt = ""
        roleplay = false
        disableStreaming = false
        enableDelay = false
        webSearch = false
        useCustomSearch = false
        deepThink = false
        advancedEnabled = false
        topP = 0.8f
        temperature = 0.7f
        thinkingBudget = 4000
        contextWindow = 0
    }

    /** 当前服务商的可用模型 */
    fun modelsOf(providerId: String): List<String> =
        providers.value.firstOrNull { it.id == providerId }?.models ?: emptyList()

    fun save(onDone: () -> Unit) {
        if (name.isBlank()) {
            error = getApplication<Application>().withAppLanguage().getString(R.string.contact_error_name_required)
            return
        }
        if (providerConfigId.isBlank()) {
            error = getApplication<Application>().withAppLanguage().getString(R.string.contact_error_provider_required)
            return
        }
        if (model.isBlank()) {
            error = getApplication<Application>().withAppLanguage().getString(R.string.contact_error_model_required)
            return
        }

        viewModelScope.launch {
            saving = true
            val existing = contactId?.let { contactRepository.getById(it) }
            val entity = ContactEntity(
                id = existing?.id ?: System.currentTimeMillis().toString(),
                providerConfigId = providerConfigId,
                model = model,
                name = name,
                systemPrompt = systemPrompt,
                avatarUri = avatarUri,
                roleplay = roleplay,
                disableStreaming = disableStreaming,
                enableDelay = enableDelay,
                webSearch = webSearch,
                useCustomSearch = useCustomSearch,
                deepThink = deepThink,
                topP = topP,
                temperature = temperature,
                thinkingBudget = thinkingBudget,
                contextWindow = contextWindow,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                // 编辑保存不能丢运行时状态：置顶/免打扰/活跃时间不在表单里，
                // 必须从旧实体透传，否则每次编辑保存都会把它们重置
                lastActiveAt = existing?.lastActiveAt,
                isPinned = existing?.isPinned ?: false,
                isMuted = existing?.isMuted ?: false
            )
            if (existing == null) {
                contactRepository.create(entity)
            } else {
                contactRepository.update(entity)
            }
            saving = false
            onDone()
        }
    }
}
