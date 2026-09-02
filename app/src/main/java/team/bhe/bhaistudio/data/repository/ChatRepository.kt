package team.bhe.bhaistudio.data.repository

import android.util.Log
import androidx.paging.PagingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import team.bhe.bhaistudio.ai.ModelContextWindow
import team.bhe.bhaistudio.ai.ProviderEndpoint
import team.bhe.bhaistudio.ai.ProviderFactory
import team.bhe.bhaistudio.ai.SegmentedReplyScheduler
import team.bhe.bhaistudio.ai.TokenEstimator
import team.bhe.bhaistudio.ai.WebSearchClient
import team.bhe.bhaistudio.ai.model.ChatMessageDto
import team.bhe.bhaistudio.ai.model.ChatRequest
import team.bhe.bhaistudio.ai.model.ChatResult
import team.bhe.bhaistudio.ai.model.ContextUsage
import team.bhe.bhaistudio.ai.model.SaveMemoryArgs
import team.bhe.bhaistudio.ai.model.StreamChunk
import team.bhe.bhaistudio.data.db.dao.MessageDao
import team.bhe.bhaistudio.data.db.entity.ContactEntity
import team.bhe.bhaistudio.data.db.entity.MemoryEntity
import team.bhe.bhaistudio.data.db.entity.MemorySourceMessage
import team.bhe.bhaistudio.data.db.entity.MemoryType
import team.bhe.bhaistudio.data.db.entity.MessageEntity
import team.bhe.bhaistudio.data.db.entity.MessageRole
import team.bhe.bhaistudio.data.db.entity.newMessageId

/**
 * 对话仓库——整个 App 的业务中枢
 *
 * 整合了桌面端分散在 `index.js` 多个函数里的逻辑：
 *   - 保存消息       saveMessageToHistory
 *   - 拼装上下文     sendMessage（index.js:5730 历史拼文本）
 *   - 注入长期记忆   index.js:5690
 *   - 分派 provider  index.js:5653
 *   - 分段播放       SegmentedReply.execute
 *
 * ── 相对桌面端的两处改进 ──────────────────────────────────
 * 1. **历史不再拼成文本塞进 system prompt**，改为标准 messages 数组
 * 2. **一轮回复显式分组**（[MessageEntity.replyGroupId]），
 *    分段回复的 N 条消息可整组删除，支持"重新生成"
 */
