package team.bhe.bhaistudio.ui.screen.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.ui.screen.home.EmptyHint

/**
 * 联系人（AI 角色）列表——"通讯录"Tab
 */
@Composable
fun ContactListScreen(
    onOpenChat: (contactId: String) -> Unit,
    onEdit: (contactId: String) -> Unit,
    onAdd: () -> Unit,
    onMemory: (contactId: String) -> Unit,
    onScan: () -> Unit,
    onImportJson: (String) -> Unit
) {
    val vm: ContactListViewModel = koinViewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    // 滚动状态可保存：切 Tab 再回来不丢位置
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fabExpanded by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }
    var showQrSource by remember { mutableStateOf(false) }
    var multiCards by remember { mutableStateOf<List<String>?>(null) }

    // Toast 文案先在 Composable 上下文解析（协程/lambda 内无法调用 stringResource）
    val msgNoCard = stringResource(R.string.toast_no_card_in_image)
    val msgInvalidCard = stringResource(R.string.toast_invalid_card)
    val msgCopied = stringResource(R.string.toast_copied_json)
    val msgCopyFailed = stringResource(R.string.toast_copy_failed)
    val msgExported = stringResource(R.string.toast_exported)
    val msgExportFailed = stringResource(R.string.toast_export_failed)

    // 相册选图 → 识别角色码（支持一图多码），用官方推荐 Photo Picker
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val cards = decodeContactCardsFromImage(context, uri)
                when {
                    cards.isEmpty() -> Toast.makeText(context, msgNoCard, Toast.LENGTH_SHORT).show()
                    cards.size == 1 -> onImportJson(cards[0])
                    else -> multiCards = cards
                }
            }
        }
    }

    fun handleJsonImport(raw: String) {
        showJsonDialog = false
        val json = raw.trim()
        if (isValidContactJson(json)) {
            onImportJson(json)
        } else {
            Toast.makeText(context, msgInvalidCard, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_title)) },
                actions = {
                    Text(
                        text = stringResource(R.string.contacts_count_format, items.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        },
        floatingActionButton = {
            AddContactFabMenu(
                expanded = fabExpanded,
                onToggle = { fabExpanded = !fabExpanded },
                onManual = {
                    fabExpanded = false
                    onAdd()
                },
                onJson = {
                    fabExpanded = false
                    showJsonDialog = true
                },
                onQr = {
                    fabExpanded = false
                    showQrSource = true
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            EmptyHint(stringResource(R.string.contacts_empty))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.contact.id }) { item ->
                    ContactCard(
                        item = item,
                        onClick = { onOpenChat(item.contact.id) },
                        onEdit = { onEdit(item.contact.id) },
                        onDelete = { vm.delete(item.contact.id) },
                        onMemory = { onMemory(item.contact.id) }
                    )
                }
            }
        }
    }

    if (showJsonDialog) {
        JsonImportDialog(onImport = ::handleJsonImport, onDismiss = { showJsonDialog = false })
    }
    if (showQrSource) {
        QrSourceDialog(
            onScanCamera = {
                showQrSource = false
                onScan()
            },
            onPickImage = {
                showQrSource = false
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onDismiss = { showQrSource = false }
        )
    }
    multiCards?.let { cards ->
        QrCardSelectDialog(
            cards = cards,
            onSelect = { card ->
                multiCards = null
                onImportJson(card)
            },
            onDismiss = { multiCards = null }
        )
    }
}

/** 新建角色 FAB 菜单：手动录入 / JSON / 二维码 */
@Composable
private fun AddContactFabMenu(
    expanded: Boolean,
    onToggle: () -> Unit,
    onManual: () -> Unit,
    onJson: () -> Unit,
    onQr: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 }
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FabMenuItem(stringResource(R.string.add_contact_qr), Icons.Default.QrCodeScanner, onQr)
                FabMenuItem(stringResource(R.string.add_contact_json), Icons.Default.DataObject, onJson)
                FabMenuItem(stringResource(R.string.add_contact_manual), Icons.Default.Edit, onManual)
            }
        }
        FloatingActionButton(onClick = onToggle) {
            Icon(
                imageVector = if (expanded) Icons.Default.Close else Icons.Default.Add,
                contentDescription = stringResource(R.string.fab_add_contact)
            )
        }
    }
}

@Composable
private fun FabMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 2.dp
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        SmallFloatingActionButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun ContactCard(
    item: ContactItem,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMemory: () -> Unit
) {
    val contact = item.contact
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }
    var qrDialogOpen by remember { mutableStateOf(false) }

    // Toast 文案：回调/lambda 内无法调用 stringResource，先在 Composable 上下文解析
    val textExported = stringResource(R.string.toast_exported)
    val textExportFailed = stringResource(R.string.toast_export_failed)
    val textCopyFailed = stringResource(R.string.toast_copy_failed)
    val textCopiedJson = stringResource(R.string.toast_copied_json)

    // 导出 JSON 文件：SAF 让用户自己选保存位置，全程免存储权限
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(contact.toCardJson().toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            Toast.makeText(context, if (ok) textExported else textExportFailed, Toast.LENGTH_SHORT).show()
        }
    }

    fun copyJson() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            Toast.makeText(context, textCopyFailed, Toast.LENGTH_SHORT).show()
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText("wujie contact", contact.toCardJson()))
        Toast.makeText(context, textCopiedJson, Toast.LENGTH_SHORT).show()
    }

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像：自定义图片或首字圆形，角色扮演模式用容器色区分
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = if (contact.roleplay) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (!contact.avatarUri.isNullOrBlank()) {
                        AsyncImage(
                            model = contact.avatarUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = contact.name.take(1).ifBlank { "?" },
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    text = listOfNotNull(item.providerName, contact.model.ifBlank { null })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            // DropdownMenu 放在 IconButton 同级的 Box 内，
            // 这样 Popup 的 anchor 是 IconButton 本身，菜单会从图标正下方弹出（end 对齐），
            // 而不是被 Card 抢走 anchor 跑到卡片左侧。
            // 「分享」是二级菜单：shareOpen 时主菜单收起、子菜单从同一锚点弹出
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.common_more)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen && !shareOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_edit_contact)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_memory)) },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onMemory()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_share)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { shareOpen = true }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.menu_delete_contact),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
                DropdownMenu(
                    expanded = shareOpen,
                    onDismissRequest = {
                        // 点外部收起子菜单时回到主菜单，符合二级菜单的回退直觉
                        shareOpen = false
                        menuOpen = true
                    }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_export_json_file)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            shareOpen = false
                            menuOpen = false
                            val safe = contact.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "contact" }
                            exportLauncher.launch("wujie-$safe.json")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_copy_json)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            shareOpen = false
                            menuOpen = false
                            copyJson()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_export_qr)) },
                        leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
                        onClick = {
                            shareOpen = false
                            menuOpen = false
                            qrDialogOpen = true
                        }
                    )
                }
            }
        }
    }

    if (qrDialogOpen) {
        QrExportDialog(contact = contact, onDismiss = { qrDialogOpen = false })
    }
}
