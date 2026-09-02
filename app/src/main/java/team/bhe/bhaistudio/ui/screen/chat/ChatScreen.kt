package team.bhe.bhaistudio.ui.screen.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.data.db.entity.MessageRole
import team.bhe.bhaistudio.ui.component.ChatAvatar
import team.bhe.bhaistudio.ui.component.ChatBubble
import team.bhe.bhaistudio.ui.component.ReasoningBubble
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 去掉开头的消息编号 `[N✦] `——这是发给 AI 用于 save_memory 指定范围的内部标记，不该给用户看。
 * ✦ 是冷门标记，只匹配内部编号格式，不误伤用户/模型正常输出的 `[数字]` 文本。
 */
private fun String.stripMessageIndex(): String =
    replaceFirst(Regex("^\\s*\\[\\d+✦\\]\\s*"), "")

/**
 * 聊天页
 *
 * 消息列表只认数据库（Room → Paging → LazyColumn），
 * 流式/分段期间的"临时内容"由 [ChatUiState] 驱动，完成后落库并消失。
 *
 * ── 渲染顺序（reverseLayout=true，index 0 在底部）──────────
 * 1. 流式临时消息（最新，贴底）
 * 2. "正在输入"气泡
 * 3. 数据库消息（最新在前）
 *
 * 分段模式下：每段落库 → Room Flow 刷新 → 自动出现在列表，
 * 段间 [TypingBubble] 由 isTyping 驱动。
 */
