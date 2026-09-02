package team.bhe.bhaistudio.ui.screen.chatsettings

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.data.db.dao.MessageDao
import team.bhe.bhaistudio.data.db.entity.ContactEntity
import team.bhe.bhaistudio.data.db.entity.MessageEntity
import team.bhe.bhaistudio.data.db.entity.MessageRole
import team.bhe.bhaistudio.data.repository.ChatRepository
import team.bhe.bhaistudio.data.repository.ContactRepository
import team.bhe.bhaistudio.data.repository.ConversationRepository
import team.bhe.bhaistudio.data.repository.withAppLanguage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天记录管理页
 *
 * 面向单个对话：实时统计 + 无限分页浏览全部记录（可单条/整轮删除）+ 导出 + 清空。
 * 导出文件写入缓存目录，通过 FileProvider 交给系统分享面板。
 */
class ChatLogsViewModel(
    application: Application,
    private val messageDao: MessageDao,
    private val chatRepository: ChatRepository,
    private val contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository
) : AndroidViewModel(application) {

    /** 会话统计概览 */
    data class ChatLogStats(
        val totalCount: Int,
        val userCount: Int,
        val aiCount: Int,
        val earliestAt: Long? = null,
        val latestAt: Long? = null,
        val estimatedTokens: Long = 0,
        val approxSize: Long = 0
    )

    private var initialized = false
    private val _conversationId = MutableStateFlow("")

    private val _contact = MutableStateFlow<ContactEntity?>(null)
    val contact: StateFlow<ContactEntity?> = _contact.asStateFlow()

    /** 全部聊天记录（最新在前）的无限分页流 */
    val messages: Flow<PagingData<MessageEntity>> = _conversationId
        .filter { it.isNotBlank() }
        .flatMapLatest { id ->
            Pager(PagingConfig(pageSize = 30, prefetchDistance = 10)) {
                messageDao.pagingByConversation(id)
            }.flow
        }
        .cachedIn(viewModelScope)

    /** 统计：实时观察消息变化（聊完回到本页自动更新） */
    val stats: StateFlow<ChatLogStats?> = _conversationId
        .filter { it.isNotBlank() }
        .flatMapLatest { id ->
            messageDao.observeByConversation(id).map { msgs -> buildStats(_contact.value, id, msgs) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 一次性提示（导出失败 / 已删除 / 已清空等） */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

    fun initialize(contactId: String) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val contact = contactRepository.getById(contactId) ?: return@launch
            _contact.value = contact
            _conversationId.value = conversationRepository.getOrCreateSingle(contactId, contact.name).id
        }
    }

    private suspend fun buildStats(
        contact: ContactEntity?,
        conversationId: String,
        messages: List<MessageEntity>
    ): ChatLogStats {
        val usage = contact?.let { chatRepository.estimateContextUsage(it, conversationId) }
        return ChatLogStats(
            totalCount = messages.size,
            userCount = messages.count { it.role == MessageRole.USER },
            aiCount = messages.count { it.role == MessageRole.AI },
            earliestAt = messages.minOfOrNull { it.createdAt },
            latestAt = messages.maxOfOrNull { it.createdAt },
            estimatedTokens = usage?.used?.toLong() ?: 0L,
            approxSize = messages.sumOf {
                (it.content.length + (it.thinkingContent?.length ?: 0)).toLong()
            }
        )
    }

    // ── 删除 ──

    /** 删除一条记录；分段回复按整轮（replyGroupId）删除 */
    fun deleteMessage(id: String, replyGroupId: String?) {
        viewModelScope.launch {
            if (!replyGroupId.isNullOrBlank()) {
                messageDao.deleteByReplyGroup(replyGroupId)
            } else {
                messageDao.deleteById(id)
            }
            _notice.value = getApplication<Application>().withAppLanguage().getString(R.string.chatlogs_deleted)
        }
    }

    // ── 导出 ──

    fun exportMarkdown() = export(getApplication<Application>().withAppLanguage().getString(R.string.chatlogs_file_md)) { app, contact, messages ->
        buildMarkdown(app, contact, messages)
    }

    fun exportJson() = export(getApplication<Application>().withAppLanguage().getString(R.string.chatlogs_file_json)) { app, contact, messages ->
        buildJson(contact, messages)
    }

    private fun export(
        fileName: String,
        builder: (Context, ContactEntity, List<MessageEntity>) -> String
    ) {
        val contact = _contact.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val ok = runCatching {
                val app = getApplication<Application>()
                val messages = messageDao.listByConversation(_conversationId.value)
                val dir = File(app.cacheDir, "export").apply { mkdirs() }
                val file = File(dir, "${System.currentTimeMillis()}_$fileName")
                file.writeText(builder(app, contact, messages), Charsets.UTF_8)
                share(file)
            }.isSuccess
            _busy.value = false
            if (!ok) _notice.value = getApplication<Application>().withAppLanguage().getString(R.string.chatlogs_export_failed)
        }
    }

    /** 通过系统分享面板导出文件（保存到文件 / 发送到其他应用） */
    private fun share(file: File) {
        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "json") "application/json" else "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            intent,
            context.getString(R.string.chatlogs_export_title)
        )
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    // ── 清空 ──

    /** 清空该对话全部消息与会话预览。不可恢复，UI 需二次确认。 */
    fun clearAll() {
        viewModelScope.launch {
            messageDao.deleteByConversation(_conversationId.value)
            // 清空列表页残留的预览文本和最近时间
            conversationRepository.clearPreview(_conversationId.value)
            _notice.value = getApplication<Application>().withAppLanguage().getString(R.string.chatlogs_cleared)
        }
    }
}

