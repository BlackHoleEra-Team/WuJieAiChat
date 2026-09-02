package team.bhe.bhaistudio.ui.screen.chatsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.data.db.entity.MessageEntity
import team.bhe.bhaistudio.data.db.entity.MessageRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天记录管理页
 *
 * 对话设置 → 聊天记录管理：
 *   1. 统计概览（消息数 / 时间跨度 / 估算 token / 估算大小，实时更新）
 *   2. 浏览全部聊天记录（无限分页，可单条 / 整轮删除）
 *   3. 导出为 Markdown 或 JSON（系统分享面板）
 *   4. 清空聊天记录（红色危险操作，二次确认）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatLogsScreen(
    contactId: String,
    onBack: () -> Unit
) {
    val vm: ChatLogsViewModel = koinViewModel()
    val contact by vm.contact.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val lazyItems = vm.messages.collectAsLazyPagingItems()

    LaunchedEffect(contactId) { vm.initialize(contactId) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            vm.consumeNotice()
        }
    }

    var showClearDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<MessageEntity?>(null) }
    val empty = (stats?.totalCount ?: 0) == 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chatlogs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatsCard(name = contact?.name.orEmpty(), stats = stats) }

            item {
                ExportCard(
                    enabled = !empty && !busy,
                    busy = busy,
                    onExportMarkdown = vm::exportMarkdown,
                    onExportJson = vm::exportJson
                )
            }

            item {
                ClearCard(
                    enabled = !empty,
                    onClick = { showClearDialog = true }
                )
            }

            item {
                Text(
                    stringResource(R.string.chatlogs_browse_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (empty) {
                item {
                    Text(
                        stringResource(R.string.chatlogs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(
                    count = lazyItems.itemCount,
                    key = { index -> lazyItems.peek(index)?.id ?: "log-$index" }
                ) { index ->
                    val msg = runCatching { lazyItems.peek(index) }.getOrNull() ?: return@items
                    LogRow(
                        msg = msg,
                        aiName = contact?.name ?: stringResource(R.string.chatlogs_ai_fallback),
                        onDelete = { deleteTarget = msg }
                    )
                }
            }
        }
    }

    // 单条删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.chatlogs_delete_record_title)) },
            text = {
                Text(
                    if (!target.replyGroupId.isNullOrBlank())
                        stringResource(R.string.chatlogs_delete_group_body)
                    else stringResource(R.string.chatlogs_delete_single_body)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteMessage(target.id, target.replyGroupId)
                        deleteTarget = null
                    }
                ) {
                    Text(stringResource(R.string.chatlogs_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // 清空全部确认
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.chatlogs_clear_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.chatlogs_clear_body,
                        contact?.name.orEmpty(),
                        stats?.totalCount ?: 0
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        vm.clearAll()
                    }
                ) {
                    Text(stringResource(R.string.chatlogs_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────
// 统计卡片
// ─────────────────────────────────────────────────────────

@Composable
private fun StatsCard(name: String, stats: ChatLogsViewModel.ChatLogStats?) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chatlogs_stats_title_format, name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(12.dp))

            val stats = stats
            if (stats == null || stats.totalCount == 0) {
                Text(
                    stringResource(R.string.chatlogs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            StatRow(
                stringResource(R.string.chatlogs_stat_total_label),
                stringResource(
                    R.string.chatlogs_stat_total_value,
                    stats.totalCount,
                    stats.userCount,
                    name,
                    stats.aiCount
                )
            )
            StatRow(
                stringResource(R.string.chatlogs_stat_span_label),
                if (stats.earliestAt != null && stats.latestAt != null) {
                    stringResource(
                        R.string.chatlogs_stat_span_value,
                        chatLogTime.format(Date(stats.earliestAt)),
                        chatLogTime.format(Date(stats.latestAt))
                    )
                } else "—"
            )
            StatRow(
                stringResource(R.string.chatlogs_stat_context_label),
                "${formatTokens(stats.estimatedTokens)} tokens"
            )
            StatRow(stringResource(R.string.chatlogs_stat_size_label), formatBytes(stats.approxSize))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// ─────────────────────────────────────────────────────────
// 单条记录行（可分页浏览 / 删除）
// ─────────────────────────────────────────────────────────

@Composable
private fun LogRow(
    msg: MessageEntity,
    aiName: String,
    onDelete: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (msg.role == MessageRole.USER) stringResource(R.string.chatlogs_role_you) else aiName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = chatLogTime.format(Date(msg.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = msg.content.stripLogIndex(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.chatlogs_delete_desc),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 浏览列表里剥掉开头的消息编号 `[N✦]` */
private fun String.stripLogIndex(): String =
    replaceFirst(Regex("^\\s*\\[\\d+✦\\]\\s*"), "")

// ─────────────────────────────────────────────────────────
// 导出卡片
// ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExportCard(
    enabled: Boolean,
    busy: Boolean,
    onExportMarkdown: () -> Unit,
    onExportJson: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chatlogs_export_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.chatlogs_export_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.chatlogs_export_busy), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = onExportMarkdown,
                        enabled = enabled
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Markdown")
                    }
                    FilledTonalButton(
                        onClick = onExportJson,
                        enabled = enabled
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("JSON")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
// 清空卡片（危险）
// ─────────────────────────────────────────────────────────

@Composable
private fun ClearCard(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chatlogs_danger_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.chatlogs_clear_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onClick,
                enabled = enabled
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chatlogs_clear_action), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
// 格式化
// ─────────────────────────────────────────────────────────

private val chatLogTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> "${"%.1f".format(value / 1_000_000.0)}M"
    value >= 1_000 -> "${"%.1f".format(value / 1_000.0)}k"
    else -> "$value"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024 -> "${"%.1f".format(bytes / 1_024.0)} KB"
    else -> "$bytes B"
}
