package team.bhe.bhaistudio.ui.screen.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.data.db.entity.MemoryEntity
import team.bhe.bhaistudio.data.db.entity.MemoryState
import team.bhe.bhaistudio.data.db.entity.MemoryType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆管理页——角色记住的短期 / 长期记忆
 *
 * 短期（[MemoryType.SHORT_TERM]）：近期的临时事项，时效性强
 * 长期（[MemoryType.LONG_TERM]）：稳定的跨会话信息，陪伴感的核心
 * 两层都不会自动删除，区分的是信息性质而非存储策略。
 *
 * 生成方式：
 *   · 手动 —— 聊天页 AI 消息 ⋮ →「存为记忆」
 *   · 自动 —— 角色自主判断重要事件（save_memory 工具）静默总结
 */
@Composable
fun MemoryScreen(
    contactId: String,
    onBack: () -> Unit
) {
    val vm: MemoryViewModel = koinViewModel()
    val memories by vm.memories.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(MemoryTab.ALL) }

    // Navigation3 不自动填充 SavedStateHandle，必须显式传入 contactId
    LaunchedEffect(contactId) { vm.initialize(contactId) }

    val visible = when (selectedTab) {
        MemoryTab.ALL -> memories
        MemoryTab.SHORT -> memories.filter { it.memoryType == MemoryType.SHORT_TERM }
        MemoryTab.LONG -> memories.filter { it.memoryType == MemoryType.LONG_TERM }
    }

    // 搜索：本地过滤正文/索引（记忆页的检索入口，数据层 search API 供后续 recall 工具用）
    var query by remember { mutableStateOf("") }
    val keyword = query.trim()
    val shown = if (keyword.isEmpty()) {
        visible
    } else {
        visible.filter {
            it.summary.contains(keyword, ignoreCase = true) ||
                it.indexSummary.contains(keyword, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.memory_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    if (visible.isNotEmpty()) {
                        IconButton(onClick = { vm.clearAll() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.memory_clear_all_desc),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 全部 / 短期 / 长期 标签页
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                MemoryTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(stringResource(tab.labelRes)) }
                    )
                }
            }

            // 有记忆时才显示搜索框
            if (visible.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.memory_search_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            when {
                // 有记忆但搜索结果为空
                keyword.isNotEmpty() && shown.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.memory_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                shown.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val emptyText = if (selectedTab == MemoryTab.ALL) {
                        stringResource(R.string.memory_empty_none)
                    } else {
                        stringResource(
                            R.string.memory_empty_format,
                            stringResource(selectedTab.labelRes)
                        )
                    }
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown, key = { it.id }) { memory ->
                        MemoryCard(memory = memory, onDelete = { vm.delete(memory.id) })
                    }
                }
            }
        }
    }
}

/** 记忆页标签页：全部 / 短期 / 长期 */
private enum class MemoryTab(val labelRes: Int) {
    ALL(R.string.memory_tab_all),
    SHORT(R.string.memory_tab_short),
    LONG(R.string.memory_tab_long)
}

@Composable
private fun MemoryCard(
    memory: MemoryEntity,
    onDelete: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 短期/长期标签
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (memory.memoryType == MemoryType.SHORT_TERM) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Text(
                        text = if (memory.memoryType == MemoryType.SHORT_TERM) {
                            stringResource(R.string.memory_tab_short)
                        } else {
                            stringResource(R.string.memory_tab_long)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (memory.memoryType == MemoryType.SHORT_TERM) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 状态徽标：活跃（会被自动想起）/ 沉睡（平时不可及，可被回忆捞回）
                val stateActive = memory.state == MemoryState.ACTIVE
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (stateActive) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = stringResource(
                            if (stateActive) R.string.memory_state_active else R.string.memory_state_inactive
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stateActive) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatDate(memory.createTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.memory_delete_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 说明：indexSummary（一句话索引）不在卡片上重复展示——
            // 它是长记忆首行的截断，显示出来会和正文第一行"撞车"看着像重复。
            // 索引只用于记忆页搜索与后续的索引注入/按需调取。
            Text(
                text = memory.summary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.memory_importance_line,
                    (memory.importance * 100).toInt().coerceIn(0, 100)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatDate(timestamp: Long): String = dateFormat.format(Date(timestamp))
