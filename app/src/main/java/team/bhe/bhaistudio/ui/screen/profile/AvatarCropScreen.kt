package team.bhe.bhaistudio.ui.screen.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import team.bhe.bhaistudio.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 头像裁切页（Compose 重写老代码 ImagePickerActivity 的裁切流）
 *
 * 做法与老代码一致：
 *   1. 图片可双指缩放、拖动（以手势质心为缩放中心）
 *   2. 固定居中的圆形蒙版（scrim + 白描边 + 参考线）
 *   3. 确认时把屏幕圆形映射回 bitmap 坐标 → 裁切正方形 → 缩到 512 → 存私有目录
 *
 * 纯手势实现，不依赖 uCrop；圆形蒙版用 Canvas 绘制。
 */
@Composable
fun AvatarCropScreen(
    imageUri: String,
    onBack: () -> Unit,
    onCropped: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bitmap = remember(imageUri) { decodeBitmap(context, imageUri) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val density = LocalDensity.current.density
        val cw = maxWidth.value * density
        val ch = maxHeight.value * density

        // 手势闭包里读最新值（rememberUpdatedState 保证重组后不取旧值）
        val currentBitmap by rememberUpdatedState(bitmap)
        val currentCw by rememberUpdatedState(cw)
        val currentCh by rememberUpdatedState(ch)

        // 图片层（可缩放拖动）
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = offset.x
                        translationY = offset.y
                        scaleX = scale
                        scaleY = scale
                    }
                    // 以手势质心为缩放中心；最小缩放到"图片短边刚好覆盖裁切圆"，
                    // 并约束图片不能拖出裁切圆（裁切区永远有内容）
                    .pointerInput(bitmap) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val b = currentBitmap
                            val minScale = if (b != null) {
                                val fit = min(currentCw / b.width, currentCh / b.height)
                                val shortEdge = min(b.width.toFloat(), b.height.toFloat())
                                // 图片短边 × fit × scale ≥ 圆直径
                                (min(currentCw, currentCh) * CropRadiusFraction * 2f) / (shortEdge * fit)
                            } else 1f
                            // coerceIn 要求 min ≤ max：小图的 minScale 可能比 8f 还大，
                            // 直接 coerceIn(minScale, 8f) 会抛 IllegalArgumentException（缩到最小即崩）
                            val maxScale = 8f
                            val newScale = (scale * zoom).coerceIn(minOf(minScale, maxScale), maxScale)
                            val ratio = newScale / scale
                            val newOffset = centroid + (offset - centroid) * ratio + pan
                            offset = clampOffsetInsideCrop(
                                currentBitmap, currentCw, currentCh, newScale, newOffset
                            )
                            scale = newScale
                        }
                    }
            )
        }

        // 圆形裁切蒙版
        AvatarCropOverlay()

        // 顶部提示（避开状态栏）
        Row(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = Color.White
                )
            }
            Text(
                text = stringResource(R.string.avatar_crop_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        // 底部确认
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    if (bitmap != null && !saving) {
                        saving = true
                        scope.launch {
                            val uri = withContext(Dispatchers.Default) {
                                runCatching { cropAndSave(context, bitmap, cw, ch, offset, scale) }
                                    .getOrNull()
                            }
                            if (uri != null) onCropped(uri.toString())
                            onBack()
                        }
                    }
                },
                enabled = bitmap != null && !saving
            ) {
                Text(
                    if (saving) stringResource(R.string.avatar_crop_saving)
                    else stringResource(R.string.avatar_crop_confirm)
                )
            }
        }
    }
}

/** 裁切圆半径占容器短边的比例（蒙版 / 手势约束 / 裁切输出三处保持一致） */
private const val CropRadiusFraction = 0.38f

/** 圆形裁切蒙版：scrim + 白描边 + 十字参考线 */
@Composable
private fun AvatarCropOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val r = min(size.width, size.height) * CropRadiusFraction
        val center = Offset(size.width / 2f, size.height / 2f)
        val circleRect = Rect(center.x - r, center.y - r, center.x + r, center.y + r)

        val mask = Path().apply {
            addRect(Rect(0f, 0f, size.width, size.height))
            addOval(circleRect)
            fillType = PathFillType.EvenOdd
        }
        drawPath(mask, Color.Black.copy(alpha = 0.55f))

        drawCircle(Color.White.copy(alpha = 0.9f), r, center, style = Stroke(width = 3.dp.toPx()))
        drawLine(
            Color.White.copy(alpha = 0.35f),
            Offset(center.x - r, center.y),
            Offset(center.x + r, center.y),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            Color.White.copy(alpha = 0.35f),
            Offset(center.x, center.y - r),
            Offset(center.x, center.y + r),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/**
 * 约束图片偏移：保证圆形裁切区映射回图片后不越界。
 *
 * 图片中心相对容器中心的偏移 = offset，圆孔半径（图片坐标）= cropR / s。
 * 要求圆孔不越界：|offset.x| ≤ (bw/2 − rImg) × s，y 同理。
 * 图片本身比圆孔还小时 max 钳到 0，即固定居中。
 */
private fun clampOffsetInsideCrop(
    bitmap: Bitmap?,
    cw: Float,
    ch: Float,
    scale: Float,
    offset: Offset
): Offset {
    if (bitmap == null) return offset
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    val fit = min(cw / bw, ch / bh)
    val s = fit * scale
    val rImg = (min(cw, ch) * CropRadiusFraction) / s
    val maxX = ((bw / 2f - rImg) * s).coerceAtLeast(0f)
    val maxY = ((bh / 2f - rImg) * s).coerceAtLeast(0f)
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY)
    )
}

/** 解码 uri 图片，限制最长边 ≤ 2048 防 OOM */
private fun decodeBitmap(context: Context, uri: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(Uri.parse(uri))?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    val maxDim = max(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (maxDim / sample > 2048) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(Uri.parse(uri))?.use {
        BitmapFactory.decodeStream(it, null, opts)
    }
}.getOrNull()

/**
 * 把屏幕居中的圆形映射回 bitmap 坐标并裁切为正方形，缩放到 512×512 保存。
 *
 * 变换关系（bitmap → 屏幕）：
 *   s = fitScale * gestureScale
 *   screen = center + (fitPos - center) * gestureScale + offset
 * 逆推得圆心的 bitmap 坐标 = bitmap中心 - offset / s。
 */
private fun cropAndSave(
    context: Context,
    bitmap: Bitmap,
    cw: Float,
    ch: Float,
    offset: Offset,
    scale: Float
): Uri? {
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    val fitScale = min(cw / bw, ch / bh)
    val s = fitScale * scale
    val r = min(cw, ch) * CropRadiusFraction

    val centerX = bw / 2f - offset.x / s
    val centerY = bh / 2f - offset.y / s
    val half = r / s

    val left = (centerX - half).toInt().coerceIn(0, bitmap.width - 1)
    val top = (centerY - half).toInt().coerceIn(0, bitmap.height - 1)
    val right = (centerX + half).toInt().coerceIn(left + 1, bitmap.width)
    val bottom = (centerY + half).toInt().coerceIn(top + 1, bitmap.height)

    val cropSize = min(right - left, bottom - top)
    if (cropSize <= 0) return null

    val cropped = Bitmap.createBitmap(bitmap, left, top, cropSize, cropSize)
    val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
    if (scaled !== cropped) cropped.recycle()

    val dir = File(context.filesDir, "avatars").apply { mkdirs() }
    val out = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
    val ok = runCatching {
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }.isSuccess
    return if (ok) Uri.fromFile(out) else null
}
