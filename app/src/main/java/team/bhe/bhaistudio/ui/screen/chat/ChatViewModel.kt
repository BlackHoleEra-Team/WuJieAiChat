package team.bhe.bhaistudio.ui.screen.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.ai.TokenEstimator
import team.bhe.bhaistudio.ai.model.ChatMessageDto
import team.bhe.bhaistudio.ai.model.ChatRequest
import team.bhe.bhaistudio.ai.model.ChatToolCallRef
import team.bhe.bhaistudio.ai.model.ChatResult
import team.bhe.bhaistudio.ai.model.ContextUsage
import team.bhe.bhaistudio.ai.model.StreamChunk
import team.bhe.bhaistudio.ai.model.RecallArgs
import team.bhe.bhaistudio.ai.model.RescoreArgs
import team.bhe.bhaistudio.ai.model.WebSearchArgs
import team.bhe.bhaistudio.data.db.entity.ContactEntity
import team.bhe.bhaistudio.data.db.entity.MessageEntity
import team.bhe.bhaistudio.data.repository.ChatRepository
import team.bhe.bhaistudio.data.repository.ContactRepository
import team.bhe.bhaistudio.data.repository.ConversationRepository
import team.bhe.bhaistudio.data.repository.ProviderConfigRepository
import team.bhe.bhaistudio.data.repository.SettingsRepository
import team.bhe.bhaistudio.data.repository.UserProfileRepository
import team.bhe.bhaistudio.data.repository.withAppLanguage