class ChatRepository(
    private val messageDao: MessageDao,
    private val conversationRepository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val providerConfigRepository: ProviderConfigRepository,
    private val providerFactory: ProviderFactory,
    private val scheduler: SegmentedReplyScheduler,
    private val settingsRepository: SettingsRepository,
    private val searchConfigRepository: SearchConfigRepository,
    private val webSearchClient: WebSearchClient
) {

    private val json = Json { ignoreUnknownKeys = true }

    // ─────────────────────────────────────────────────────
    // 查询（UI 层经由这里访问，不直接摸 DAO）
    // ─────────────────────────────────────────────────────

    /** 聊天消息分页源，倒序（最新在前），配合 LazyColumn 的 reverseLayout */
    fun pagingMessages(conversationId: String): PagingSource<Int, MessageEntity> =
        messageDao.pagingByConversation(conversationId)

    suspend fun lastUserMessage(conversationId: String): MessageEntity? =
        messageDao.lastByRole(conversationId, MessageRole.USER)

    suspend fun latestReplyGroupId(conversationId: String): String? =
        messageDao.lastByRole(conversationId, MessageRole.AI)?.replyGroupId

    // ─────────────────────────────────────────────────────
    // 写入
    // ─────────────────────────────────────────────────────

    /** 保存用户消息并刷新会话预览 */
    suspend fun saveUserMessage(conversationId: String, content: String): MessageEntity {
        val message = MessageEntity(
            id = newMessageId(),
            conversationId = conversationId,
            role = MessageRole.USER,
            content = content
        )
        messageDao.insert(message)
        conversationRepository.updatePreview(conversationId, content)
        return message
    }

    /**
     * 保存 AI 回复。
     *
     * @param replyGroupId 一轮回复的分组 id。分段回复时多条消息共享同一个
     * @param replyIndex 段序，从 0 开始。非分段回复为 0
     */
    suspend fun saveAiMessage(
        conversationId: String,
        content: String,
        thinkingContent: String? = null,
        replyGroupId: String,
        replyIndex: Int = 0
    ): MessageEntity {
        val cleanContent = content.stripApiMessageIndex()
        val message = MessageEntity(
            id = newMessageId(),
            conversationId = conversationId,
            role = MessageRole.AI,
            content = cleanContent,
            thinkingContent = thinkingContent,
            replyGroupId = replyGroupId,
            replyIndex = replyIndex
        )
        messageDao.insert(message)
        conversationRepository.updatePreview(conversationId, cleanContent)
        return message
    }

    /**
     * 分段播放并逐条落库。
     *
     * 每段落库一次——与桌面端行为一致（每段是一条独立消息），
     * 好处是中途退出也不会丢失已播放的内容。
     */
    suspend fun saveSegmentedReply(
        conversationId: String,
        fullText: String,
        replyGroupId: String = newReplyGroupId(),
        onSegmentShown: suspend (MessageEntity, index: Int) -> Unit = { _, _ -> }
    ) {
        val segments = scheduler.splitTextSmart(fullText.stripApiMessageIndex())
        scheduler.play(segments) { segment, index ->
            val saved = saveAiMessage(
                conversationId = conversationId,
                content = segment,
                replyGroupId = replyGroupId,
                replyIndex = index
            )
            onSegmentShown(saved, index)
        }
    }

    /** 删除整轮回复（重新生成时用） */
    suspend fun deleteReplyGroup(replyGroupId: String) {
        messageDao.listByReplyGroup(replyGroupId).forEach { messageDao.deleteById(it.id) }
    }

    suspend fun clearConversation(conversationId: String) =
        messageDao.deleteByConversation(conversationId)

    // ─────────────────────────────────────────────────────
    // 请求
    // ─────────────────────────────────────────────────────

    /**
     * 构造请求。
     *
     * 服务商、密钥、端点风格全部来自 [team.bhe.bhaistudio.data.db.entity.ProviderConfigEntity]，
     * 角色只决定"人设 + 模型 + 开关"。
     *
     * @param historyRounds 参与上下文的历史轮数，取自设置项
     * @return 失败时携带可读原因，UI 直接展示即可
     */
    suspend fun buildChatRequest(
        contact: ContactEntity,
        conversationId: String,
        historyRounds: Int,
        includeMemory: Boolean = true
    ): Result<ChatRequest> {
        val config = providerConfigRepository.getById(contact.providerConfigId)
            ?: return Result.failure(IllegalStateException("该角色的服务商配置不存在"))

        val apiKey = providerConfigRepository.getDecryptedKey(config.id)
            ?: return Result.failure(IllegalStateException("「${config.name}」尚未配置 API Key"))

        if (contact.model.isBlank()) {
            return Result.failure(IllegalStateException("该角色未选择模型"))
        }

        // 自动记忆开启时，声明 save_memory 工具并告知主代理消息编号约定
        val includeMemoryTool = settingsRepository.enableAutoMemory.first()

        // 自定义联网搜索：角色开启且已配置搜索 API 时，声明 web_search 工具
        val useCustomSearch = contact.webSearch && contact.useCustomSearch &&
            searchConfigRepository.first() != null

        // 人设 + 时间 + 压缩摘要 + 记忆 + 记忆/搜索工具说明共同构成 system prompt
        val memoryPrompt = if (includeMemory) memoryRepository.buildMemoryPrompt(contact.id) else ""
        // 记忆库非空就声明 recall 工具：不仅能取回活跃索引对应的正文，
        // 还能捞回"已淡忘"的沉睡记忆（元记忆：知道有旧事 → 按需回忆）
        val memoryCount = if (includeMemory) memoryRepository.count(contact.id) else 0
        val includeRecallTool = includeMemoryTool && memoryCount > 0
        val compressed = conversationRepository.getById(conversationId)?.compressedSummary.orEmpty()
        val systemPrompt = buildString {
            // 时间感知：模型不知道"现在几号周几"，注入后角色才有时间感（问候、时态都对）
            appendLine(CURRENT_TIME_HINT)
            if (contact.systemPrompt.isNotBlank()) appendLine(contact.systemPrompt)
            if (compressed.isNotBlank()) {
                appendLine("<对话摘要>")
                appendLine(compressed)
                appendLine("</对话摘要>")
            }
            if (memoryPrompt.isNotBlank()) appendLine(memoryPrompt)
            if (includeMemoryTool) appendLine(MEMORY_AGENT_HINT)
            // 有记忆索引时才告知 recall 工具的用法（正文不注入，靠按需调取）
            if (includeRecallTool) appendLine(RECALL_HINT)
            // 只有开启了自定义搜索才告诉 AI 有这个工具，否则不提
            if (useCustomSearch) appendLine(WEB_SEARCH_HINT)
        }.trim()

        // ×2 是因为一轮包含用户与 AI 各一条。
        // 给每条消息加 [n✦] 编号（升序），让主代理能精确指定"总结哪段对话"。
        // 用冷门字符 ✦ 做标记：即使模型在回复里学样输出编号（如 [8✦]），
        // 剥离时只认 [n✦] 这种格式，不会误伤用户正常输入的 [数字] 内容。
        val history = messageDao
            .listRecent(conversationId, historyRounds * 2)
            .map { it.role.toApiRole() to it.content }
            .mapIndexed { index, (role, content) ->
                ChatMessageDto(role, "[${index + 1}$MESSAGE_INDEX_MARK] $content")
            }

        return Result.success(
            ChatRequest(
                apiKey = apiKey,
                model = contact.model,
                history = buildList {
                    if (systemPrompt.isNotBlank()) add(ChatMessageDto("system", systemPrompt))
                    history.forEach { add(it) }
                },
                enableThinking = contact.deepThink,
                enableSearch = contact.webSearch,
                temperature = contact.temperature.takeIf { it > 0f },
                topP = contact.topP.takeIf { it > 0f },
                thinkingBudget = contact.thinkingBudget,
                includeMemoryTool = includeMemoryTool,
                includeRecallTool = includeRecallTool,
                useCustomSearch = useCustomSearch
            )
        )
    }

    /** 由服务商配置构造协议端点（解密密钥） */
    suspend fun buildEndpoint(contact: ContactEntity): Result<ProviderEndpoint> {
        val config = providerConfigRepository.getById(contact.providerConfigId)
            ?: return Result.failure(IllegalStateException("该角色的服务商配置不存在"))

        val apiKey = providerConfigRepository.getDecryptedKey(config.id).orEmpty()
        return Result.success(
            ProviderEndpoint(
                baseUrl = config.baseUrl,
                chatPath = config.chatPath,
                apiKey = apiKey,
                protocol = config.protocol,
                thinkingStyle = config.thinkingStyle,
                searchStyle = config.searchStyle
            )
        )
    }

    /** 流式回复。返回的 [StreamChunk] 全部是增量，调用方拼接即可 */
    fun streamReply(endpoint: ProviderEndpoint, request: ChatRequest): Flow<StreamChunk> =
        providerFactory.streamChat(endpoint, request)

    /** 一次性回复（角色扮演 / 关闭流式传输） */
    suspend fun onceReply(endpoint: ProviderEndpoint, request: ChatRequest): ChatResult =
        withContext(Dispatchers.IO) { providerFactory.chat(endpoint, request) }

    // ─────────────────────────────────────────────────────
    // 策略
    // ─────────────────────────────────────────────────────

    /**
     * 是否走分段回复。
     *
     * 沿用桌面端的角色条件（index.js:2208）：
     * `contact.roleplay && !contact.deepthink`
     *
     * 分段回复完全由角色决定（全局设置里已移除该开关）。
     * 深度思考模式下内容是边想边出的，再分段会让节奏割裂。
     */
    fun shouldUseSegmentedReply(contact: ContactEntity): Boolean =
        contact.roleplay && !contact.deepThink

    fun splitSegments(text: String): List<String> = scheduler.splitTextSmart(text)

    suspend fun clearContactMemories(contactId: String) =
        memoryRepository.clearContact(contactId)

    // ─────────────────────────────────────────────────────
    // 长期记忆生成
    // ─────────────────────────────────────────────────────

    /**
     * 把最近一段对话交给 AI 生成记忆摘要并入库（桌面端 add-long-term-memory 思路）。
     *
     * 用联系人自己的模型与密钥做摘要，不额外要求任何配置。
     *
     * @param count 参与摘要的最近消息条数
     * @return 成功返回摘要文本，失败携带可读原因
     */
    suspend fun summarizeAndSaveMemory(
        contact: ContactEntity,
        conversationId: String,
        count: Int = MEMORY_SUMMARY_ROUNDS * 2
    ): Result<String> {
        val messages = messageDao.listRecent(conversationId, count)
        if (messages.isEmpty()) {
            return Result.failure(IllegalStateException("还没有可保存的对话"))
        }

        val endpoint = buildEndpoint(contact).getOrElse { return Result.failure(it) }

        // 摘要只发「摘要指令 + 对话文本」，不带历史记忆，避免上下文膨胀。
        // 视角与自动记忆一致：记忆注入给主代理看，"我" = 角色本人
        val transcript = messages.joinToString("\n") {
            val who = if (it.role == MessageRole.USER) "对方" else "我"
            "$who：${it.content}"
        }
        val summaryRequest = ChatRequest(
            apiKey = endpoint.apiKey,
            model = contact.model,
            history = listOf(
                ChatMessageDto("system", memorySummaryPrompt(contact.name, contact.systemPrompt)),
                ChatMessageDto("user", transcript)
            ),
            temperature = 0.3f
        )

        // 子代理总结是网络请求，必须切到 IO 线程（viewModelScope 默认跑主线程）
        val output = runCatching {
            withContext(Dispatchers.IO) { providerFactory.chat(endpoint, summaryRequest) }
        }.getOrElse { return Result.failure(it) }
            .content
        val parsed = parseSummaryOutput(output)
        // 摘要为空时兜底直接截取原文，保证手动保存至少不落空
        val body = parsed.summary.ifBlank { transcript.take(80) }

        memoryRepository.add(
            MemoryEntity(
                contactId = contact.id,
                summary = body,
                indexSummary = parsed.keywords.joinToString("；"),
                originalMessages = messages.map {
                    MemorySourceMessage(
                        role = it.role.toApiRole(),
                        content = it.content,
                        time = it.createdAt
                    )
                },
                // 手动保存没有主代理打分，由子代理按重要性规则自评
                importance = parsed.importance
            )
        )
        return Result.success(body)
    }

    // ─────────────────────────────────────────────────────
    // 按需回忆（recall 工具调用）
    // ─────────────────────────────────────────────────────

    /**
     * 主代理 recall 工具：用 query 在索引/正文里检索记忆并取回整段正文。
     *
     * 这是记忆的「按需回忆」：索引常驻 + 命中才调取详情，
     * 每次命中记录一次调取（accessCount / lastAccessAt 参与激活权重）。
     *
     * @return 格式化的记忆文本；无命中返回空串（调用方不注入，避免污染上下文）
     */
    suspend fun recallMemory(contactId: String, query: String): String {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            Log.i("RecallFlow", "recallMemory skipped: blank query")
            return ""
        }
        val hits = memoryRepository.search(contactId, keyword)
        Log.i("RecallFlow", "recallMemory contactId=$contactId query='$keyword' hits=${hits.size} states=${hits.map { it.state.name }}")
        if (hits.isEmpty()) {
            // 常规检索（整句/切词/公共子串）全空 = 迫不得已 → 触发索引全量检索：
            // 把该角色全部记忆按重要性列成完整清单给模型人工定位。
            Log.w("RecallFlow", "recallMemory no hits for query='$keyword'，触发索引全量检索")
            val all = memoryRepository.listAllForRecall(contactId)
            if (all.isEmpty()) {
                Log.w("RecallFlow", "recallMemory 记忆库为空，全量检索也无内容")
                return ""
            }
            return buildString {
                appendLine("<记忆全量清单>")
                appendLine("按你的记忆检索词没有找到直接匹配的内容。以下是这个角色现存记忆的完整清单，请在其中人工定位与问题相关的记忆（这是最后一次尝试，之后不得再调用任何工具）：")
                all.forEachIndexed { index, memory ->
                    val layer = if (memory.memoryType == MemoryType.LONG_TERM) "长期" else "短期"
                    val kw = memory.indexSummary.ifBlank { memory.summary.take(30) }
                    appendLine("${index + 1}. [$layer] 关键词：$kw；内容：${memory.summary}")
                }
                appendLine("</记忆全量清单>")
            }
        }
        return buildString {
            appendLine("<已调取的记忆>")
            hits.forEachIndexed { index, memory ->
                val layer = if (memory.memoryType == MemoryType.LONG_TERM) "长期" else "短期"
                appendLine("${index + 1}. [$layer] ${memory.summary}")
                memoryRepository.markAccessed(memory.id)
            }
            append("</已调取的记忆>")
        }
    }

    /**
     * AI rescore 工具：按查询词重新评定记忆的重要性（可命中沉睡记忆并唤醒），
     * 返回更新的条数。让记忆价值随相处变化，而不是永远停留在保存那一刻的打分。
     */
    suspend fun rescoreMemory(contactId: String, query: String, importance: Float): Int =
        memoryRepository.rescoreByQuery(contactId, query, importance)

    // ─────────────────────────────────────────────────────
    // 自动记忆（save_memory 工具调用）
    // ─────────────────────────────────────────────────────

    /**
     * 处理主代理的 save_memory 工具调用（静默，用户无感知）。
     *
     * 主代理只指定"要总结的对话编号范围"，这里把该范围的消息取出来，
     * 交给记忆子代理总结成短期/长期记忆，并按 remove 指令清理过期记忆。
     * 任何失败都静默忽略，不影响主流程。
     */
    /**
     * 处理 save_memory 工具调用。
     *
     * 返回值：
     * - true：至少有新记忆写入（addIfNew 真的 insert 了）
     * - false：参数无效 / 消息为空 / 子代理失败 / 重复记忆等任何未真正入库的情况
     *
     * remove / replace 不算"新增"——如果只是删除/替换了几条，没新增就不算成功。
     */
    suspend fun handleSaveMemoryTool(contact: ContactEntity, conversationId: String, argsJson: String): Boolean {
        val args = runCatching { json.decodeFromString<SaveMemoryArgs>(argsJson) }
            .getOrElse {
                parseArgsJsonBlock(argsJson).also { parsed ->
                    if (parsed == null) Log.w("SaveMemory", "args 解析失败 raw=$argsJson", it)
                } ?: return false
            }
        if (args.from <= 0 || args.to < args.from) {
            Log.w("SaveMemory", "参数无效 from=${args.from} to=${args.to} raw=$argsJson")
            return false
        }

        // 与 buildChatRequest 使用相同的查询与编号规则（[n] 从 1 开始、升序）
        val rounds = settingsRepository.historyMemoryRounds.first()
        val messages = messageDao.listRecent(conversationId, rounds * 2)
        if (messages.isEmpty()) {
            Log.w("SaveMemory", "对话消息为空 conversationId=$conversationId rounds=$rounds")
            return false
        }
        val fromIdx = (args.from - 1).coerceIn(0, messages.lastIndex)
        val toIdx = (args.to - 1).coerceIn(0, messages.lastIndex)
        if (fromIdx > toIdx) {
            Log.w("SaveMemory", "索引越界 fromIdx=$fromIdx toIdx=$toIdx 消息数=${messages.size}")
            return false
        }
        val slice = messages.subList(fromIdx, toIdx + 1)
        if (slice.isEmpty()) {
            Log.w("SaveMemory", "切片为空 fromIdx=$fromIdx toIdx=$toIdx")
            return false
        }

        // 移除/替换操作即使发生也不算"保存成功"——必须有新记忆入库
        val added = summarizeRangeAndSave(contact, slice, args.longTerm, args.note, args.importance)
        args.remove.forEach { memoryRepository.deleteByContent(contact.id, it) }
        args.replace.forEach { memoryRepository.replace(contact.id, it.old, it.new) }
        Log.i("SaveMemory", "保存完成 added=$added remove=${args.remove.size} replace=${args.replace.size} 消息数=${slice.size}")
        return added
    }

    /** 把指定范围的消息交给记忆子代理总结并入库（短期/长期按参数） */
    private suspend fun summarizeRangeAndSave(
        contact: ContactEntity,
        messages: List<MessageEntity>,
        longTerm: Boolean,
        note: String? = null,
        importance: Float? = null
    ): Boolean {
        val endpoint = buildEndpoint(contact).getOrElse {
            Log.w("SaveMemory", "buildEndpoint 失败：${it.message} contact=${contact.name} providerId=${contact.providerConfigId}")
            return false
        }
        // 以角色本人为"我"：记忆是注入给主代理看的，视角必须和主代理一致，
        // 否则会写出「我给对方取名」这种主客体颠倒的记忆
        val transcript = messages.joinToString("\n") {
            val who = if (it.role == MessageRole.USER) "对方" else "我"
            "$who：${it.content}"
        }
        // 主代理的备注是视角对齐的关键：子代理看不到对话当下主代理的心理活动，
        // 只能靠这条以主代理口吻写的说明来理解"这段到底要记什么"
        val userContent = buildString {
            appendLine("需要整理的对话：")
            appendLine(transcript)
            if (!note.isNullOrBlank()) {
                appendLine()
                appendLine("你（${contact.name}）指定要记住的内容（只整理这些，其余一律忽略）：")
                append(note)
            }
            // 主代理在工具调用里给这段内容评过重要性：要求子代理原样透传，不自作主张
            if (importance != null) {
                appendLine()
                appendLine("主代理为这段内容评定的重要性：${importance.coerceIn(0f, 1f)}（0~1）。")
                appendLine("请把这个数值原样填进 <importance>，不要自行改动。")
            }
        }
        val request = ChatRequest(
            apiKey = endpoint.apiKey,
            model = contact.model,
            history = listOf(
                ChatMessageDto("system", memorySummaryPrompt(contact.name, contact.systemPrompt)),
                ChatMessageDto("user", userContent)
            ),
            temperature = 0.3f
        )

        // 子代理总结是网络请求，必须切到 IO 线程（viewModelScope 默认跑主线程）
        val output = runCatching {
            withContext(Dispatchers.IO) { providerFactory.chat(endpoint, request) }
        }.getOrElse {
            Log.w("SaveMemory", "子代理总结调用失败：${it.message}", it)
            return false
        }.content
        val parsed = parseSummaryOutput(output)
        val body = parsed.summary.trim()

        // 子代理判定没有值得记住的内容（输出空）→ 不入库，避免凑数记忆污染
        if (body.isBlank()) {
            Log.i("SaveMemory", "子代理判定无值得记住的内容，跳过保存")
            return false
        }

        // L3：保存前检索合并——与已有记忆（ACTIVE + INACTIVE）高度相关则合并更新，不产生克隆。
        // 检索不到的"重复"视为无影响（可接受冗余），照常新增。
        val existing = memoryRepository.findMergeCandidate(contact.id, body, parsed.keywords)
        if (existing != null) {
            val merged = mergeWithExisting(contact, existing, parsed)
            Log.i("SaveMemory", "命中已有记忆(id=${existing.id})，合并结果 merged=$merged")
            if (merged) return true
            Log.w("SaveMemory", "合并失败，改走新增（可能产生近似重复）")
        }

        val added = memoryRepository.addIfNew(
            MemoryEntity(
                contactId = contact.id,
                summary = body,
                indexSummary = parsed.keywords.joinToString("；"),
                originalMessages = messages.map {
                    MemorySourceMessage(
                        role = it.role.toApiRole(),
                        content = it.content,
                        time = it.createdAt
                    )
                },
                memoryType = if (longTerm) MemoryType.LONG_TERM else MemoryType.SHORT_TERM,
                importance = parsed.importance
            )
        )
        Log.i("SaveMemory", "新记忆入库 added=$added indexSummary='${parsed.keywords.joinToString("；")}' longTerm=$longTerm")
        if (!added) Log.w("SaveMemory", "addIfNew 未写入（重复记忆或空摘要） summary=${body.take(60)}")
        return added
    }

    /**
     * L3：调用记忆子代理把「已有记忆 + 新总结」合并为一条并唤醒原记录。
     * 子代理失败/输出为空时返回 false，调用方回退为直接新增。
     */
    private suspend fun mergeWithExisting(
        contact: ContactEntity,
        existing: MemoryEntity,
        parsed: SummaryOutput
    ): Boolean {
        val endpoint = buildEndpoint(contact).getOrElse {
            Log.w("SaveMemory", "merge buildEndpoint 失败：${it.message}")
            return false
        }
        val systemPrompt =
            "你就是「${contact.name}」本人。你正在把两条关于对方（用户）的记忆合并成一条。\n" +
            "要求：\n" +
            "1. 保留两条里所有仍然有效的信息，去掉已被新说法覆盖的旧表述。\n" +
            "2. 每条一行，20~40 字，直接陈述事实，不写「用户说/对方说」。\n" +
            "3. 关键词必须覆盖合并后所有要点，且每点同时给具体词与泛化类别词（例：意大利面 + 饮食偏好/爱吃什么），" +
            "保证日后即使只记得模糊印象也能检索到。\n" +
            "输出格式（严格使用以下标记）：\n" +
            "<summary>\n（合并后的总结正文，每条一行）\n</summary>\n" +
            "<keywords>\n（关键词，用；分隔）\n</keywords>"
        val userContent = "已有记忆：\n${existing.summary}\n\n新增内容：\n${parsed.summary}"
        val request = ChatRequest(
            apiKey = endpoint.apiKey,
            model = contact.model,
            history = listOf(
                ChatMessageDto("system", systemPrompt),
                ChatMessageDto("user", userContent)
            ),
            temperature = 0.3f
        )
        val output = runCatching {
            withContext(Dispatchers.IO) { providerFactory.chat(endpoint, request) }
        }.getOrElse {
            Log.w("SaveMemory", "合并子代理调用失败：${it.message}", it)
            return false
        }.content
        val merged = parseSummaryOutput(output)
        if (merged.summary.isBlank()) {
            Log.w("SaveMemory", "合并子代理输出为空，放弃合并")
            return false
        }
        memoryRepository.updateMerged(
            id = existing.id,
            summary = merged.summary.trim(),
            keywords = merged.keywords.joinToString("；"),
            importance = maxOf(existing.importance, parsed.importance)
        )
        return true
    }

    /** 容错解析 save_memory 参数：模型偶尔会包在 ```json 代码块里 */
    private fun parseArgsJsonBlock(raw: String): SaveMemoryArgs? {
        val cleaned = raw
            .substringAfter("```json", raw)
            .substringAfter("```", raw)
            .substringBefore("```")
            .trim()
        return runCatching { json.decodeFromString<SaveMemoryArgs>(cleaned) }.getOrNull()
    }

    // ─────────────────────────────────────────────────────
    // 自定义联网搜索
    // ─────────────────────────────────────────────────────

    /**
     * 执行自定义联网搜索：用列表中的第一条搜索配置，
     * 返回格式化结果文本，交给模型回答。
     */
    suspend fun executeWebSearch(query: String): Result<String> {
        val config = searchConfigRepository.first()
            ?: return Result.failure(IllegalStateException("未配置自定义搜索 API，请到「设置 → 网络搜索」添加"))
        return webSearchClient.search(config, query)
    }

    // ─────────────────────────────────────────────────────
    // 上下文窗口 / 压缩
    // ─────────────────────────────────────────────────────

    /**
     * 估算当前上下文占用（本地估算，参考 CodeWhale compaction.rs）。
     *
     * 包含：system prompt（人设 + 压缩摘要 + 记忆 + 工具说明）与全部历史消息。
     */
    suspend fun estimateContextUsage(contact: ContactEntity, conversationId: String): ContextUsage {
        val includeMemoryTool = settingsRepository.enableAutoMemory.first()
        val memoryPrompt = memoryRepository.buildMemoryPrompt(contact.id)
        val compressed = conversationRepository.getById(conversationId)?.compressedSummary.orEmpty()
        val systemText = buildString {
            appendLine(CURRENT_TIME_HINT)
            if (contact.systemPrompt.isNotBlank()) appendLine(contact.systemPrompt)
            if (compressed.isNotBlank()) {
                appendLine("<对话摘要>")
                appendLine(compressed)
                appendLine("</对话摘要>")
            }
            if (memoryPrompt.isNotBlank()) appendLine(memoryPrompt)
            if (includeMemoryTool) appendLine(MEMORY_AGENT_HINT)
        }.trim()

        val messages = messageDao.listByConversation(conversationId)
            .map { it.role.toApiRole() to it.content }
        val used = TokenEstimator.total(systemText, messages)
        // 手动指定窗口优先，0 = 按模型自动推断
        val total = if (contact.contextWindow > 0) {
            contact.contextWindow
        } else {
            ModelContextWindow.forModel(contact.model)
        }
        return ContextUsage(used = used, total = total)
    }

    /**
     * 压缩当前会话上下文：
     * 用联系人的模型对全部历史做摘要 → 存入会话的 compressedSummary →
     * 清空历史、保留最近 2 轮（4 条）作为近期上下文。
     *
     * @return 成功返回摘要文本；失败携带可读原因
     */
    suspend fun compressContext(contact: ContactEntity, conversationId: String): Result<String> {
        val endpoint = buildEndpoint(contact).getOrElse { return Result.failure(it) }
        val messages = messageDao.listByConversation(conversationId)
        if (messages.isEmpty()) return Result.success("")

        val transcript = messages.joinToString("\n") {
            val who = if (it.role == MessageRole.USER) "我" else "对方"
            "$who：${it.content}"
        }
        val request = ChatRequest(
            apiKey = endpoint.apiKey,
            model = contact.model,
            history = listOf(
                ChatMessageDto("system", CONTEXT_COMPRESSION_PROMPT),
                ChatMessageDto("user", transcript)
            ),
            temperature = 0.3f
        )
        val summary = runCatching {
            withContext(Dispatchers.IO) { providerFactory.chat(endpoint, request) }
        }.getOrElse { return Result.failure(it) }
            .content.trim()

        if (summary.isNotBlank()) {
            conversationRepository.updateCompressedSummary(conversationId, summary)
            // 保留最近 2 轮（4 条），其余删除，作为压缩后的近期上下文
            val keep = messages.takeLast(KEEP_ROUNDS * 2)
            messageDao.deleteByConversation(conversationId)
            messageDao.insertAll(keep)
        }
        return Result.success(summary)
    }

    companion object {
        fun newReplyGroupId(): String = "reply-${System.currentTimeMillis()}"

        /** 当前时间提示：注入 system prompt，让角色感知时间 */
        private val CURRENT_TIME_HINT: String by lazy {
            val now = java.time.LocalDateTime.now()
            val week = now.dayOfWeek
                .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CHINESE)
            "当前时间：${now.year}年${now.monthValue}月${now.dayOfMonth}日 $week " +
                "%02d:%02d".format(now.hour, now.minute)
        }

        /** 上下文压缩的系统指令：自包含、保留关键信息、第三人称 */
        private const val CONTEXT_COMPRESSION_PROMPT =
            "你是一个对话压缩助手。下面是一段角色扮演对话的完整历史。" +
            "请把这段对话压缩成一份精炼的「上下文摘要」，要求：\n" +
            "1. 保留所有对延续对话重要的信息：关键事件、用户的个人信息与偏好、角色的承诺、未完成的话题、重要决定、正在进行的计划\n" +
            "2. 按时间顺序组织，去掉寒暄、重复和无关内容\n" +
            "3. 用第三人称简洁叙述，保持事实准确，不要编造\n" +
            "4. 摘要必须自包含：让一个没看过原文的 AI 仅凭摘要就能无缝延续对话\n" +
            "5. 控制在 500 字以内\n" +
            "只输出摘要正文。"

        /** 压缩后保留的最近对话轮数（×2 条消息）作为近期上下文 */
        private const val KEEP_ROUNDS = 2

        /** 自定义联网搜索工具说明：只有开启自定义搜索时才注入 system prompt */
        private const val WEB_SEARCH_HINT =
            "你有一个 web_search 工具。需要实时信息（天气、新闻、最新事件、不确定的事实核查）时，" +
            "调用它获取最新结果，不要编造。"

        /** 消息编号标记：冷门字符，区分于用户/模型的正常 `[数字]` 文本 */
        private const val MESSAGE_INDEX_MARK = "✦"

        /**
         * 记忆摘要的系统指令：让子代理**代入角色本人**整理记忆。
         *
         * 之前要求"第三人称"是视角混乱的根源：模型会写出
         * 「我给对方取名为迷迷，对方非常喜欢这个名字」这种句子——
         * 记里的"我"到底是用户还是角色？记忆是注入给主代理看的，
         * 主代理读到会理解成"是我给用户取了名"，自我认知错乱，
         * 甚至因此反复触发保存记忆（连锁反应）。
         *
         * 现在明确以角色本人第一人称写：我 = 角色自己，对方/TA = 用户。
         */
        private fun memorySummaryPrompt(name: String, persona: String): String =
            "你就是「$name」本人，下面是你和对方（用户）之间的对话记录。\n" +
            "请以**第一人称**整理出值得你记住的内容：「我」= $name，「TA / 对方 / 用户」= 和你对话的人。\n" +
            "你的人设：${persona.take(300)}\n\n" +
            "输出分三段：先写「总结」正文，再为总结**逐条**提炼「关键词」，最后给这段内容评一个「重要性」。\n\n" +
            "【总结】规则：\n" +
            "1. 只记录真正重要、会影响你们未来相处的事实：身份与称呼、关系、重要经历、偏好、承诺、长期状态。\n" +
            "2. 不要记录：寒暄客套、临时性信息、重复已有记忆的内容、你自己刚说过的话的复述、对方一时的情绪。\n" +
            "3. 每条一行，20~40 字，直接陈述事实，不要写「用户说/对方说」这类元描述。\n" +
            "4. 数量不限（通常 1~3 条），宁缺毋滥。\n" +
            "5. 如果下面给出了「你指定要记住的内容」，**只整理那里列出的条目**" +
            "（可精简措辞、统一成你的视角），对话中其它无关信息一律忽略，不要自作主张补充。\n\n" +
            "【关键词】规则：\n" +
            "1. **必须覆盖总结中的每一条内容**：总结写了 N 条，关键词就要让 N 块信息日后都能被检索到，绝不允许只挑最醒目的一条、丢掉其余信息。\n" +
            "2. **每条都要同时给两类词，兼顾『记得清』与『记不清』两种回想方式**：\n" +
            "   · 具体词：直接代表这条信息的人/物/事/地方（例：意大利面、奶奶、豆豆）——你将来能直接想起它时靠它检索；\n" +
            "   · 泛化词：这条信息的上位类别，以及对方日后可能会怎么问起（例：饮食偏好、爱吃什么、宠物、家人、童年回忆、日常习惯、喜好）——\n" +
            "     就算你完全忘记细节、只隐约记得「好像聊过对方喜欢吃什么」这种模糊印象，也能用泛化词把这格记忆翻出来。\n" +
            "3. 同一条信息的多个词写在一起（例：总结写了「TA 最爱吃奶奶做的意大利面」，关键词写「意大利面；奶奶；饮食偏好；喜欢吃什么」）。\n" +
            "4. 每条 1~2 个词即可，要能代表那个信息点、能和别的记忆区分开。\n" +
            "5. 用「；」分隔写在一行里，不编号、不分行。\n\n" +
            "【importance】规则：\n" +
            "1. 填一个 0~1 的小数，表示这段记忆在你们长期相处中的分量：\n" +
            "   0.9+ = 身份/称呼/承诺/决定性经历（几乎不该被遗忘）；\n" +
            "   0.6~0.8 = 稳定偏好、长期状态；0.3~0.6 = 普通但有价值的经历；<0.3 = 琐事。\n" +
            "2. **如果上面明确给了你一个重要性数值，原样照抄它，不要自行改动**。\n\n" +
            "输出格式（严格使用以下标记，不要输出标记以外的任何文字）：\n" +
            "<summary>\n（总结正文，每条一行）\n</summary>\n" +
            "<keywords>\n（关键词，用；分隔）\n</keywords>\n" +
            "<importance>\n（0~1 的小数）\n</importance>\n" +
            "**如果这段对话没有任何值得记住的内容，<summary> 与 <keywords> 都留空，<importance> 也留空**。"

        private const val MEMORY_SUMMARY_ROUNDS = 5

        /**
         * 注入主代理 system prompt 的 recall 说明：
         * 记忆正文不常驻，只有索引；需要细节时调用 recall 取回。
         */
        private const val RECALL_HINT =
            "你保存过关于对方的记忆：近期活跃的记忆以检索索引形式写在系统提示里（<已保存的记忆>/<近期动态>），" +
            "更早的记忆可能已随时间淡忘、不会出现在索引中，但仍可通过 recall 找回。" +
            "当对方问起过去的事（喜好、经历、约定等），或你隐约觉得这件事之前提到过却记不清细节时，调用 recall 工具，" +
            "把想回忆的事情描述清楚（人物、话题或事件关键词均可），系统会取回对应记忆正文，包括已淡忘的旧记忆。" +
            "若 recall 后仍没有相关内容，就如实说不记得，不要凭空编造。"

        /**
         * 注入主代理 system prompt 的说明：
         * 告知消息编号约定，以及何时调用 save_memory 工具。
         */
        private const val MEMORY_AGENT_HINT =
            "你的对话历史中每条消息前带有编号（如 [3✦] 我：...，✦ 是标记字符），编号从 1 开始、按顺序递增。" +
            "如果你判断某段连续编号 [from]~[to] 的对话（可以横跨多轮）构成了值得长期记住的完整事件，" +
            "请调用 save_memory 工具并指定该范围（from/to 为消息编号，longTerm 表示是否长期记忆）。" +
            "旧记忆被新信息推翻时用 remove 删除其原文，表述发生变化时（如搬家、换工作）用 replace 更新。" +
            "如果没有新的值得记住的内容，不要调用本工具。"
    }
}

