package team.bhe.bhaistudio.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.bhe.bhaistudio.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 会话列表（"聊天"Tab）
 *
 * 大圆头像、名字加粗、上方右侧时间，下方单行预览（与 iOS 信息列表同构）。
 */
@Composable
fun ChatListScreen(
    onOpenChat: (contactId: String) -> Unit,
    onAddContact: () -> Unit,
    onScan: () -> Unit
) {
    val vm: ChatListViewModel = koinViewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.common_more)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.fab_add_contact)) },
                            onClick = {
                                menuExpanded = false
                                onAddContact()
                            },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_scan)) },
                            onClick = {
                                menuExpanded = false
                                onScan()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            EmptyHint(stringResource(R.string.home_empty))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items, key = { it.conversation.id }) { item ->
                    ConversationCard(
                        item = item,
                        onClick = {
                            item.conversation.pinnedContactId?.let(onOpenChat)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationCard(
    item: ConversationItem,
    onClick: () -> Unit
) {
    val conv = item.conversation
    val scheme = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        color = scheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 大圆头像：custom image 或首字
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(scheme.primaryContainer)
            ) {
                if (!item.avatarUri.isNullOrBlank()) {
                    AsyncImage(
                        model = item.avatarUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = (item.contactName ?: conv.title).take(1).ifBlank { "?" },
                            style = MaterialTheme.typography.titleLarge,
                            color = scheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            // 名字 + 预览；上方右侧时间
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.contactName ?: conv.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (conv.lastMessageAt > 0) {
                        Text(
                            text = formatMessageTime(conv.lastMessageAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.padding(2.dp))
                Text(
                    text = conv.lastMessage.ifBlank { stringResource(R.string.chat_preview_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 列表时间戳格式化：今天 / 昨天 / 星期 / 月日 / 年月日。
 */
@Composable
private fun formatMessageTime(timestamp: Long): String {
    val now = LocalDateTime.now()
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
    val today = LocalDate.now()
    val msgDate = time.toLocalDate()
    val days = ChronoUnit.DAYS.between(msgDate, today)
    return when {
        days == 0L -> stringResource(R.string.time_today)
        days == 1L -> stringResource(R.string.time_yesterday)
        days in 2L..6L -> time.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        msgDate.year == today.year -> "%02d-%02d".format(msgDate.monthValue, msgDate.dayOfMonth)
        else -> "%d-%02d-%02d".format(msgDate.year, msgDate.monthValue, msgDate.dayOfMonth)
    }
}