@Composable
fun ChatScreen(
    contactId: String,
    onBack: () -> Unit,
    onOpenSettings: (contactId: String) -> Unit,
    onOpenProviders: () -> Unit
) {
    val vm: ChatViewModel = koinViewModel()
    val contact by vm.contact.collectAsStateWithLifecycle()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val exhausted by vm.exhausted.collectAsStateWithLifecycle()
    val lazyItems = vm.messages.collectAsLazyPagingItems()
    val userAvatarUri by vm.userAvatarUri.collectAsStateWithLifecycle()
    val userNickname by vm.userNickname.collectAsStateWithLifecycle()

    // Navigation3 不自动填充 SavedStateHandle，必须显式把 contactId 传给 ViewModel
    LaunchedEffect(contactId) { vm.initialize(contactId) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }
    // 一次性事件（存记忆结果等）
    LaunchedEffect(Unit) {
        vm.events.collect { snackbar.showSnackbar(it) }
    }

    // ── 上下文耗尽弹窗（上下文数据在「对话设置」页）──
    var showExhaustedDialog by remember { mutableStateOf(false) }
    LaunchedEffect(exhausted) {
        if (exhausted) {
            showExhaustedDialog = true
            vm.consumeExhausted()
        }
    }

    // ── 服务商拦截弹窗：未设密钥 / 不可用 → 指引前往服务商管理 ──
    val apiBlock by vm.apiBlock.collectAsStateWithLifecycle()
    apiBlock?.let { block ->
        AlertDialog(
            onDismissRequest = { vm.dismissApiBlock() },
            title = {
                Text(
                    if (block.reason == ApiBlockReason.NO_KEY)
                        stringResource(R.string.chat_api_block_title_no_key)
                    else
                        stringResource(R.string.chat_api_block_title_unavailable)
                )
            },
            text = {
                Text(
                    if (block.reason == ApiBlockReason.NO_KEY)
                        stringResource(
                            R.string.chat_api_block_body_no_key,
                            contact?.name.orEmpty(),
                            block.providerName
                        )
                    else
                        stringResource(R.string.chat_api_block_body_unavailable, block.providerName)
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissApiBlock() }) { Text(stringResource(R.string.common_cancel)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.dismissApiBlock()
                    onOpenProviders()
                }) { Text(stringResource(R.string.common_go_settings)) }
            }
        )
    }

    Scaffold(
        // 输入法适配：键盘弹起时整个页面（含底部输入栏）随键盘上移，
        // 消息列表高度同步收缩，最新消息仍贴底可见
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    // 等待回复时标题栏显示"正在输入…"，替代消息列表里的气泡
                    val waiting = uiState.isTyping &&
                        uiState.streamingText.isBlank() &&
                        uiState.streamingThinking.isBlank()
                    Text(
                        if (waiting) stringResource(R.string.chat_typing)
                        else contact?.name ?: stringResource(R.string.chat_title_default)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenSettings(contactId) }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_open_settings))
                    }
                }
            )
        },
        bottomBar = {
            Column {
                AnimatedVisibility(visible = uiState.memorySave != null) {
                    MemorySaveBar(uiState.memorySave)
                }
                ChatInputBar(
                    isSending = uiState.isSending,
                    onSend = { vm.sendMessage(it) }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        MessageList(
            vm = vm,
            uiState = uiState,
            contact = contact,
            userAvatarUri = userAvatarUri,
            userNickname = userNickname,
            lazyItemCount = lazyItems.itemCount,
            lazyItemAt = { index -> lazyItems.peek(index) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }

    if (showExhaustedDialog) {
        AlertDialog(
            onDismissRequest = { showExhaustedDialog = false },
            title = { Text(stringResource(R.string.chat_context_exhausted_title)) },
            text = { Text(stringResource(R.string.chat_context_exhausted_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showExhaustedDialog = false
                    onOpenSettings(contactId)
                }) { Text(stringResource(R.string.chat_go_compress)) }
            },
            dismissButton = {
                TextButton(onClick = { showExhaustedDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────
// 消息列表
// ─────────────────────────────────────────────────────────

@Composable
private fun MessageList(
    vm: ChatViewModel,
    uiState: ChatUiState,
    contact: team.bhe.bhaistudio.data.db.entity.ContactEntity?,
    userAvatarUri: String,
    userNickname: String,
    lazyItemCount: Int,
    lazyItemAt: (Int) -> team.bhe.bhaistudio.data.db.entity.MessageEntity?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    // AI 开始回复时滚到底，之后跟着文本增长持续跟随
    LaunchedEffect(uiState.isTyping) {
        if (uiState.isTyping) listState.animateScrollToItem(0)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { uiState.streamingText.length }.collect { len ->
            if (len > 0 && uiState.isTyping) listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // 1. 流式临时消息（贴底）
        if (uiState.streamingText.isNotBlank() || uiState.streamingThinking.isNotBlank()) {
            item(key = "streaming") {
                Column {
                    if (uiState.streamingThinking.isNotBlank()) {
                        // 思考与"正在输入"融合：默认折叠；思考中显示「正在思考…」+加载动画，
                        // 开始输出正文后变为「已完成思考 (Xs)」。点击标题可展开查看流式思考内容
                        ReasoningBubble(
                            text = uiState.streamingThinking,
                            initiallyExpanded = false,
                            thinkingDone = uiState.streamingText.isNotBlank(),
                            elapsedSeconds = uiState.thinkingElapsedSec
                        )
                    }
                    if (uiState.streamingText.isNotBlank()) {
                        ChatBubble(
                            text = uiState.streamingText.stripMessageIndex(),
                            isFromUser = false,
                            time = "",
                            avatar = ChatAvatar(contact?.avatarUri, contact?.name ?: stringResource(R.string.chat_name_fallback_ai))
                        )
                    }
                }
            }
        }

        // 3. 数据库消息（Paging 反向加载）
        items(
            count = lazyItemCount,
            key = { index -> runCatching { lazyItemAt(index)?.id }.getOrNull() ?: "msg-$index" }
        ) { index ->
            // Paging 快照在发送/刷新瞬间可能小于 itemCount，越界访问会导致闪退
            val msg = runCatching { lazyItemAt(index) }.getOrNull() ?: return@items
            // reverseLayout 下 index+1 是时间上更早的一条；越界时视为不分组
            val prev = runCatching { lazyItemAt(index + 1) }.getOrNull()
            val grouped = prev?.role == msg.role

            when (msg.role) {
                MessageRole.USER -> Row(verticalAlignment = Alignment.Top) {
                    MessageActions(
                        isLatest = false,
                        onCopy = {
                            copyToClipboard(context, msg.content.stripMessageIndex())
                        }
                    )
                    ChatBubble(
                        text = msg.content.stripMessageIndex(),
                        isFromUser = true,
                        time = formatTime(msg.createdAt),
                        isGrouped = grouped,
                        modifier = Modifier.weight(1f),
                        avatar = ChatAvatar(userAvatarUri, userNickname.ifBlank { stringResource(R.string.chat_name_fallback_me) })
                    )
                }

                MessageRole.AI -> Column {
                    if (!msg.thinkingContent.isNullOrBlank()) {
                        ReasoningBubble(msg.thinkingContent)
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        ChatBubble(
                            text = msg.content.stripMessageIndex(),
                            isFromUser = false,
                            time = formatTime(msg.createdAt),
                            isGrouped = grouped,
                            modifier = Modifier.weight(1f),
                            avatar = ChatAvatar(contact?.avatarUri, contact?.name ?: stringResource(R.string.chat_name_fallback_ai))
                        )
                        // 消息操作：复制 / 复制思考 / 存为记忆（所有 AI 消息）/ 重新生成（仅最新一轮）
                        MessageActions(
                            isLatest = index == 0,
                            onCopy = {
                                copyToClipboard(context, msg.content.stripMessageIndex())
                            },
                            onCopyThinking = msg.thinkingContent
                                ?.takeIf { it.isNotBlank() }
                                ?.let { thinking -> { copyToClipboard(context, thinking) } },
                            onSaveMemory = vm::saveRecentAsMemory,
                            onRegenerate = vm::regenerate
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
// 消息操作菜单
// ─────────────────────────────────────────────────────────

/**
 * 消息操作菜单：复制（用户/AI 消息都可用）；AI 消息另有 存为记忆 / 重新生成（仅最新一轮）。
 */
@Composable
private fun MessageActions(
    isLatest: Boolean,
    onCopy: () -> Unit,
    onCopyThinking: (() -> Unit)? = null,
    onSaveMemory: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.chat_message_actions_desc),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_copy)) },
                onClick = {
                    menuOpen = false
                    onCopy()
                }
            )
            if (onCopyThinking != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_copy_thinking)) },
                    onClick = {
                        menuOpen = false
                        onCopyThinking()
                    }
                )
            }
            if (onSaveMemory != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_save_as_memory)) },
                    onClick = {
                        menuOpen = false
                        onSaveMemory()
                    }
                )
            }
            if (isLatest && onRegenerate != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_regenerate)) },
                    onClick = {
                        menuOpen = false
                        onRegenerate()
                    }
                )
            }
        }
    }
}

