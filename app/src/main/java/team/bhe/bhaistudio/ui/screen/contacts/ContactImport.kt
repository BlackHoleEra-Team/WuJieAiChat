package team.bhe.bhaistudio.ui.screen.contacts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.ui.screen.scan.isWujieContactCard
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun isValidContactJson(value: String): Boolean = runCatching {
    val obj = JSONObject(value.trim())
    obj.has("name") || obj.optString("wujie") == "contact"
}.getOrDefault(false)

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}

/**
 * 打开 uri 的内容流。
 *
 * Photo Picker 返回的 uri 带有**临时读授权**，必须直接读这个 uri——
 * 千万别映射成 MediaStore 的 media/external uri（那需要 READ_MEDIA_IMAGES 权限，
 * 反而读不到）。openInputStream 对 picker uri 在部分系统上返回 null，
 * 此时用 openTypedAssetFileDescriptor（带 mime）兜底。
 */
private fun openStream(context: Context, uri: Uri): InputStream? {
    runCatching { context.contentResolver.openInputStream(uri) }
        .getOrNull()?.let { return it }
    return runCatching {
        context.contentResolver
            .openTypedAssetFileDescriptor(uri, "image/*", null)?.createInputStream()
    }.getOrNull()
}

/**
 * 通过流读取图片并采样解码（防 OOM）。
 * bounds 与完整解码各开一次独立流。
 */
private fun readBitmapScaled(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? {
    try {
        // 第一遍：读尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val s1 = openStream(context, uri)
        if (s1 == null) return null
        s1.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        // 第二遍：实际解码
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val s2 = openStream(context, uri)
        if (s2 == null) return null
        val bmp = s2.use { BitmapFactory.decodeStream(it, null, options) }
        return bmp
    } catch (_: Exception) {
        return null
    }
}

suspend fun decodeContactCardsFromImage(context: Context, uri: Uri): List<String> {
    val bitmap = readBitmapScaled(context, uri)
    if (bitmap == null) return emptyList()
    val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )
    return try {
        scanner.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
            .mapNotNull { it.rawValue }
            .filter { isWujieContactCard(it) }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    } finally {
        scanner.close()
        bitmap.recycle()
    }
}

fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
}.getOrNull()

fun queryFileName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

@Composable
fun JsonImportDialog(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val content = readTextFromUri(context, uri)
            if (content == null) {
                Toast.makeText(context, context.getString(R.string.toast_read_file_failed), Toast.LENGTH_SHORT).show()
            } else {
                text = content
                fileName = queryFileName(context, uri)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_json_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.import_json_placeholder)) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        filePicker.launch(arrayOf("application/json", "text/plain", "text/*"))
                    }) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Spacer(Modifier.padding(start = 4.dp))
                        Text(stringResource(R.string.import_json_pick_file))
                    }
                    fileName?.let {
                        Spacer(Modifier.padding(start = 8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onImport(text) }) {
                Text(stringResource(R.string.import_json_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
fun QrSourceDialog(
    onScanCamera: () -> Unit,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_qr_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onScanCamera, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.padding(start = 6.dp))
                    Text(stringResource(R.string.import_qr_camera))
                }
                OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.padding(start = 6.dp))
                    Text(stringResource(R.string.import_qr_gallery))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
fun QrCardSelectDialog(
    cards: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_qr_multi_title, cards.size)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cards, key = { it.hashCode() }) { card ->
                    val unnamed = stringResource(R.string.import_unnamed_contact)
                    val name = runCatching {
                        JSONObject(card).optString("name").ifBlank { unnamed }
                    }.getOrDefault(unnamed)
                    Surface(
                        onClick = { onSelect(card) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = runCatching {
                                    JSONObject(card).optString("systemPrompt")
                                }.getOrDefault("").ifBlank { card },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