// ─────────────────────────────────────────────────────────
// 导出格式构建
// ─────────────────────────────────────────────────────────

private val exportTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/** Markdown：适合阅读与归档，带时间戳与思考过程 */
private fun buildMarkdown(
    context: Context,
    contact: ContactEntity,
    messages: List<MessageEntity>
): String = buildString {
    appendLine(context.getString(R.string.chatlogs_md_heading, contact.name))
    appendLine()
    appendLine(
        context.getString(R.string.chatlogs_md_exported, exportTimeFormat.format(Date()))
    )
    if (contact.systemPrompt.isNotBlank()) {
        appendLine(
            context.getString(
                R.string.chatlogs_md_persona,
                contact.systemPrompt.replace('\n', ' ')
            )
        )
    }
    appendLine(context.getString(R.string.chatlogs_md_count, messages.size))
    appendLine()
    appendLine("---")
    appendLine()
    messages.forEach { msg ->
        val role = if (msg.role == MessageRole.USER) {
            context.getString(R.string.chatlogs_role_user)
        } else {
            contact.name
        }
        val time = exportTimeFormat.format(Date(msg.createdAt))
        appendLine("**$role** · $time")
        appendLine()
        appendLine(msg.content.trim())
        appendLine()
        if (!msg.thinkingContent.isNullOrBlank()) {
            appendLine("<details>")
            appendLine("<summary>${context.getString(R.string.chatlogs_thinking_summary)}</summary>")
            appendLine()
            appendLine(msg.thinkingContent.trim())
            appendLine()
            appendLine("</details>")
            appendLine()
        }
    }
    append(context.getString(R.string.chatlogs_md_end))
}

private val exportJson = Json { prettyPrint = true }

/** JSON：结构化备份，可读/可恢复 */
private fun buildJson(contact: ContactEntity, messages: List<MessageEntity>): String {
    val root = buildJsonObject {
        put("app", "无界 AI")
        put("version", 1)
        put("exportedAt", System.currentTimeMillis())
        put("contact", buildJsonObject {
            put("id", contact.id)
            put("name", contact.name)
            put("model", contact.model)
            put("systemPrompt", contact.systemPrompt)
        })
        put("messages", buildJsonArray {
            messages.forEach { msg ->
                add(buildJsonObject {
                    put("role", msg.role.name.lowercase())
                    put("content", msg.content)
                    put("createdAt", msg.createdAt)
                    msg.thinkingContent?.let { put("thinking", it) }
                })
            }
        })
    }
    return exportJson.encodeToString(root)
}