/**
 * 聊天页 ViewModel
 *
 * 编排一整轮对话：落库用户消息 → 构造请求 →（流式 or 分段）→ 落库 AI 消息。
 *
 * ── 两种回复模式的差异 ────────────────────────────────────
 * **流式模式**（默认）：token 边到边显示，先显示"正在输入"，内容实时增长。
 * **分段模式**（角色扮演且未开深度思考）：先等完整回复，
 *   再由 [SegmentedReply] 切成多段，每条作为独立气泡依次出现。
 *
 * 桌面端把这套逻辑散落在 `sendMessage` / `handleAIResponseDisplay` 里，
 * 且两种模式的回调语义不一致（一个传增量、一个传累积）。
 * 这里统一为：流式期间用 [ChatUiState.streamingText] 暂存，
 * 无论哪种模式，最终都落库成 [MessageEntity]，UI 只认数据库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    context: Application,
    private val chatRepository: ChatRepository,
    private val contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val providerConfigRepository: ProviderConfigRepository,
    private val userProfileRepository: UserProfileRepository
) : AndroidViewModel(context) {

    private val _contact = MutableStateFlow<ContactEntity?>(null)
    val contact: StateFlow<ContactEntity?> = _contact.asStateFlow()

    /** 用户自己的头像 / 昵称，用于用户气泡旁的头像 */
    val userAvatarUri: StateFlow<String> = userProfileRepository.avatarUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val userNickname: StateFlow<String> = userProfileRepository.nickname
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _conversationId = MutableStateFlow("")

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 本次对话是否已启动过记忆保存任务（避免多轮流式中重复触发/重复提示） */
    private var memoryTaskStarted = false

    /** 一次性提示事件（存记忆结果等），UI 以 Snackbar 展示 */
    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** 聊天前拦截事件：服务商未设密钥 / 不可用，UI 弹窗指引去服务商管理 */
    private val _apiBlock = MutableStateFlow<ApiBlockInfo?>(null)
    val apiBlock: StateFlow<ApiBlockInfo?> = _apiBlock.asStateFlow()

    fun dismissApiBlock() {
        _apiBlock.value = null
    }

    // ── 上下文窗口 / 压缩 ──
    private val _contextUsage = MutableStateFlow(ContextUsage(0, 0))
    val contextUsage: StateFlow<ContextUsage> = _contextUsage.asStateFlow()

    private val _compressing = MutableStateFlow(false)
    val compressing: StateFlow<Boolean> = _compressing.asStateFlow()

    /** 最近一次压缩结果：true=成功 false=失败 null=还没压缩过 */
    private val _compressResult = MutableStateFlow<Boolean?>(null)
    val compressResult: StateFlow<Boolean?> = _compressResult.asStateFlow()

    /** 上下文耗尽（发送被拦截时置 true，UI 弹窗提示去压缩） */
    private val _exhausted = MutableStateFlow(false)
    val exhausted: StateFlow<Boolean> = _exhausted.asStateFlow()

    fun consumeExhausted() {
        _exhausted.value = false
    }

    /** 累计消耗 token（估算），用于底部"总消耗"统计 */
    val totalTokens: StateFlow<Long> = settingsRepository.totalTokens
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * 消息分页流。
     *
     * 会话 id 确定后才开始查询，所以先 flatMapLatest 再建 Pager。
     * `cachedIn(viewModelScope)` 保证配置变更后不重新加载。
     */
    val messages: Flow<PagingData<MessageEntity>> = _conversationId
        .flatMapLatest { id ->
            if (id.isBlank()) {
                flowOf(PagingData.empty())
            } else {
                Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
                    chatRepository.pagingMessages(id)
                }.flow
            }
        }
        .cachedIn(viewModelScope)

    private val json = Json { ignoreUnknownKeys = true }

    private var initialized = false

    /**
     * 由 Screen 在组合时调用，传入当前 contactId。
     * Navigation3 不会自动把 destination 参数填进 SavedStateHandle，必须显式传入。
     */
    fun initialize(contactId: String) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            // 观察而非一次性查询：在编辑页保存角色后，参数（人设/模型/开关等）
            // 会自动刷新到这里，无需退出重进
            contactRepository.observeById(contactId).collect { entity ->
                if (entity == null) return@collect
                _contact.value = entity
                if (_conversationId.value.isBlank()) {
                    _conversationId.value =
                        conversationRepository.getOrCreateSingle(contactId, entity.name).id
                }
                refreshContextUsage()
            }
        }
    }

    /**
     * 重新估算上下文占用（每次落库/压缩后调用）。
     * 本地估算，不调 API。
     */
    fun refreshContextUsage() {
        viewModelScope.launch {
            val contact = _contact.value ?: return@launch
            val conversationId = _conversationId.value
            if (conversationId.isBlank()) return@launch
            _contextUsage.value = chatRepository.estimateContextUsage(contact, conversationId)
        }
    }

    /**
     * 压缩当前会话上下文：模型总结全部历史 → 存摘要 → 清空历史（保留最近 2 轮）。
     * 压缩期间 UI 显示进度；结果通过 [events] 反馈。
     */
    fun compress() {
        viewModelScope.launch {
            val contact = _contact.value ?: return@launch
            val conversationId = _conversationId.value
            if (conversationId.isBlank()) return@launch
            _compressing.value = true
            val result = chatRepository.compressContext(contact, conversationId)
            _compressing.value = false
            _compressResult.value = result.isSuccess
            refreshContextUsage()
        }
    }

    // ─────────────────────────────────────────────────────
    // 发送
    // ─────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        val input = text.trim()
        if (input.isBlank()) return

        val conversationId = _conversationId.value
        if (conversationId.isBlank()) return

        // 上下文耗尽保护：剩余不足以发起新对话时，弹窗提示去压缩
        val usage = _contextUsage.value
        if (usage.total > 0 && usage.ratio >= EXHAUSTED_RATIO) {
            _exhausted.value = true
            return
        }

        viewModelScope.launch {
            val contact = _contact.value ?: return@launch
            // 新一轮对话重置记忆保存任务标记（一次对话只触发一次）
            memoryTaskStarted = false

            // 提前拦截：服务商未设置密钥 / 上次测试不可用 → 弹窗指引前往服务商管理
            val provider = providerConfigRepository.getById(contact.providerConfigId)
            if (provider == null || provider.encryptedApiKey.isBlank()) {
                _apiBlock.value = ApiBlockInfo(
                    provider?.name ?: getApplication<Application>().withAppLanguage().getString(R.string.chat_unknown_provider),
                    ApiBlockReason.NO_KEY
                )
                _uiState.value = _uiState.value.copy(isSending = false)
                return@launch
            }
            if (provider.isAvailable == false) {
                _apiBlock.value = ApiBlockInfo(provider.name, ApiBlockReason.UNAVAILABLE)
                _uiState.value = _uiState.value.copy(isSending = false)
                return@launch
            }

            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            chatRepository.saveUserMessage(conversationId, input)

            val useSegmented = chatRepository.shouldUseSegmentedReply(contact)

            val request = chatRepository.buildChatRequest(
                contact = contact,
                conversationId = conversationId,
                historyRounds = settingsRepository.historyMemoryRounds.first(),
                includeMemory = settingsRepository.enableHistoryMemory.first()
            )

            request.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = error.message
                )
                return@launch
            }

            val chatRequest = request.getOrNull() ?: return@launch

            when {
                // 角色扮演：分段播放（内部本就是非流式）
                useSegmented -> sendSegmented(contact, chatRequest, conversationId)
                // 「关闭流式传输」独立开关：一次性完整回复
                contact.disableStreaming -> sendDirect(contact, chatRequest, conversationId)
                // 默认流式
                else -> sendStreaming(contact, chatRequest, conversationId)
            }
        }
    }

    /** 估算一次请求的输入 token（system + 历史） */
    private fun estimateRequestTokens(request: ChatRequest): Long {
        val system = request.history.firstOrNull { it.role == "system" }?.content.orEmpty()
        val messages = request.history
            .filter { it.role != "system" }
            .map { it.role to it.content }
        return TokenEstimator.total(system, messages).toLong()
    }

    /**
     * 累计本轮消耗的 token：优先用服务商返回的真实 usage，
     * 拿不到时退回本地估算（输入=请求估算，输出=正文估算）。
     */
    private suspend fun recordTokens(
        inputTokens: Int,
        outputTokens: Int,
        request: ChatRequest,
        content: String,
        thinking: String
    ) {
        val input = if (inputTokens > 0) inputTokens.toLong() else estimateRequestTokens(request)
        val output = if (outputTokens > 0) outputTokens.toLong() else TokenEstimator.text(content + thinking).toLong()
        settingsRepository.addTokens(input + output)
        refreshContextUsage()
    }

    /**
     * 分段模式：先取完整回复，再逐条播放。
     *
     * 每段落库即出现在列表上（Room 的 Flow 会自动刷新），
     * 段与段之间由调度器 delay，并显示"正在输入"。
     */
    private suspend fun sendSegmented(
        contact: ContactEntity,
        request: team.bhe.bhaistudio.ai.model.ChatRequest,
        conversationId: String
    ) {
        _uiState.value = _uiState.value.copy(isTyping = true)

        val endpoint = chatRepository.buildEndpoint(contact).getOrElse { error ->
            _uiState.value = _uiState.value.copy(
                isSending = false,
                isTyping = false,
                error = error.message
            )
            return
        }

        val result = runCatching { onceReplyWithTools(contact, endpoint, request, conversationId) }
            .getOrElse { error ->
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    isTyping = false,
                    error = error.message
                )
                return
            }
        // 搜索失败等场景已发提示，直接收尾
        if (result == null) {
            _uiState.value = _uiState.value.copy(isSending = false, isTyping = false)
            return
        }

        chatRepository.saveSegmentedReply(
            conversationId = conversationId,
            fullText = result.content
        )

        recordTokens(result.inputTokens, result.outputTokens, request, result.content, result.thinkingContent.orEmpty())
        _uiState.value = _uiState.value.copy(isSending = false, isTyping = false)
    }

    /**
     * 非流式：一次性取完整回复直接落库。
     * 对应「关闭流式传输」独立开关（未开角色扮演时的非流式回复）。
     */
    private suspend fun sendDirect(
        contact: ContactEntity,
        request: ChatRequest,
        conversationId: String
    ) {
        _uiState.value = _uiState.value.copy(isTyping = true)

        val endpoint = chatRepository.buildEndpoint(contact).getOrElse { error ->
            _uiState.value = _uiState.value.copy(
                isSending = false,
                isTyping = false,
                error = error.message
            )
            return
        }

        val result = runCatching { onceReplyWithTools(contact, endpoint, request, conversationId) }
            .getOrElse { error ->
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    isTyping = false,
                    error = error.message
                )
                return
            }
        // 搜索失败等场景已发提示，直接收尾
        if (result == null) {
            _uiState.value = _uiState.value.copy(isSending = false, isTyping = false)
            return
        }

        chatRepository.saveAiMessage(
            conversationId = conversationId,
            content = result.content,
            thinkingContent = result.thinkingContent?.takeIf { it.isNotBlank() },
            replyGroupId = ChatRepository.newReplyGroupId()
        )

        recordTokens(result.inputTokens, result.outputTokens, request, result.content, result.thinkingContent.orEmpty())
        _uiState.value = _uiState.value.copy(isSending = false, isTyping = false)
    }

    /**
     * 非流式一次取完整回复。若模型只返回工具调用而没有正文
     * （自动记忆 save_memory / 联网搜索 web_search 优先触发），
     * 在客户端执行工具后追加一轮请求，最多两轮，直到拿到正文。
     *
     * 与流式的「工具后无正文自动续写」等价——此前非流式没有这套，
     * 导致模型先调工具时"关闭流式/分段"看起来没回复。
     *
     * @return 最终结果；null 表示工具执行失败已提示，调用方应直接收尾
     */
    private suspend fun onceReplyWithTools(
        contact: ContactEntity,
        endpoint: team.bhe.bhaistudio.ai.ProviderEndpoint,
        request: ChatRequest,
        conversationId: String
    ): ChatResult? {
        var currentRequest = request
        val thinkingBuf = StringBuilder()
        var inputTokens = 0
        var outputTokens = 0
        for (pass in 0 until 2) {
            val result = chatRepository.onceReply(endpoint, currentRequest)
            inputTokens = result.inputTokens
            outputTokens = result.outputTokens
            result.thinkingContent?.let { thinkingBuf.append(it) }

            // 拿到正文：收尾返回（思考取全部轮次拼接，至少保留最终轮）
            if (result.content.isNotBlank()) {
                return ChatResult(
                    content = result.content,
                    thinkingContent = thinkingBuf.toString().takeIf { it.isNotBlank() }
                        ?: result.thinkingContent,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens
                )
            }
            // 没有正文也没有工具：无话可说，把已收集的思考返回
            if (result.toolCalls.isEmpty()) {
                return ChatResult(
                    content = result.content,
                    thinkingContent = thinkingBuf.toString().takeIf { it.isNotBlank() },
                    inputTokens = inputTokens,
                    outputTokens = outputTokens
                )
            }

            // 有工具调用：执行后注入结果，下一轮让它产出正文
            var handled = false
            for (tool in result.toolCalls) {
                when (tool.name) {
                    "web_search" -> {
                        val query = runCatching {
                            json.decodeFromString<WebSearchArgs>(tool.arguments).query
                        }.getOrNull()
                        if (query.isNullOrBlank()) continue
                        val search = chatRepository.executeWebSearch(query)
                        if (search.isSuccess) {
                            currentRequest = buildSearchFollowupRequest(currentRequest, search.getOrThrow())
                            handled = true
                        } else {
                            // 搜索失败：弹提示指引用户，终止对话
                            _events.emit(
                                getApplication<Application>().withAppLanguage().getString(
                                    R.string.chat_search_failed_format,
                                    search.exceptionOrNull()?.message
                                        ?: getApplication<Application>().withAppLanguage().getString(R.string.common_unknown_error)
                                )
                            )
                            return null
                        }
                    }

                    "save_memory" -> {
                        // 静默保存（自动记忆的 UI 提示条只在流式路径展示）
                        runCatching {
                            chatRepository.handleSaveMemoryTool(contact, conversationId, tool.arguments)
                        }
                        // 告知模型记忆已在后台处理，请继续完成回复
                        currentRequest = buildMemoryFollowupRequest(currentRequest)
                        handled = true
                    }

                    "recall" -> {
                        // 按需回忆：检索记忆正文并注入，让本轮直接产出回复
                        val query = runCatching {
                            json.decodeFromString<RecallArgs>(tool.arguments).query
                        }.getOrNull()
                        if (query.isNullOrBlank()) continue
                        val recalled = chatRepository.recallMemory(contact.id, query)
                        if (recalled.isNotBlank()) {
                            currentRequest = buildRecallFollowupRequest(currentRequest, recalled)
                            handled = true
                        }
                    }

                    "rescore" -> {
                        // 重新评定记忆重要性：命中沉睡记忆会一并唤醒
                        val args = runCatching {
                            json.decodeFromString<RescoreArgs>(tool.arguments)
                        }.getOrNull()
                        if (args == null || args.query.isBlank()) continue
                        chatRepository.rescoreMemory(contact.id, args.query, args.importance)
                        currentRequest = buildRescoreFollowupRequest(currentRequest)
                        handled = true
                    }
                }
            }
            if (!handled) break
        }
        // 两轮后仍无正文（极端情况）：交回已收集的思考，正文为空由落库侧兜底
        return ChatResult(
            content = "",
            thinkingContent = thinkingBuf.toString().takeIf { it.isNotBlank() },
            inputTokens = inputTokens,
            outputTokens = outputTokens
        )
    }

    /**
     * 流式模式：边收边显示，结束（或中断）后一次性落库。
     */
    private suspend fun sendStreaming(
        contact: ContactEntity,
        request: ChatRequest,
        conversationId: String
    ) {
        _uiState.value = _uiState.value.copy(isTyping = true)

        val endpoint = chatRepository.buildEndpoint(contact).getOrElse { error ->
            _uiState.value = _uiState.value.copy(
                isSending = false,
                isTyping = false,
                error = error.message
            )
            return
        }

        var currentRequest = request
        // 至多三轮：第一轮主回复；若主代理调用了工具（recall / web_search / save_memory）且没写正文，
        // 就注入工具结果后续写最终回复。注入文本已要求"直接作答"，但个别模型仍会再次调工具，
        // 因此留少量续写预算兜底，防止"调了工具却没正文、回复凭空消失"。
        var followupBudget = MAX_STREAM_FOLLOWUPS
        // 中断兜底跟踪：是否落库过正文 / 本轮是否调过工具 / 工具续写次数 / 最后一轮思考
        var anyContentSaved = false
        var toolSeen = false
        var followupsDone = 0
        var lastThinking = ""
        // 跨轮继承思考：第一轮工具调用前的思考内容拼到第二轮思考里，避免"前段思考丢失"
        var carryThinking = ""
        Log.i(
            "ChatFlow",
            "sendStreaming start conversationId=$conversationId contactId=${contact.id} historySize=${currentRequest.history.size}"
        )
        for (pass in 0 until MAX_STREAM_PASSES) {
            var content = StringBuilder()
            var thinking = StringBuilder(carryThinking)
            // 工具调用缓冲：index → arguments 增量分片；name / id 在首个分片出现
            val toolCalls = mutableMapOf<Int, MutableList<String>>()
            val toolNames = mutableMapOf<Int, String?>()
            val toolIds = mutableMapOf<Int, String?>()
            val replyGroupId = ChatRepository.newReplyGroupId()
            var pendingSearch: String? = null
            val pendingRecallQueries = mutableListOf<String>()
            var rescoreTriggered = false
            var streamError: String? = null
            var inputTokens = 0
            var outputTokens = 0
            var thinkingStartedAt = 0L
            var firstTextChunk = true

            Log.i(
                "ChatFlow",
                "--- pass=$pass start historySize=${currentRequest.history.size} carryThinking.len=${carryThinking.length}"
            )

            chatRepository.streamReply(endpoint, currentRequest).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Thinking -> {
                        if (thinkingStartedAt == 0L) thinkingStartedAt = System.currentTimeMillis()
                        thinking.append(chunk.delta)
                        _uiState.value = _uiState.value.copy(streamingThinking = thinking.toString())
                    }

                    is StreamChunk.Text -> {
                        // 首个正文分片到达 → 思考阶段结束，记录耗时供"已完成思考 (Xs)"展示
                        if (firstTextChunk) {
                            firstTextChunk = false
                            if (thinkingStartedAt > 0L) {
                                _uiState.value = _uiState.value.copy(
                                    thinkingElapsedSec = (System.currentTimeMillis() - thinkingStartedAt) / 1000f
                                )
                            }
                        }
                        content.append(chunk.delta)
                        _uiState.value = _uiState.value.copy(streamingText = content.toString())
                    }

                    is StreamChunk.ToolCall -> {
                        if (chunk.name != null) toolNames[chunk.index] = chunk.name
                        if (chunk.id != null) toolIds[chunk.index] = chunk.id
                        toolCalls.getOrPut(chunk.index) { mutableListOf() }.add(chunk.argumentsDelta)
                    }

                    is StreamChunk.Done -> {
                        inputTokens = chunk.inputTokens
                        outputTokens = chunk.outputTokens
                        Log.i(
                            "ChatFlow",
                            "pass=$pass Done contentLen=${content.length} thinkingLen=${thinking.length} toolNames=${toolNames.values.toList()}"
                        )
                        // 流结束，落库。空内容不落，避免产生空气泡
                        if (content.isNotBlank()) {
                            chatRepository.saveAiMessage(
                                conversationId = conversationId,
                                content = content.toString(),
                                thinkingContent = thinking.toString().takeIf { it.isNotBlank() },
                                replyGroupId = replyGroupId
                            )
                            anyContentSaved = true
                        }
                        toolSeen = toolNames.values.any { it != null }
                        // 自动记忆：异步并行执行，不阻塞、不影响主回复输出。
                        // 一次对话只触发一次（去重），输入框上方展示"正在保存记忆"的进度与结果
                        if (toolNames.values.contains("save_memory")) {
                            startMemorySave(contact, conversationId, toolCalls, toolNames)
                        }
                        pendingSearch = extractWebSearchQuery(toolCalls, toolNames)
                        pendingRecallQueries += extractRecallQueries(toolCalls, toolNames)
                        rescoreTriggered = toolNames.values.contains("rescore")
                    }

                    is StreamChunk.Error -> {
                        Log.w("ChatFlow", "pass=$pass Error message='${chunk.message}' contentLen=${content.length}")
                        // 中断时也要保存已生成的内容，不浪费 tokens
                        if (content.isNotBlank()) {
                            chatRepository.saveAiMessage(
                                conversationId = conversationId,
                                content = content.toString(),
                                thinkingContent = thinking.toString().takeIf { it.isNotBlank() },
                                replyGroupId = replyGroupId
                            )
                        }
                        streamError = chunk.message
                    }
                }
            }

            recordTokens(inputTokens, outputTokens, currentRequest, content.toString(), thinking.toString())
            lastThinking = thinking.toString()

            if (streamError != null) {
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    isTyping = false,
                    streamingText = "",
                    streamingThinking = "",
                    error = streamError
                )
                return
            }

            // ── 标准 agentic tool loop ──
            // 模型这轮只调工具、没写正文 → 把这一轮组织成协议级标准消息回写历史：
            //   [assistant(tool_calls)] + [tool(tool_result)…]
            // 然后带着完整行动链继续请求。模型能看到"自己刚查过什么、拿到了什么结果"，
            // 不会再"失忆式"地重复调同一个工具，直到它认为信息够了、输出正文收尾。
            val assembledCalls = assembleToolCalls(toolCalls, toolNames, toolIds)
            val needsFollowup = content.isBlank() && followupBudget > 0 && assembledCalls.isNotEmpty()
            Log.i(
                "ChatFlow",
                "post-pass=$pass needsFollowup=$needsFollowup calls=${assembledCalls.map { it.name }} " +
                    "followupBudget=$followupBudget contentLen=${content.length}"
            )
            if (needsFollowup) {
                // 1) assistant 回合：把本轮声明的工具调用写回历史（标准 tool_calls 结构）
                currentRequest = currentRequest.copy(
                    history = currentRequest.history + ChatMessageDto(
                        role = "assistant",
                        content = "", // 工具轮通常无正文（有正文会在上方落库并走正常收尾）
                        toolCalls = assembledCalls
                    )
                )
                // 2) 逐个执行工具，把观察结果以 role=tool 消息写回，交给模型决策下一步
                val results = assembledCalls.map { call ->
                    val out = executeAgentTool(contact, conversationId, call.name, call.arguments)
                    ChatMessageDto(
                        role = "tool",
                        content = out,
                        toolCallId = call.id,
                        toolName = call.name
                    )
                }
                Log.i("ChatFlow", "tool round 执行完成 results=${results.map { it.toolName }}")
                currentRequest = currentRequest.copy(history = currentRequest.history + results)
                followupBudget--
                followupsDone++
                // 保留本轮工具调用前的思考，融入后续思考（不清空 streamingThinking）
                carryThinking = thinking.toString()
                _uiState.value = _uiState.value.copy(streamingText = "")
                Log.i("ChatFlow", "进入下一工具轮 pass=${pass + 1}，剩余预算 followupBudget=$followupBudget")
                continue
            }

            // 预算耗尽或无需续写：若整轮只调工具没产出正文，
            // 把已输出的思考 + 中断提示落库，避免回复凭空消失
            if (!anyContentSaved && (toolSeen || lastThinking.isNotBlank())) {
                saveInterruptedReply(
                    conversationId = conversationId,
                    thinking = lastThinking,
                    roundExhausted = followupsDone >= MAX_STREAM_FOLLOWUPS
                )
            }
            resetStreamingState()
            return
        }
        // 流式轮次用完后兜底：同样处理"只调工具没正文"的场景
        if (!anyContentSaved && (toolSeen || lastThinking.isNotBlank())) {
            saveInterruptedReply(
                conversationId = conversationId,
                thinking = lastThinking,
                roundExhausted = followupsDone >= MAX_STREAM_FOLLOWUPS
            )
        }
        _uiState.value = _uiState.value.copy(isSending = false, isTyping = false)
    }

    /**
     * 从工具调用里提取 web_search 的搜索关键词。
     * arguments 是分片拼接后的 JSON，容错解析。
     */
    private fun extractWebSearchQuery(
        toolCalls: Map<Int, List<String>>,
        toolNames: Map<Int, String?>
    ): String? {
        for ((index, deltas) in toolCalls) {
            if (toolNames[index] == "web_search") {
                val argsJson = deltas.joinToString("")
                val query = runCatching {
                    json.decodeFromString<WebSearchArgs>(argsJson).query
                }.getOrNull()
                if (!query.isNullOrBlank()) return query
            }
        }
        return null
    }

    /** 从工具调用里提取所有 recall 的查询词（可能多个，合并检索） */
    private fun extractRecallQueries(
        toolCalls: Map<Int, List<String>>,
        toolNames: Map<Int, String?>
    ): List<String> {
        val queries = mutableListOf<String>()
        for ((index, deltas) in toolCalls) {
            if (toolNames[index] == "recall") {
                val argsJson = deltas.joinToString("")
                val query = runCatching {
                    json.decodeFromString<RecallArgs>(argsJson).query
                }.getOrNull()
                if (!query.isNullOrBlank()) queries += query
            }
        }
        return queries.distinct()
    }

    /**
     * 标准 agent loop：把本轮的流式工具分片组装成协议无关的调用回合。
     * index → 完整 arguments；name / id 在首个分片出现（缺失时本地生成占位 id）。
     */
    private fun assembleToolCalls(
        toolCalls: Map<Int, List<String>>,
        toolNames: Map<Int, String?>,
        toolIds: Map<Int, String?>
    ): List<ChatToolCallRef> {
        val calls = mutableListOf<ChatToolCallRef>()
        for ((index, deltas) in toolCalls) {
            val name = toolNames[index]
            if (name.isNullOrBlank()) continue
            calls += ChatToolCallRef(
                id = toolIds[index] ?: "call_${System.nanoTime()}_$index",
                name = name,
                arguments = deltas.joinToString("")
            )
        }
        return calls
    }

    /**
     * 标准 agent loop：执行单个工具调用，返回给模型看的观察结果文本。
     * 该文本会以 role=tool 消息写回历史，模型据此决策下一步。
     */
    private suspend fun executeAgentTool(
        contact: ContactEntity,
        conversationId: String,
        name: String,
        argsJson: String
    ): String = when (name) {
        "recall" -> {
            val query = runCatching { json.decodeFromString<RecallArgs>(argsJson).query }.getOrNull()
            if (query.isNullOrBlank()) {
                Log.w("ChatFlow", "recall 参数为空")
                "【工具 recall】查询词为空，未执行。"
            } else {
                Log.i("ChatFlow", "recall tool query='$query'")
                chatRepository.recallMemory(contact.id, query)
                    .ifEmpty { "【工具 recall】按「$query」没有检索到任何记忆内容。" }
            }
        }

        "web_search" -> {
            val query = runCatching { json.decodeFromString<WebSearchArgs>(argsJson).query }.getOrNull()
            if (query.isNullOrBlank()) {
                "【工具 web_search】查询词为空，未执行。"
            } else {
                val search = chatRepository.executeWebSearch(query)
                if (search.isSuccess) {
                    "【工具 web_search】搜索结果：\n${search.getOrThrow()}"
                } else {
                    Log.w("ChatFlow", "web_search 失败：${search.exceptionOrNull()?.message}")
                    "【工具 web_search】搜索失败：${search.exceptionOrNull()?.message}"
                }
            }
        }

        "rescore" -> {
            val args = runCatching { json.decodeFromString<RescoreArgs>(argsJson) }.getOrNull()
            if (args == null || args.query.isBlank()) {
                "【工具 rescore】参数无效，未执行。"
            } else {
                val n = chatRepository.rescoreMemory(contact.id, args.query, args.importance)
                "【工具 rescore】已按「${args.query}」重新评定 $n 条记忆的重要性，沉睡记忆已一并唤醒。"
            }
        }

        "save_memory" -> {
            // save_memory 已在流结束处静默异步执行（startMemorySave），这里只回执状态
            "【工具 save_memory】该段对话的记忆已在后台保存处理。"
        }

        else -> "【工具 $name】不可用，请勿再调用。"
    }

    /** 构造搜索结果回填请求：把搜索结果作为一条 user 消息注入历史末尾 */
    private fun buildSearchFollowupRequest(request: ChatRequest, results: String): ChatRequest {
        val injected = ChatMessageDto(
            role = "user",
            content = "请使用以下网络搜索结果回答我的问题：\n\n$results\n\n（工具已执行完毕，请直接给出最终回答，不要再调用任何工具。）"
        )
        return request.copy(history = request.history + injected)
    }

    /** 构造 save_memory 之后的续写请求：告知模型记忆已在后台处理，请完成最终回复 */
    private fun buildMemoryFollowupRequest(request: ChatRequest): ChatRequest {
        val injected = ChatMessageDto(
            role = "user",
            content = "【系统】你刚才调用了 save_memory 工具保存这段对话的记忆，保存已在后台进行。" +
                "请直接完成对用户最后一条消息的回复，不要再调用任何工具。"
        )
        return request.copy(history = request.history + injected)
    }

    /** 构造 recall 之后的续写请求：把取回的记忆作为一条 user 消息注入 */
    private fun buildRecallFollowupRequest(request: ChatRequest, recalled: String): ChatRequest {
        Log.d("ChatFlow", "buildRecallFollowupRequest recalledLen=${recalled.length} historyBefore=${request.history.size}")
        val injected = ChatMessageDto(
            role = "user",
            content = "请结合下面取回的记忆完成对用户最后一条消息的回复。记忆内容已取回完整，请直接作答，不要再调用 recall 或任何工具：\n\n$recalled"
        )
        return request.copy(history = request.history + injected)
    }

    /** recall 0 命中时的兜底续写：强制直接作答，避免第二轮空转导致正文消失 */
    private fun buildRecallFallbackRequest(request: ChatRequest): ChatRequest {
        Log.d("ChatFlow", "buildRecallFallbackRequest historyBefore=${request.history.size}")
        val injected = ChatMessageDto(
            role = "user",
            content = "【系统】刚才的按需回忆没有检索到更详细的记忆。请不要再调用任何工具，直接根据你已知的信息（包括系统提示中的记忆索引）如实回答用户的问题。若确实不知道就明说不知道。"
        )
        return request.copy(history = request.history + injected)
    }

    /** 构造 rescore 之后的续写请求：告知重要性评定已生效 */
    private fun buildRescoreFollowupRequest(request: ChatRequest): ChatRequest {
        val injected = ChatMessageDto(
            role = "user",
            content = "【系统】你刚才重新评定的记忆重要性已生效。请直接完成对用户最后一条消息的回复，不要再调用任何工具。"
        )
        return request.copy(history = request.history + injected)
    }

    /**
     * 启动自动记忆保存任务（异步并行，不阻塞回复输出）。
     *
     * 一次对话只处理第一次 save_memory 调用，后续轮次再触发时直接忽略，
     * 避免"第二轮模型重试 → 再次弹提示"造成重复的保存成功/失败提示。
     */
    private fun startMemorySave(
        contact: ContactEntity,
        conversationId: String,
        toolCalls: Map<Int, List<String>>,
        toolNames: Map<Int, String?>
    ) {
        if (memoryTaskStarted) return
        memoryTaskStarted = true
        Log.d("SaveMemory", "save_memory 触发，异步保存开始 conversationId=$conversationId")

        _uiState.value = _uiState.value.copy(
            memorySave = MemorySaveUi(
                MemorySaveStatus.Running,
                getApplication<Application>().withAppLanguage().getString(R.string.chat_memory_saving)
            )
        )
        viewModelScope.launch {
            val saved = handlePendingToolCalls(contact, conversationId, toolCalls, toolNames)
            Log.d("SaveMemory", "异步保存完成 saved=$saved")
            _uiState.value = _uiState.value.copy(
                memorySave = MemorySaveUi(
                    status = if (saved) MemorySaveStatus.Success else MemorySaveStatus.Failure,
                    message = if (saved) getApplication<Application>().withAppLanguage().getString(R.string.chat_memory_saved)
                    else getApplication<Application>().withAppLanguage().getString(R.string.chat_memory_save_failed)
                )
            )
            // 结果停留 3 秒后自动消失
            delay(3000)
            if (_uiState.value.memorySave?.status != MemorySaveStatus.Running) {
                _uiState.value = _uiState.value.copy(memorySave = null)
            }
        }
    }

    /**
     * 处理主代理发起的 save_memory 工具调用。
     *
     * 静默执行：把每个 save_memory 的 arguments（分片拼接后）交给仓库处理。
     * @return 是否至少有新记忆写入（不抛异常也未必成功——子代理失败/重复都视作未保存）
     */
    private suspend fun handlePendingToolCalls(
        contact: ContactEntity,
        conversationId: String,
        toolCalls: Map<Int, List<String>>,
        toolNames: Map<Int, String?>
    ): Boolean {
        var saved = false
        toolCalls.forEach { (index, deltas) ->
            if (toolNames[index] == "save_memory") {
                val argsJson = deltas.joinToString("")
                Log.d("SaveMemory", "handleSaveMemoryTool 调用 raw=$argsJson")
                // 真实反映是否写入了新记忆（不抛异常也未必成功——子代理失败/重复都视作未保存）
                val ok = runCatching {
                    chatRepository.handleSaveMemoryTool(contact, conversationId, argsJson)
                }.onFailure {
                    Log.w("SaveMemory", "handleSaveMemoryTool 异常：${it.message}", it)
                }.getOrDefault(false)
                if (ok) saved = true
            }
        }
        return saved
    }

    /** 重新生成最后一轮回复 */
    fun regenerate() {
        viewModelScope.launch {
            val conversationId = _conversationId.value
            if (conversationId.isBlank()) return@launch

            val groupId = chatRepository.latestReplyGroupId(conversationId) ?: return@launch
            chatRepository.deleteReplyGroup(groupId)
            sendMessage(lastUserInput(conversationId) ?: return@launch)
        }
    }

    private suspend fun lastUserInput(conversationId: String): String? =
        chatRepository.lastUserMessage(conversationId)?.content

    /**
     * 把最近一段对话交给 AI 生成摘要并存入长期记忆。
     * 结果通过 [events] 以 Snackbar 反馈。
     */
    fun saveRecentAsMemory() {
        viewModelScope.launch {
            val contact = _contact.value ?: return@launch
            val conversationId = _conversationId.value
            if (conversationId.isBlank()) return@launch

            _events.emit(getApplication<Application>().withAppLanguage().getString(R.string.chat_memory_generating))
            val result = chatRepository.summarizeAndSaveMemory(contact, conversationId)
            result.onSuccess { summary ->
                val preview = summary.take(40) + if (summary.length > 40) "…" else ""
                _events.emit(getApplication<Application>().withAppLanguage().getString(R.string.chat_memory_saved_format, preview))
            }.onFailure { e ->
                _events.emit(e.message ?: getApplication<Application>().withAppLanguage().getString(R.string.chat_memory_save_failed))
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun resetStreamingState() {
        _uiState.value = _uiState.value.copy(
            isSending = false,
            isTyping = false,
            streamingText = "",
            streamingThinking = "",
            thinkingElapsedSec = 0f
        )
    }

    /**
     * 中断兜底：整轮只调工具（或思考到一半）却没产出正文时，
     * 把「已输出的思考 + 中断说明」落库为一条 AI 消息，
     * 让界面上保留中断前输出、并在下方提示已达工具使用轮次上限，而不是凭空消失。
     */
    private suspend fun saveInterruptedReply(
        conversationId: String,
        thinking: String,
        roundExhausted: Boolean
    ) {
        val note = getApplication<Application>().withAppLanguage().getString(
            if (roundExhausted) R.string.chat_tool_round_exhausted else R.string.chat_reply_interrupted
        )
        chatRepository.saveAiMessage(
            conversationId = conversationId,
            content = note,
            thinkingContent = thinking.takeIf { it.isNotBlank() },
            replyGroupId = ChatRepository.newReplyGroupId()
        )
        Log.w("ChatFlow", "saveInterruptedReply 已落库 roundExhausted=$roundExhausted thinkingLen=${thinking.length} note=$note")
    }

    companion object {
        private const val PAGE_SIZE = 30

        /** 上下文占用达到该比例即视为耗尽，禁止发起新对话（留足压缩提示词的余量） */
        private const val EXHAUSTED_RATIO = 0.9f

        /**
         * 流式最多轮数（agentic tool loop）：
         * 主回复 + 至多 [MAX_STREAM_FOLLOWUPS] 次工具结果续写。
         * 每轮是一次真实网络请求，因此上限要能覆盖"recall 取料 → 再取料 → 作答"的典型路径，
         * 又要防止模型无脑循环烧 token。超过上限即中断并落库说明。
         */
        private const val MAX_STREAM_PASSES = 6

        /** 工具后续写的最大预算（单次问答内工具调用轮次上限） */
        private const val MAX_STREAM_FOLLOWUPS = 5
    }
}

/**
 * 聊天页 UI 状态
 *
 * @param isSending 已发出请求、等待首个响应
 * @param isTyping 显示"正在输入"气泡
 * @param streamingText 流式期间累积的正文，未落库
 * @param streamingThinking 流式期间累积的思考过程
 * @param error 错误提示，非空时由 UI 以 Snackbar 展示
 */
data class ChatUiState(
    val isSending: Boolean = false,
    val isTyping: Boolean = false,
    val streamingText: String = "",
    val streamingThinking: String = "",
    /** 思考耗时（秒），思考结束开始输出正文时记录，用于"已完成思考 (Xs)"展示 */
    val thinkingElapsedSec: Float = 0f,
    /** 保存记忆的状态提示（输入框上方，短暂停留后消失） */
    val memorySave: MemorySaveUi? = null,
    val error: String? = null
)

/** 保存记忆的状态 */
enum class MemorySaveStatus { Running, Success, Failure }

/** 保存记忆的状态提示 */
data class MemorySaveUi(
    val status: MemorySaveStatus,
    val message: String
)

/** 聊天前拦截原因 */
enum class ApiBlockReason {
    /** 服务商未设置 API Key */
    NO_KEY,

    /** 服务商上次测试不可用 */
    UNAVAILABLE
}

/** 聊天前拦截信息（弹窗指引前往服务商管理） */
data class ApiBlockInfo(
    val providerName: String,
    val reason: ApiBlockReason
)
