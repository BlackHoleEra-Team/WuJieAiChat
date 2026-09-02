package team.bhe.bhaistudio.ui.screen.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Canvas
import team.bhe.bhaistudio.BuildConfig
import team.bhe.bhaistudio.R

/**
 * 关于页——应用信息、能力说明、技术栈、开源鸣谢
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var showLicenses by remember { mutableStateOf(false) }
    // AdaptiveIconDrawable 不能直接用 painterResource 加载，先渲染成 Bitmap
    val context = LocalContext.current
    val appIcon = remember(context) {
        val drawable = requireNotNull(context.getDrawable(R.mipmap.ic_launcher))
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 应用徽标
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = scheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = scheme.surfaceContainerLowest,
                        modifier = Modifier.size(88.dp)
                    ) {
                        Image(
                            bitmap = appIcon,
                            contentDescription = stringResource(R.string.about_app_icon_desc),
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.about_version_format, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            Section(stringResource(R.string.about_section_what)) {
                Text(
                    text = stringResource(R.string.about_what_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }

            Section(stringResource(R.string.about_section_features)) {
                FeatureRow(
                    stringResource(R.string.about_feature_roleplay_title),
                    stringResource(R.string.about_feature_roleplay_sub)
                )
                FeatureRow(
                    stringResource(R.string.about_feature_segment_title),
                    stringResource(R.string.about_feature_segment_sub)
                )
                FeatureRow(
                    stringResource(R.string.about_feature_memory_title),
                    stringResource(R.string.about_feature_memory_sub)
                )
                FeatureRow(
                    stringResource(R.string.about_feature_deepthink_title),
                    stringResource(R.string.about_feature_deepthink_sub)
                )
                FeatureRow(
                    stringResource(R.string.about_feature_search_title),
                    stringResource(R.string.about_feature_search_sub)
                )
                FeatureRow(
                    stringResource(R.string.about_feature_providers_title),
                    stringResource(R.string.about_feature_providers_sub)
                )
                FeatureRow(
                    stringResource(R.string.about_feature_color_title),
                    stringResource(R.string.about_feature_color_sub)
                )
            }

            Section(stringResource(R.string.about_section_stack)) {
                Text(
                    text = stringResource(R.string.about_stack_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }

            Section(stringResource(R.string.about_section_credits)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLicenses = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.about_credits_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.about_credits_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant
                    )
                }
            }

            Section(stringResource(R.string.about_section_license)) {
                Text(
                    text = stringResource(R.string.about_license_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun FeatureRow(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(8.dp)
        ) {}
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 开源项目鸣谢弹窗 */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_licenses_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                LicenseRow("Jetpack Compose / Material 3", stringResource(R.string.about_license_compose_desc), "Apache-2.0")
                LicenseRow("Material Symbols", stringResource(R.string.about_license_icons_desc), "Apache-2.0")
                LicenseRow("Navigation3", stringResource(R.string.about_license_navigation_desc), "Apache-2.0")
                LicenseRow("Room", stringResource(R.string.about_license_room_desc), "Apache-2.0")
                LicenseRow("DataStore", stringResource(R.string.about_license_datastore_desc), "Apache-2.0")
                LicenseRow("Koin", stringResource(R.string.about_license_koin_desc), "Apache-2.0")
                LicenseRow("OkHttp / okhttp-sse", stringResource(R.string.about_license_okhttp_desc), "Apache-2.0")
                LicenseRow("kotlinx.coroutines", stringResource(R.string.about_license_coroutines_desc), "Apache-2.0")
                LicenseRow("kotlinx.serialization", stringResource(R.string.about_license_serialization_desc), "Apache-2.0")
                LicenseRow("Coil", stringResource(R.string.about_license_coil_desc), "Apache-2.0")
                LicenseRow("Paging 3", stringResource(R.string.about_license_paging_desc), "Apache-2.0")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_settings_got_it)) }
        }
    )
}

@Composable
private fun LicenseRow(name: String, description: String, license: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = scheme.surfaceContainerHigh
        ) {
            Text(
                text = license,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
