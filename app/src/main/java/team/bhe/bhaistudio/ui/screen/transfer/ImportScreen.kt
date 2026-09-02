package team.bhe.bhaistudio.ui.screen.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.data.transfer.InviteInfo
import team.bhe.bhaistudio.data.transfer.encode
import team.bhe.bhaistudio.data.transfer.localDeviceName
import team.bhe.bhaistudio.ui.screen.contacts.generateQrBitmap

/**
 * 导入（接收）页。
 *
 * 进入后立即进入接收模式：监听 TCP + 展示二维码 / 连接信息；
 * 有设备连入并令牌匹配时弹窗询问是否接受；接受后开始加密接收，
 * 全部落盘后重启进程完成迁移落地。
 */
@Composable
fun ImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ImportViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.startListening() }
    DisposableEffect(Unit) {
        onDispose { vm.cancel() }
    }

    // 接收完成 → 短暂展示后重启进程，让 Application 落地迁移包
    LaunchedEffect(state) {
        if (state is ImportUiState.Done) {
            delay(1800)
            restartAppForMigration(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_top_title)) },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val s = state) {
                is ImportUiState.Listening -> ListeningContent(
                    invite = s.invite,
                    onCancel = { vm.cancel(); onBack() }
                )

                is ImportUiState.Asking -> {
                    Text(
                        stringResource(R.string.import_listening_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    AskingDialog(
                        from = s.fromDevice,
                        onAccept = { vm.respond(true) },
                        onReject = { vm.respond(false) }
                    )
                }

                is ImportUiState.Transferring -> TransferProgress(
                    label = stringResource(R.string.import_transferring),
                    progress = s.progress,
                    done = s.received,
                    total = s.total
                )

                ImportUiState.Done -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.import_done_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        stringResource(R.string.import_restart),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is ImportUiState.Error -> ErrorContent(
                    message = s.message,
                    action = { vm.startListening() },
                    actionLabel = stringResource(R.string.import_retry),
                    onBack = onBack
                )

                ImportUiState.Idle -> CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun ListeningContent(invite: InviteInfo, onCancel: () -> Unit) {
    val qr = remember(invite) {
        generateQrBitmap(invite.encode(), 700)?.asImageBitmap()
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text(
                stringResource(R.string.import_listening),
                style = MaterialTheme.typography.titleMedium
            )
        }

        Card(
            modifier = Modifier.size(280.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            if (qr != null) {
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.import_qr_hint),
                    modifier = Modifier.padding(20.dp)
                )
            } else {
                Text(
                    stringResource(R.string.import_qr_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
        Text(
            stringResource(R.string.import_qr_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Text(
            "${localDeviceName()}\n${invite.host}:${invite.port}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.import_cancel))
        }
    }
}

@Composable
private fun AskingDialog(
    from: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.import_incoming_title, from)) },
        text = { Text(stringResource(R.string.import_incoming_body)) },
        confirmButton = {
            Button(onClick = onAccept) { Text(stringResource(R.string.import_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text(stringResource(R.string.import_reject)) }
        }
    )
}

@Composable
private fun TransferProgress(
    label: String,
    progress: Float,
    done: Long,
    total: Long
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = "${(progress * 100).toInt()}%  ·  ${formatBytes(done)} / ${formatBytes(total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    action: () -> Unit,
    actionLabel: String,
    onBack: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.import_error_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Button(onClick = action) { Text(actionLabel) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.transfer_cancel)) }
    }
}
