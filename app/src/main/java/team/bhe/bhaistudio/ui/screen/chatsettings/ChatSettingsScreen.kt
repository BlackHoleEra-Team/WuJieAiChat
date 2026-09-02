package team.bhe.bhaistudio.ui.screen.chatsettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.ai.model.ContextUsage

/**
 * 对话设置页——聊天页 ⋮ 进入，标题 <Name> - 设置
 *
 * 与「角色编辑」（通讯录进入）是两码事：
 * 这里是**对话**的设置，承载上下文窗口占用、压缩入口与总 token 统计。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    contactId: String,
    onBack: () -> Unit,
    onOpenChatLogs: () -> Unit
) {
    val vm: ChatSettingsViewModel = koinViewModel()
    val contact by vm.contact.collectAsStateWithLifecycle()
    val usage by vm.contextUsage.collectAsStateWithLifecycle()
    val compressing by vm.compressing.collectAsStateWithLifecycle()
    val compressResult by vm.compressResult.collectAsStateWithLifecycle()
    val totalTokens by vm.totalTokens.collectAsStateWithLifecycle()

    LaunchedEffect(contactId) { vm.initialize(contactId) }

    var showCompressDialog by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(compressing) {
        if (compressing) showCompressDialog = true
    }
    LaunchedEffect(compressResult) {
        when (compressResult) {
            true -> {
                showCompressDialog = false
                showSuccess = true
            }
            false -> showCompressDialog = false
            null -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.chat_settings_title_format,
                            contact?.name ?: stringResource(R.string.chat_title_default)
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 上下文窗口占用
            ContextUsageCard(
                usage = usage,
                onCompress = { vm.compress() }
            )

            // 总消耗统计
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.chat_settings_token_stats),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.chat_settings_token_total, formatTokens(totalTokens)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.chat_settings_token_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 聊天记录管理入口
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenChatLogs)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.chat_settings_logs_entry), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.chat_settings_logs_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showCompressDialog) {
        CompressDialog(onDismiss = { showCompressDialog = false })
    }
    if (showSuccess) {
        AlertDialog(
            onDismissRequest = { showSuccess = false },
            title = { Text(stringResource(R.string.chat_settings_compress_success_title)) },
            text = { Text(stringResource(R.string.chat_settings_compress_success_body)) },
            confirmButton = {
                TextButton(onClick = { showSuccess = false }) {
                    Text(stringResource(R.string.chat_settings_got_it))
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────
// 上下文占用卡片
// ─────────────────────────────────────────────────────────

private val WarnOrange = Color(0xFFF57C00)
private const val WARN_RATIO = 0.6f
private const val CRITICAL_RATIO = 0.8f

/**
 * 上下文窗口占用卡片：
 * MD3E 波浪进度条，快满时容器变色（橙 → 红）并给出警告，下方压缩按钮。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContextUsageCard(
    usage: ContextUsage,
    onCompress: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val ratio = usage.ratio
    val barColor = when {
        ratio >= CRITICAL_RATIO -> scheme.error
        ratio >= WARN_RATIO -> WarnOrange
        else -> scheme.primary
    }
    val containerColor = when {
        ratio >= CRITICAL_RATIO -> scheme.errorContainer.copy(alpha = 0.35f)
        ratio >= WARN_RATIO -> WarnOrange.copy(alpha = 0.10f)
        else -> scheme.surfaceContainerHigh
    }
    val statusText = when {
        ratio >= CRITICAL_RATIO -> stringResource(R.string.chat_settings_context_status_critical)
        ratio >= WARN_RATIO -> stringResource(R.string.chat_settings_context_status_warn)
        else -> stringResource(R.string.chat_settings_context_status_ok)
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.chat_settings_context_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ratio >= WARN_RATIO) barColor else scheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(ratio * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = barColor
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearWavyProgressIndicator(
                progress = { ratio },
                color = barColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.chat_settings_context_used,
                    formatTokens(usage.used.toLong()),
                    formatTokens(usage.total.toLong())
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = barColor
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onCompress,
                modifier = Modifier.align(Alignment.End)
            ) { Text(stringResource(R.string.chat_settings_compress_action)) }
        }
    }
}

/** 压缩进行中的 Dialog：Expressive 圆形加载 + 取消 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CompressDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LoadingIndicator(modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.chat_settings_compressing_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.chat_settings_compressing_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    }
}

/** token 数字美化：1200 → 1.2k */
private fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> "${"%.1f".format(value / 1_000_000.0)}M"
    value >= 1_000 -> "${"%.1f".format(value / 1_000.0)}k"
    else -> "$value"
}
