package team.bhe.bhaistudio.ui.screen.scan

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import team.bhe.bhaistudio.R

/**
 * 扫一扫
 *
 * 用途：
 * 1. 普通网址 → 交给 [onOpenUrl]，用内置 WebView 打开
 * 2. 无界角色卡片（JSON，含 `"wujie":"contact"`）→ 交给 [onCreateContact]，
 *    跳转创建角色页并自动填入数据
 * 3. 其它文本 → 复制到剪贴板并提示
 *
 * 相机预览用 CameraX，条码解析用 ML Kit（模型随包，不依赖 GMS）。
 */
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCreateContact: (String) -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var denied by remember { mutableStateOf(false) }

    // 扫码成功的「叮」：系统音，无需音频文件。音量走媒体流。
    val toneGenerator = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { toneGenerator?.release() } }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        granted = isGranted
        denied = !isGranted
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                granted -> CameraScan(
                    modifier = Modifier.fillMaxSize(),
                    onDetected = { raw ->
                        // 先响「叮」再分发，跳转后声音也能播完
                        // TONE_PROP_BEEP 是系统标准提示音，兼容性比 TONE_CDMA_PIP 好（后者部分国产 ROM 无声）
                        val ok = runCatching {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                        }.getOrNull() == true
                        if (!ok) {
                            // 兜底：走通知流再试一次
                            runCatching {
                                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                                    .startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                            }
                        }
                        handleDetected(raw, context, onOpenUrl, onCreateContact)
                    }
                )

                denied -> PermissionDeniedHint(
                    modifier = Modifier.align(Alignment.Center),
                    onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )

                else -> Text(
                    text = stringResource(R.string.scan_waiting_permission),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

/** 按内容类型分发扫描结果 */
private fun handleDetected(
    raw: String,
    context: Context,
    onOpenUrl: (String) -> Unit,
    onCreateContact: (String) -> Unit
) {
    val value = raw.trim()
    when {
        value.startsWith("http://", true) || value.startsWith("https://", true) -> onOpenUrl(value)
        isWujieContactCard(value) -> onCreateContact(value)
        else -> {
            // 纯文本没有明确去处：复制出来，避免"扫到了却什么都没发生"
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("scan result", value))
            // 非 Composable 上下文：用 Context.getString
            Toast.makeText(
                context,
                context.getString(R.string.toast_scan_result_copied, value),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

/**
 * 判断是否为无界角色卡片。
 *
 * 格式（后续可能扩展）：`{"wujie":"contact","name":"...","systemPrompt":"...",...}`
 * 用 `wujie` 标识字段区分，避免把任意 JSON 二维码误当成角色卡片导入。
 */
internal fun isWujieContactCard(value: String): Boolean {
    // 容忍 BOM 与前置空白（部分扫码器 / 旧版本生成内容可能带 BOM）
    val trimmed = value.trimStart('﻿', ' ', '\t', '\n', '\r')
    if (!trimmed.startsWith("{")) return false
    return runCatching {
        val obj = JSONObject(trimmed)
        // 严格匹配优先：当前标准（"wujie":"contact"）
        if (obj.optString("wujie") == "contact") return@runCatching true
        // 兜底：早期版本/外部工具生成的卡片没有 wujie 字段，但有 name + systemPrompt
        obj.has("name") && obj.has("systemPrompt")
    }.getOrDefault(false)
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraScan(
    modifier: Modifier = Modifier,
    onDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
    }
    // 只回调一次：连续帧会反复识别到同一个码，不去重会导致跳转/复制被触发多次
    var handled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy ->
                            if (handled) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            scanBarcode(scanner, proxy) { value ->
                                if (!handled && value.isNotBlank()) {
                                    handled = true
                                    onDetected(value)
                                }
                            }
                        }
                    }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    { future.get().unbindAll() },
                    ContextCompat.getMainExecutor(context)
                )
            }
            scanner.close()
        }
    }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScanFrame()
    }
}

/** 扫描框装饰：居中取景框 + 提示文字 */
@Composable
private fun BoxScope.ScanFrame() {
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(240.dp)
            .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
    )
    Text(
        text = stringResource(R.string.scan_frame_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(y = 150.dp)
    )
}

@OptIn(ExperimentalGetImage::class)
private fun scanBarcode(
    scanner: BarcodeScanner,
    proxy: ImageProxy,
    onResult: (String) -> Unit
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { list ->
            val value = list.firstOrNull()?.rawValue
            if (!value.isNullOrBlank()) onResult(value)
        }
        .addOnCompleteListener { proxy.close() }
}

@Composable
private fun PermissionDeniedHint(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.scan_permission_required),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.scan_permission_rationale),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.scan_retry_permission)) }
    }
}
