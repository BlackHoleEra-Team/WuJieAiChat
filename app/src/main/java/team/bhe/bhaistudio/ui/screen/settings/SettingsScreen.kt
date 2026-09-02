package team.bhe.bhaistudio.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.data.repository.AppLanguage
import team.bhe.bhaistudio.data.repository.ThemeMode

/**
 * 通用设置页
 *
 * 分段回复 / 延迟发送等回复方式开关已移到角色编辑页（角色扮演/关闭流式/延迟发送），
 * 这里只保留记忆、称呼、外观等全局设置。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSearchSettings: () -> Unit,
    onTransfer: () -> Unit
) {
    val vm: SettingsViewModel = koinViewModel()

    val enableHistoryMemory by vm.enableHistoryMemory.collectAsStateWithLifecycle()
    val historyRounds by vm.historyMemoryRounds.collectAsStateWithLifecycle()
    val enableAutoMemory by vm.enableAutoMemory.collectAsStateWithLifecycle()
    val enableAiUserName by vm.enableAiUserName.collectAsStateWithLifecycle()
    val aiUserName by vm.aiUserName.collectAsStateWithLifecycle()
    val dynamicColor by vm.dynamicColor.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val appLanguage by vm.appLanguage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 切换语言后提示：部分运行中文案（VM/Application context 生成）需重启应用才完全生效
    val chooseLanguage: (AppLanguage) -> Unit = { lang ->
        if (lang != appLanguage) {
            vm.setLanguage(lang)
            Toast.makeText(
                context,
                context.getString(R.string.settings_language_restart_hint),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            Section(stringResource(R.string.settings_section_memory)) {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_memory_title),
                    subtitle = stringResource(R.string.settings_auto_memory_subtitle),
                    checked = enableAutoMemory,
                    onCheckedChange = vm::setAutoMemory
                )
                SwitchRow(
                    title = stringResource(R.string.settings_send_memory_title),
                    subtitle = stringResource(R.string.settings_send_memory_subtitle),
                    checked = enableHistoryMemory,
                    onCheckedChange = { vm.setHistoryMemory(it, historyRounds) }
                )
            }

            Section(stringResource(R.string.settings_section_context)) {
                var roundsInput by remember(historyRounds) { mutableStateOf(historyRounds.toString()) }
                OutlinedTextField(
                    value = roundsInput,
                    onValueChange = { input ->
                        roundsInput = input
                        input.toIntOrNull()?.let { vm.setHistoryMemory(enableHistoryMemory, it) }
                    },
                    label = { Text(stringResource(R.string.settings_context_rounds_label)) },
                    supportingText = { Text(stringResource(R.string.settings_context_rounds_support)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Section(stringResource(R.string.settings_section_search)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSearchSettings)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_search_custom_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_search_custom_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Section(stringResource(R.string.settings_section_data)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onTransfer)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.transfer_entry_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.transfer_entry_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Section(stringResource(R.string.settings_section_appearance)) {
                Text(
                    text = stringResource(R.string.settings_theme_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setThemeMode(mode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = { vm.setThemeMode(mode) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (mode) {
                                ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                                ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                                ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                // 语言：跟随系统 / 中文 / 英文 / 日语 / 繁体中文（港台）
                // 切换即由 MainActivity 的 LocalContext 覆盖生效，不 recreate，导航原地保留
                Text(
                    text = stringResource(R.string.settings_language_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
                AppLanguage.entries.forEach { lang ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chooseLanguage(lang) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = appLanguage == lang,
                            onClick = { chooseLanguage(lang) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (lang) {
                                AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
                                AppLanguage.ZH -> stringResource(R.string.settings_language_zh)
                                AppLanguage.EN -> stringResource(R.string.settings_language_en)
                                AppLanguage.JA -> stringResource(R.string.settings_language_ja)
                                AppLanguage.ZH_HK -> stringResource(R.string.settings_language_zh_hk)
                                AppLanguage.ZH_TW -> stringResource(R.string.settings_language_zh_tw)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_color_title),
                    subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                    checked = dynamicColor,
                    onCheckedChange = vm::setDynamicColor
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────

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
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