/** 复制消息文本到剪贴板并轻提示 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("wujie_message", text))
    Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
}

// ─────────────────────────────────────────────────────────
// 输入栏
// ─────────────────────────────────────────────────────────

/**
 * 保存记忆的状态提示条（输入框上方）。
 * 进行中：小字 + 进度条；结束后：✓ / ✕ 状态图标。
 */
@Composable
private fun MemorySaveBar(state: MemorySaveUi?) {
    val save = state ?: return
    val scheme = MaterialTheme.colorScheme
    val statusColor = when (save.status) {
        MemorySaveStatus.Running -> scheme.primary
        MemorySaveStatus.Success -> Color(0xFF2E7D32)
        MemorySaveStatus.Failure -> scheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = save.message,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        when (save.status) {
            MemorySaveStatus.Running -> LinearProgressIndicator(
                modifier = Modifier
                    .width(80.dp)
                    .height(4.dp),
                color = statusColor
            )
            MemorySaveStatus.Success -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(18.dp)
            )
            MemorySaveStatus.Failure -> Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    isSending: Boolean,
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                maxLines = 5,
                shape = MaterialTheme.shapes.extraLarge
            )

            IconButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = text.isNotBlank() && !isSending
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send_desc),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String =
    timeFormat.format(Date(timestamp))
