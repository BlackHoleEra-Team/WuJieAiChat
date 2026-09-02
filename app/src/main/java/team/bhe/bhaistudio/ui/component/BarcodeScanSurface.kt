package team.bhe.bhaistudio.ui.component

import android.content.Context
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * 通用相机扫码表面（CameraX + ML Kit 条码解析）。
 *
 * 抽取自 ScanScreen，供「导出数据」扫码连接接收方等场景复用。
 * 每屏只回调一次（[onDetected] 触发后自动忽略后续帧）。
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun BarcodeScanSurface(
    modifier: Modifier = Modifier,
    onDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    // 只回调一次：连续帧会反复识别到同一个码
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
    }
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

/** 权限是否已授予（页面里用它决定显示相机还是申请权限） */
fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.CAMERA
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
