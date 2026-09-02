package team.bhe.bhaistudio.ui.screen.transfer

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.ui.component.BarcodeScanSurface
import team.bhe.bhaistudio.ui.component.hasCameraPermission

/**
 * 导出（发送）页。
 *
 * 接收方先进入「导入数据」的接收模式；本页扫描对方二维码（或手动输入地址），
 * 令牌校验通过并得到对方确认后，把本机数据加密发送过去。
 */
@Composable
fun ExportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ExportViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    var scanning by rememberSaveable { mutableStateOf(false) }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var manualPort by rememberSaveable { mutableStateOf("") }
    var manualToken by rememberSaveable { mutableStateOf("") }
    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (!granted) scanning = false
    }

    fun startScan() {
        if (cameraGranted) scanning = true else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(Unit) {
        onDispose { if (state is ExportUiState.Transferring) vm.backToIdle() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_top_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val s = state) {
                is ExportUiState.Idle -> {
                    if (scanning) {
                        ScanCard(
                            onDetected = { raw ->
                                scanning = false
                                vm.connectWithRaw(raw)
                            },
                            onCancel = { scanning = false }
                        )
                    } else {
                        GuideContent(
                            startScan = ::startScan,
                            manualOpen = manualOpen,
                            onToggleManual = { manualOpen = !manualOpen },
                            manualHost = manualHost,
                            onHostChange = { manualHost = it },
                            manualPort = manualPort,
                            onPortChange = { manualPort = it },
                            manualToken = manualToken,
                            onTokenChange = { manualToken = it },
                            onManualConnect = {
                                vm.connectManual(manualHost, manualPort, manualToken)
                            }
                        )
                    }
                }

                is ExportUiState.Preparing -> CenteredBusy(stringResource(R.string.export_preparing))

                is ExportUiState.Connecting -> CenteredBusy(
                    stringResource(R.string.export_connecting),
                    subtitle = "${s.invite.host}:${s.invite.port}"
                )

                is ExportUiState.Transferring -> {
                    vmStateContent(s)
                }

                is ExportUiState.Done -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.export_done, s.target),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = onBack) { Text(stringResource(R.string.export_back)) }
                }

                is ExportUiState.Error -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.export_error_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = { vm.backToIdle() }) { Text(stringResource(R.string.export_retry)) }
                }
            }
        }
    }
}

@Composable
private fun vmStateContent(s: ExportUiState.Transferring) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.export_transferring), style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = "${(s.progress * 100).toInt()}%  ·  ${formatBytes(s.sent)} / ${formatBytes(s.total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CenteredBusy(text: String, subtitle: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuideContent(
    startScan: () -> Unit,
    manualOpen: Boolean,
    onToggleManual: () -> Unit,
    manualHost: String,
    onHostChange: (String) -> Unit,
    manualPort: String,
    onPortChange: (String) -> Unit,
    manualToken: String,
    onTokenChange: (String) -> Unit,
    onManualConnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.export_step1),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.export_step2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(onClick = startScan, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.export_scan))
        }

        OutlinedButton(onClick = onToggleManual, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.export_manual))
        }

        if (manualOpen) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = onHostChange,
                        label = { Text(stringResource(R.string.export_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualPort,
                        onValueChange = onPortChange,
                        label = { Text(stringResource(R.string.export_port)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = onTokenChange,
                        label = { Text(stringResource(R.string.export_token)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = onManualConnect, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.export_connect))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanCard(onDetected: (String) -> Unit, onCancel: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
    ) {
        BarcodeScanSurface(
            modifier = Modifier.fillMaxSize(),
            onDetected = onDetected
        )
    }
    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.transfer_cancel)) }
}