/**
 * 剥掉消息开头的 `[N✦] ` 编号——这是发给 AI 用于 save_memory 指定范围的内部标记，
 * 不该出现在落库内容 / 预览 / 导出里。✦ 是冷门标记，只匹配内部编号格式，
 * 不误伤用户/模型正常输出的 `[数字]` 文本。
 */
private fun String.stripApiMessageIndex(): String =
    replaceFirst(Regex("^\\s*\\[\\d+✦\\]\\s*"), "")

/** 本地角色 → OpenAI 协议角色名 */
private fun MessageRole.toApiRole(): String = when (this) {
    MessageRole.USER -> "user"
    MessageRole.AI -> "assistant"
}

/**
 * 记忆子代理的结构化输出：总结正文 + 覆盖性检索关键词 + 重要性打分。
 *
 * @param keywords 每条是一个可独立定位该记忆的钩子（覆盖总结所有要点）；
 *   为空表示模型没给关键词，交由仓库用兜底派生。
 * @param importance 0~1，主代理透传或子代理自评；解析失败用默认 0.5
 */
private data class SummaryOutput(
    val summary: String,
    val keywords: List<String>,
    val importance: Float = 0.5f
)

/** 解析子代理输出：优先取 `<summary>`/`<keywords>`/`<importance>`；模型没按格式时整段视为摘要 */
private fun parseSummaryOutput(raw: String): SummaryOutput {
    val (hasSummary, summaryText) = extractTag(raw, "<summary>", "</summary>")
    val (hasKeywords, keywordsText) = extractTag(raw, "<keywords>", "</keywords>")
    val (hasImportance, importanceText) = extractTag(raw, "<importance>", "</importance>")
    val importance = if (hasImportance) {
        importanceText.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
    } else 0.5f
    return if (hasSummary) {
        SummaryOutput(
            summary = summaryText,
            keywords = if (hasKeywords) splitKeywords(keywordsText) else emptyList(),
            importance = importance
        )
    } else {
        SummaryOutput(raw.trim(), emptyList(), importance)
    }
}

/** 提取首对标记之间的内容；标记不完整返回 found=false */
private fun extractTag(raw: String, open: String, close: String): Pair<Boolean, String> {
    val start = raw.indexOf(open)
    if (start < 0) return false to ""
    val end = raw.indexOf(close, start + open.length)
    if (end < 0) return false to ""
    return true to raw.substring(start + open.length, end).trim()
}

/** 按中/英文分号拆关键词列表 */
private fun splitKeywords(raw: String): List<String> = raw
    .split(Regex("[；;]"))
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
