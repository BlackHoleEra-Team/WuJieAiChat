package team.bhe.bhaistudio.ui.screen.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R

/**
 * "我的"页——用户卡片 + 服务商/设置入口
 *
 * 顶部用户卡片复刻老代码 ProfileFragment 的做法（头像可换、昵称可改），
 * 视觉按 MD3E：extraLarge 大圆角、tonal 渐变、编辑徽标。
 */
@Composable
fun ProfileScreen(
    onProviders: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onOpenRecycled: () -> Unit,
    onAvatarPicked: (String, (String) -> Unit) -> Unit
) {
    val vm: ProfileViewModel = koinViewModel()
    val nickname by vm.nickname.collectAsStateWithLifecycle()
    val avatarUri by vm.avatarUri.collectAsStateWithLifecycle()

    var showNicknameDialog by remember { mutableStateOf(false) }

    // 系统 Photo Picker 选头像（无需存储权限）→ 交给上层进入裁切页 → 裁切结果保存
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onAvatarPicked(uri.toString()) { cropped -> vm.setAvatarUri(cropped) }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_profile)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 用户卡片
            UserHeroCard(
                nickname = nickname.ifBlank { stringResource(R.string.profile_default_name) },
                avatarUri = avatarUri,
                onAvatarClick = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onNicknameClick = { showNicknameDialog = true }
            )

            EntryCard(
                icon = Icons.Default.Key,
                title = stringResource(R.string.providers_title),
                subtitle = stringResource(R.string.profile_providers_subtitle),
                onClick = onProviders
            )

            EntryCard(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.profile_settings_subtitle),
                onClick = onSettings
            )

            EntryCard(
                icon = Icons.Default.History,
                title = stringResource(R.string.recycle_title),
                subtitle = stringResource(R.string.recycle_profile_subtitle),
                onClick = onOpenRecycled
            )

            EntryCard(
                icon = Icons.Default.Info,
                title = stringResource(R.string.about_title),
                subtitle = stringResource(R.string.profile_about_subtitle),
                onClick = onAbout
            )
        }
    }

    if (showNicknameDialog) {
        NicknameDialog(
            current = nickname,
            onDismiss = { showNicknameDialog = false },
            onConfirm = { vm.setNickname(it); showNicknameDialog = false }
        )
    }
}

/**
 * MD3E 用户 Hero 卡片
 *
 *   · extraLarge（32dp）大圆角 + primaryContainer→surfaceContainerHighest 渐变
 *   · 76dp 圆形头像：有自定义图显示图片，否则显示昵称首字
 *   · 头像右下角 primary 圆形「编辑」徽标，暗示可点击换头像
 *   · 昵称 + 右侧小编辑图标，点击弹窗改名
 */
@Composable
private fun UserHeroCard(
    nickname: String,
    avatarUri: String,
    onAvatarClick: () -> Unit,
    onNicknameClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(scheme.primaryContainer, scheme.surfaceContainerHighest)
                    )
                )
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 头像 + 编辑徽标
                Box(modifier = Modifier.size(76.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.10f),
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .border(2.dp, scheme.primary.copy(alpha = 0.55f), CircleShape)
                            .clickable(onClick = onAvatarClick)
                    ) {
                        if (avatarUri.isNotBlank()) {
                            AsyncImage(
                                model = avatarUri,
                                contentDescription = stringResource(R.string.profile_avatar_desc),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = nickname.take(1),
                                    style = MaterialTheme.typography.displaySmall,
                                    color = scheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    // 编辑徽标
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(scheme.primary)
                            .clickable(onClick = onAvatarClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.profile_change_avatar_desc),
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(Modifier.width(18.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onNicknameClick)
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = nickname,
                            style = MaterialTheme.typography.titleLarge,
                            color = scheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.profile_change_nickname_desc),
                            tint = scheme.onPrimaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.profile_slogan),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NicknameDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_nickname_dialog_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.profile_nickname_label)) },
                placeholder = { Text(stringResource(R.string.profile_default_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input) },
                enabled = input.isNotBlank()
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun EntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
