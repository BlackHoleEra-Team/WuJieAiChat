package team.bhe.bhaistudio.ui.screen.recycled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.ui.screen.recycled.RecycledViewModel.Item
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拾忆页——被遗忘/删除的记忆统一存放在这里。
 *
 * · 每条显示来自哪个角色、内容、进拾忆时间
 * · 找回 = 恢复回活跃（重新计时）
 * · 自动清空时间可设置（默认 30 天），也可立即清空
 */
@Composable
fun RecycledScreen(onBack: () -> Unit) {
    val vm: RecycledViewModel = koinViewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    val retentionDays by vm.retentionDays.collectAsStateWithLifecycle()

    var showClearConfirm by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recycle_title)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.recycle_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 自动清空时间设置行
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.recycle_auto_clear),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.width(0.dp))
                    Text(
                        text = stringResource(R.string.recycle_auto_clear_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { showRetentionDialog = true }) {
                        Text(
                            text = if (retentionDays <= 0) {
                                stringResource(R.string.recycle_auto_clear_never)
                            } else {
                                stringResource(R.string.recycle_auto_clear_days, retentionDays)
                            },
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            when {
                items.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.recycle_empty),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.padding(8.dp))
                    Text(
                        text = stringResource(R.string.recycle_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.memory.id }) { item ->
                        RecycledCard(item = item, onRestore = { vm.restore(item.memory.id) })
                    }
                }
            }

            if (items.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.recycle_clear_all))
                }
            }
        }
    }

    if (showRetentionDialog) {
        RetentionDialog(
            current = retentionDays,
            onPick = { days ->
                vm.setRetentionDays(days)
                showRetentionDialog = false
            },
            onDismiss = { showRetentionDialog = false }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.recycle_clear_confirm_title)) },
            text = { Text(stringResource(R.string.recycle_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    showClearConfirm = false
                }) {
                    Text(
                        stringResource(R.string.recycle_confirm_clear),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.transfer_cancel))
                }
            }
        )
    }
}

@Composable
private fun RecycledCard(item: Item, onRestore: () -> Unit) {
    val memory = item.memory
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.summary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    text = "${item.contactName} · ${formatDate(memory.recycledAt ?: memory.createTime)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onRestore) {
                Icon(
                    Icons.Default.Restore,
                    contentDescription = stringResource(R.string.recycle_restore),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun RetentionDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(7, 30, 90, 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recycle_auto_clear)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { days ->
                    TextButton(onClick = { onPick(days) }) {
                        Text(
                            text = if (days <= 0) {
                                stringResource(R.string.recycle_auto_clear_never)
                            } else {
                                stringResource(R.string.recycle_auto_clear_days, days)
                            },
                            color = if (days == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.transfer_cancel))
            }
        }
    )
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatDate(timestamp: Long): String = dateFormat.format(Date(timestamp))
