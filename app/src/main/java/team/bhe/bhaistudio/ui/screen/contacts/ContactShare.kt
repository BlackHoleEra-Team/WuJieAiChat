package team.bhe.bhaistudio.ui.screen.contacts

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.data.db.entity.ContactEntity
import java.io.File

/**
 * 角色卡片 <-> JSON <-> 二维码
 *
 * 卡片格式与扫码端（ScanScreen.isWujieContactCard / ContactEditViewModel.importFromCard）
 * 共用同一套字段：`{"wujie":"contact","name":...,"systemPrompt":...}`。
 * `wujie` 是标识头，扫码器据此区分"无界角色卡片"和任意 JSON。
 *
 * 刻意不带 providerConfigId / avatarUri：服务商 id 是本机数据库主键，
 * 头像路径是本机文件路径，对导入方都是噪音。
 */
fun ContactEntity.toCardJson(): String = buildString {
    append('{')
    append("\"wujie\":\"contact\",")
    append("\"v\":1,")
    append("\"name\":").append(jsonString(name))
    append(",\"systemPrompt\":").append(jsonString(systemPrompt))
    if (model.isNotBlank()) append(",\"model\":").append(jsonString(model))
    append(",\"roleplay\":").append(roleplay)
    append(",\"disableStreaming\":").append(disableStreaming)
    append(",\"enableDelay\":").append(enableDelay)
    append(",\"webSearch\":").append(webSearch)
    append(",\"useCustomSearch\":").append(useCustomSearch)
    append(",\"deepThink\":").append(deepThink)
    append(",\"temperature\":").append(temperature)
    append(",\"topP\":").append(topP)
    append(",\"thinkingBudget\":").append(thinkingBudget)
    append('}')
}

/** 手写 JSON 字符串转义：引号、反斜杠、控制字符 */
private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

/**
 * 生成角色二维码。
 *
 * 二维码容量有限（M 级纠错约 2.3KB）：人设特别长的角色会 encode 失败，
 * 返回 null 由调用方提示改用 JSON 文件导出。
 */
fun generateQrBitmap(content: String, sizePx: Int = 720): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
    )
    val matrix = QRCodeWriter()
        .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            pixels[y * sizePx + x] = if (matrix[x, y]) black else white
        }
    }
    Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}.getOrNull()

/**
 * 二维码存入相册。
 *
 * API 29+ 走 MediaStore（Pictures/WuJie），无需任何存储权限；
 * API 26-28 无 RELATIVE_PATH，退化为应用私有图片目录（免权限，Toast 里给路径）。
 *
 * @return 保存位置描述，失败返回 null
 */
fun saveQrToGallery(context: Context, bitmap: Bitmap, contactName: String): String? = runCatching {
    val fileName = "wujie-${contactName.ifBlank { "contact" }.take(20)}-${System.currentTimeMillis()}.png"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WuJie")
        }
        val uri = context.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        "Pictures/WuJie/$fileName"
    } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File(dir, fileName)
        file.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        file.absolutePath
    }
}.getOrNull()

/**
 * 角色二维码导出对话框：预览 + 保存相册。
 */
@Composable
fun QrExportDialog(
    contact: ContactEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(contact.id) { generateQrBitmap(contact.toCardJson()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_export_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_export_title),
                        modifier = Modifier.size(260.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.qr_export_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = stringResource(R.string.qr_too_large),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            if (bitmap != null) {
                TextButton(onClick = {
                    val path = saveQrToGallery(context, bitmap, contact.name)
                    Toast.makeText(
                        context,
                        if (path != null) context.getString(R.string.qr_saved, path) else context.getString(R.string.qr_save_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }) { Text(stringResource(R.string.qr_save_to_gallery)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.qr_close)) }
        }
    )
}
