package team.bhe.bhaistudio.ui.screen.contact

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R

/**
 * 联系人（AI 角色）编辑页
 *
 * 桌面端 AddContact / EditContact 两个 100KB+ 的 Activity（大量重复代码）
 * 在这里合并成一个页面，新增与编辑共用同一套表单。
 */
@Composable
fun ContactEditScreen(
    contactId: String?,
    importJson: String? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onCropRequested: (String, (String) -> Unit) -> Unit,
    onOpenSearchSettings: () -> Unit,
    onOpenProviders: () -> Unit
) {
    val vm: ContactEditViewModel = koinViewModel()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val hasSearchConfig by vm.hasSearchConfig.collectAsStateWithLifecycle()

    // Navigation3 不自动填充 SavedStateHandle，必须显式传入。
    // 从裁切页返回时本组合会重建、LaunchedEffect 重新执行——
    // 若此时重新 initialize，数据库旧值会把刚裁切好的头像覆盖掉（角色头像 bug），
    // 所以用 skipNextInit 跳过那一次。
    LaunchedEffect(contactId, importJson) {
        if (vm.skipNextInit) {
            vm.skipNextInit = false
        } else {
            vm.initialize(contactId, importJson)
        }
    }

    // 导入的卡片不带服务商信息；未选服务商时默认选第一个已配置密钥的
    LaunchedEffect(providers) {
        if (vm.providerConfigId.isBlank()) {
            providers.firstOrNull { it.maskedApiKey.isNotBlank() }?.let {
                vm.providerConfigId = it.id
            }
        }
    }

    // 头像选择：选图 → 上层进裁切页 → 裁切结果回填 avatarUri
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onCropRequested(uri.toString()) { cropped ->
                vm.avatarUri = cropped
                vm.skipNextInit = true
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (contactId == null) stringResource(R.string.contact_edit_title_new)
                        else stringResource(R.string.contact_edit_title_edit)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { vm.save(onSaved) },
                        enabled = !vm.saving,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(stringResource(R.string.common_save))
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
            // 头像选择（不选则显示首字）
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.size(88.dp)) {
                    Surface(
                        onClick = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (vm.avatarUri != null) {
                            AsyncImage(
                                model = vm.avatarUri,
                                contentDescription = stringResource(R.string.contact_avatar_desc),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = vm.name.take(1).ifBlank { "?" },
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
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
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.contact_pick_avatar_desc),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.contact_avatar_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = vm.name,
                onValueChange = { vm.name = it },
                label = { Text(stringResource(R.string.contact_name_label)) },
                placeholder = { Text(stringResource(R.string.contact_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 服务商选择
            ProviderPicker(
                providers = providers,
                selectedId = vm.providerConfigId,
                onSelect = {
                    vm.providerConfigId = it
                    // 切换服务商时重置模型
                    vm.model = vm.modelsOf(it).firstOrNull() ?: ""
                }
            )

            // 服务商未设置密钥：指引前往服务商管理（不阻止继续创建）
            val selectedProvider = providers.firstOrNull { it.id == vm.providerConfigId }
            if (selectedProvider != null && selectedProvider.maskedApiKey.isBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.contact_provider_no_key_format, selectedProvider.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenProviders) {
                        Text(stringResource(R.string.common_go_settings))
                    }
                }
            }

            // 模型选择：可从服务商模型下拉选，也可手动输入任意模型名
            ModelPicker(
                models = vm.modelsOf(vm.providerConfigId),
                selected = vm.model,
                onSelect = { vm.model = it }
            )

            OutlinedTextField(
                value = vm.systemPrompt,
                onValueChange = { vm.systemPrompt = it },
                label = { Text(stringResource(R.string.contact_prompt_label)) },
                placeholder = { Text(stringResource(R.string.contact_prompt_placeholder)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )

            // 角色扮演
            SwitchRow(
                title = stringResource(R.string.contact_roleplay_title),
                subtitle = stringResource(R.string.contact_roleplay_subtitle),
                checked = vm.roleplay,
                onCheckedChange = {
                    vm.roleplay = it
                    // 选择性联动：勾选角色扮演时自动开启关闭流式；取消则保持独立选择
                    if (it) vm.disableStreaming = true
                }
            )

            // 关闭流式传输（独立开关，与角色扮演/延迟发送双向联动）
            SwitchRow(
                title = stringResource(R.string.contact_disable_streaming_title),
                subtitle = stringResource(R.string.contact_disable_streaming_subtitle),
                checked = vm.disableStreaming,
                onCheckedChange = {
                    vm.disableStreaming = it
                    // 反向联动：关闭「关闭流式」= 允许流式，角色扮演 / 延迟发送随之关闭
                    if (!it) {
                        vm.roleplay = false
                        vm.enableDelay = false
                    }
                }
            )

            // 延迟发送（与「关闭流式传输」互斥：不关闭流式就用不了延迟）
            SwitchRow(
                title = stringResource(R.string.contact_delay_title),
                subtitle = stringResource(R.string.contact_delay_subtitle),
                checked = vm.enableDelay,
                onCheckedChange = {
                    vm.enableDelay = it
                    // 互斥：启用延迟发送必须关闭流式传输
                    if (it) vm.disableStreaming = true
                }
            )

            // 高级参数
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SwitchRow(
                        title = stringResource(R.string.contact_advanced_title),
                        subtitle = stringResource(R.string.contact_advanced_subtitle),
                        checked = vm.advancedEnabled,
                        onCheckedChange = { vm.advancedEnabled = it }
                    )

                    if (vm.advancedEnabled) {
                        SwitchRow(
                            title = stringResource(R.string.contact_deepthink_title),
                            subtitle = stringResource(R.string.contact_deepthink_subtitle),
                            checked = vm.deepThink,
                            onCheckedChange = { vm.deepThink = it }
                        )
                        SwitchRow(
                            title = stringResource(R.string.contact_websearch_title),
                            subtitle = if (vm.webSearch) {
                                stringResource(R.string.contact_websearch_subtitle_on)
                            } else {
                                stringResource(R.string.contact_websearch_subtitle_off)
                            },
                            checked = vm.webSearch,
                            onCheckedChange = { vm.webSearch = it }
                        )
                        if (vm.webSearch) {
                            // 搜索方式互斥单选：官方内置 vs 自定义 API
                            SearchModeRow(
                                title = stringResource(R.string.contact_search_official_title),
                                subtitle = stringResource(R.string.contact_search_official_subtitle),
                                selected = !vm.useCustomSearch,
                                onClick = { vm.useCustomSearch = false }
                            )
                            SearchModeRow(
                                title = stringResource(R.string.contact_search_custom_title),
                                subtitle = stringResource(R.string.contact_search_custom_subtitle),
                                selected = vm.useCustomSearch,
                                onClick = { vm.useCustomSearch = true }
                            )
                            if (vm.useCustomSearch && !hasSearchConfig) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(R.string.contact_search_missing),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = onOpenSearchSettings) {
                                        Text(stringResource(R.string.contact_go_config))
                                    }
                                }
                            }
                        }
                        SliderRow(
                            label = stringResource(R.string.contact_slider_temperature, vm.temperature.toString()),
                            value = vm.temperature,
                            onValueChange = { vm.temperature = it }
                        )
                        SliderRow(
                            label = stringResource(R.string.contact_slider_top_p, vm.topP.toString()),
                            value = vm.topP,
                            onValueChange = { vm.topP = it }
                        )
                        SliderRow(
                            label = stringResource(R.string.contact_slider_thinking_budget, vm.thinkingBudget.toString()),
                            value = vm.thinkingBudget.toFloat(),
                            onValueChange = { vm.thinkingBudget = it.toInt() },
                            valueRange = 1024f..32000f
                        )
                        OutlinedTextField(
                            value = if (vm.contextWindow == 0) "" else vm.contextWindow.toString(),
                            onValueChange = { input ->
                                vm.contextWindow = input.toIntOrNull()?.coerceAtLeast(0) ?: 0
                            },
                            label = { Text(stringResource(R.string.contact_context_window_label)) },
                            placeholder = { Text(stringResource(R.string.contact_context_window_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            vm.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
// 通用小部件
// ─────────────────────────────────────────────────────────

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

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

/** 搜索方式单选行（官方内置 vs 自定义 API），互斥 */
@Composable
private fun SearchModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProviderPicker(
    providers: List<team.bhe.bhaistudio.data.db.entity.ProviderConfigEntity>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = providers.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.contact_provider_placeholder)

    Box {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.contact_provider_label)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // 透明点击层：让 readOnly 输入框也能弹出菜单
        Box(
            Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.filter { it.enabled }.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.width(6.dp))
                                // 可用性标记：未设置=?，可用=绿✓，不可用=红✕
                                val (statusText, statusColor) = when {
                                    provider.maskedApiKey.isBlank() -> "?" to MaterialTheme.colorScheme.outline
                                    provider.isAvailable == true -> "✓" to Color(0xFF2E7D32)
                                    provider.isAvailable == false -> "✕" to MaterialTheme.colorScheme.error
                                    else -> "?" to MaterialTheme.colorScheme.outline
                                }
                                Text(
                                    statusText,
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Text(
                                provider.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(provider.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        // 可编辑输入框：既能手输任意模型名，也能从下拉选
        OutlinedTextField(
            value = selected,
            onValueChange = onSelect,
            label = { Text(stringResource(R.string.contact_model_label)) },
            placeholder = {
                Text(
                    if (models.isEmpty()) stringResource(R.string.contact_model_placeholder_manual)
                    else stringResource(R.string.contact_model_placeholder_choose)
                )
            },
            trailingIcon = {
                if (models.isNotEmpty()) {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(R.string.contact_model_pick_desc)
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        expanded = false
                        onSelect(model)
                    }
                )
            }
        }
    }
}
