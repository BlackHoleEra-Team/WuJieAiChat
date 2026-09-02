package team.bhe.bhaistudio.ui.screen.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.data.db.entity.ProviderConfigEntity

/**
 * 服务商管理——30 家预设的快捷填写入口
 *
 * 每个服务商 baseUrl 已内置（对标 AIRI 的 provider 元数据），
 * 用户展开卡片 → 填密钥 → 可选拉取模型列表 → 完事。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProviderSettingsScreen(onBack: () -> Unit) {
    val vm: ProviderSettingsViewModel = koinViewModel()
    val providers by vm.providers.collectAsStateWithLifecycle()
    // 每个卡片的 key 输入框状态
    val keyInputs = remember { mutableStateMapOf<String, String>() }

    // 已飞入过的卡片 id：飞过一次后，滚动回来不再重复动画
    val flownIds = remember { mutableStateMapOf<String, Boolean>() }

    // 密钥保存/清除成功后清空对应卡片的输入框
    LaunchedEffect(Unit) {
        vm.savedKey.collect { id -> keyInputs[id] = "" }
    }

    // 搜索词
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(providers, query) {
        val q = query.trim()
        if (q.isEmpty()) providers
        else providers.filter {
            it.name.contains(q, ignoreCase = true) || it.baseUrl.contains(q, ignoreCase = true)
        }
    }

    // 首次数据到达后仍展示至少 MinLoadingMillis 的加载圈（避免一闪而过），
    // 期间列表未渲染，天然阻隔继续操作
    var showList by remember { mutableStateOf(false) }
    LaunchedEffect(providers.isNotEmpty()) {
        if (providers.isNotEmpty()) {
            delay(MinLoadingMillis)
            showList = true
        }
    }

    // 可用性测试弹窗：测试中显示波浪加载圈，结束后显示 ✓/✕ 结果
    if (vm.testing) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.providers_testing_title)) },
            text = {
                Text(stringResource(R.string.providers_testing_body, vm.testingName))
            },
            confirmButton = {}
        )
    } else if (vm.testResult != null) {
        val ok = vm.testResult == true
        AlertDialog(
            onDismissRequest = { vm.dismissTestResult() },
            title = {
                Text(
                    if (ok) stringResource(R.string.providers_test_ok_title)
                    else stringResource(R.string.providers_test_fail_title)
                )
            },
            text = {
                Text(
                    text = if (ok) {
                        stringResource(R.string.providers_test_result_ok, vm.testingName)
                    } else {
                        stringResource(R.string.providers_test_result_fail, vm.testingName)
                    },
                    color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissTestResult() }) {
                    Text(stringResource(R.string.providers_ok))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.providers_title)) },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            // 搜索框固定顶部：加载过程中也保持可见
            ExpressiveSearchBar(
                query = query,
                onQueryChange = { query = it }
            )
            Spacer(Modifier.height(10.dp))

            if (!showList) {
                // 加载中：MD3E 波浪形空心圆环加载指示器
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }
            } else {
                // 加载完成：整列向上滑入
                AnimatedVisibility(
                    visible = showList,
                    enter = slideInVertically(
                        animationSpec = tween(400, easing = EmphasizedDecelerate)
                    ) { it } + fadeIn(tween(400)),
                    label = "providers-in"
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.providers_intro, providers.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }

                        if (filtered.isEmpty() && query.isNotBlank()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 28.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.providers_no_match, query.trim()),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        itemsIndexed(filtered, key = { _, provider -> provider.id }) { index, provider ->
                            // 首次出现：交错从右侧飞入；飞过一次后直接显示（滚动回来不再重复动画）
                            val alreadyFlown = flownIds[provider.id] == true
                            var visible by remember(provider.id) { mutableStateOf(alreadyFlown) }
                            if (!alreadyFlown) {
                                LaunchedEffect(provider.id) {
                                    delay((index % 12) * 35L)
                                    visible = true
                                    flownIds[provider.id] = true
                                }
                            }
                            AnimatedVisibility(
                                visible = visible,
                                enter = slideInHorizontally(
                                    tween(320, easing = EmphasizedDecelerate)
                                ) { it / 3 } + fadeIn(tween(320))
                            ) {
                                ProviderCard(
                                    provider = provider,
                                    keyInput = keyInputs[provider.id] ?: "",
                                    onKeyInput = { keyInputs[provider.id] = it },
                                    expanded = vm.expanded[provider.id] == true,
                                    busy = vm.busy[provider.id] == true,
                                    hint = vm.hint[provider.id],
                                    onToggle = { vm.toggle(provider.id) },
                                    onSaveKey = { vm.saveKey(provider.id, it) },
                                    onClearKey = { vm.clearKey(provider.id) },
                                    onFetchModels = { vm.fetchModels(provider.id) },
                                    onDelete = { vm.delete(provider.id) },
                                    onApplyAliyunNew = if (provider.id == "preset-aliyun") {
                                        vm::applyAliyunNewEndpoint
                                    } else null,
                                    onResetAliyunLegacy = if (provider.id == "preset-aliyun") {
                                        vm::resetAliyunLegacyEndpoint
                                    } else null
                                )
                            }
                        }

                        item {
                            val alreadyFlown = flownIds["__add_custom__"] == true
                            var visible by remember { mutableStateOf(alreadyFlown) }
                            if (!alreadyFlown) {
                                LaunchedEffect(Unit) {
                                    visible = true
                                    flownIds["__add_custom__"] = true
                                }
                            }
                            AnimatedVisibility(
                                visible = visible,
                                enter = slideInHorizontally(
                                    tween(320, easing = EmphasizedDecelerate)
                                ) { it / 3 } + fadeIn(tween(320))
                            ) {
                                AddCustomCard(
                                    onAdd = { name, url -> vm.addCustom(name, url) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * MD3E（Material 3 Expressive）搜索栏
 *
 * 对标 Expressive Search Bar 的视觉语言：
 *   · extraLarge（32dp）大圆角，无描边、无指示线
 *   · surfaceContainerHigh tonal 底色，聚焦时轻微抬高容器
 *   · 占位符居中（Expressive 特征），输入后自然退到左侧
 *   · 聚焦时弹性放大（spring 弹性曲线，配合 MotionScheme.expressive）
 */
@Composable
private fun ExpressiveSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    // Expressive 动效：聚焦时轻微弹性放大，失焦回落
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "searchbar-scale"
    )

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // 整条可点：点空白区域也能聚焦输入
            .clickable(interactionSource = null, indication = null) {
                focusRequester.requestFocus()
            },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        shape = MaterialTheme.shapes.extraLarge,
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = scheme.surfaceContainerHigh,
            focusedContainerColor = scheme.surfaceContainerHighest,
            unfocusedIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = scheme.primary,
            focusedTextColor = scheme.onSurface,
            unfocusedTextColor = scheme.onSurface,
            focusedPlaceholderColor = scheme.onSurfaceVariant,
            unfocusedPlaceholderColor = scheme.onSurfaceVariant
        ),
        interactionSource = interactionSource,
        placeholder = {
            Text(
                text = stringResource(R.string.providers_search_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = if (isFocused) scheme.primary else scheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.providers_clear_search),
                        tint = scheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

// ─────────────────────────────────────────────────────────

@Composable
private fun ProviderCard(
    provider: ProviderConfigEntity,
    keyInput: String,
    onKeyInput: (String) -> Unit,
    expanded: Boolean,
    busy: Boolean,
    hint: String?,
    onToggle: () -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onFetchModels: () -> Unit,
    onDelete: () -> Unit,
    onApplyAliyunNew: ((String) -> Unit)? = null,
    onResetAliyunLegacy: (() -> Unit)? = null
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // 头部：点击展开
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(provider.name, style = MaterialTheme.typography.titleMedium)
                        if (provider.isPreset) {
                            Spacer(Modifier.width(6.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text(stringResource(R.string.providers_preset_chip)) }
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // 可用性标记：未设置=?，可用=绿色✓，不可用=红色✕
                        val (statusText, statusColor) = when {
                            provider.maskedApiKey.isBlank() -> "?" to MaterialTheme.colorScheme.outline
                            provider.isAvailable == true -> "✓" to Color(0xFF2E7D32)
                            provider.isAvailable == false -> "✕" to MaterialTheme.colorScheme.error
                            else -> "?" to MaterialTheme.colorScheme.outline
                        }
                        Text(
                            text = statusText,
                            color = statusColor,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        provider.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 丝滑展开/收起：垂直展开 + 淡入（MD3 动效）
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(300, easing = EmphasizedDecelerate)
                ) + fadeIn(tween(300, easing = EmphasizedDecelerate)),
                exit = shrinkVertically(
                    animationSpec = tween(250, easing = EmphasizedAccelerate)
                ) + fadeOut(tween(250))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isConfigured = provider.maskedApiKey.isNotBlank()
                    val editing = keyInput.isNotBlank()

                    if (isConfigured && !editing) {
                        // 已设置：显示「已设置 API-Key」+ 清除按钮，输入框与保存按钮隐藏
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.providers_key_set), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    provider.maskedApiKey,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = onClearKey,
                                enabled = !busy
                            ) { Text(stringResource(R.string.providers_key_clear)) }
                        }
                    } else {
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = onKeyInput,
                            label = { Text("API Key") },
                            placeholder = { Text(if (isConfigured) provider.maskedApiKey else "sk-…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { onSaveKey(keyInput) },
                                enabled = keyInput.isNotBlank()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.providers_save_key))
                            }
                            OutlinedButton(
                                onClick = onFetchModels,
                                enabled = !busy
                            ) {
                                Text(
                                    if (busy) stringResource(R.string.providers_fetching)
                                    else stringResource(R.string.providers_fetch_models)
                                )
                            }
                        }
                    }

                    if (provider.models.isNotEmpty()) {
                        // 模型可能很多（聚合服务商几百个），只预览前几个，避免撑爆卡片
                        val preview = provider.models.take(3).joinToString("、")
                        val text = if (provider.models.size > 3) {
                            stringResource(
                                R.string.providers_models_more,
                                preview,
                                provider.models.size
                            )
                        } else {
                            stringResource(R.string.providers_models_preview, preview)
                        }
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 预设降级为"默认建议"：真实模型以拉取结果为准
                    if (provider.isPreset) {
                        Text(
                            stringResource(R.string.providers_preset_models_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }

                    // 阿里云百炼：新版接口（WorkspaceId 域名）快捷切换
                    if (provider.id == "preset-aliyun" && onApplyAliyunNew != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        val isNew = provider.baseUrl.contains("maas.aliyuncs.com")
                        var workspaceId by remember { mutableStateOf("") }
                        Text(
                            stringResource(R.string.providers_aliyun_new_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (isNew) {
                                stringResource(R.string.providers_aliyun_new_active)
                            } else {
                                stringResource(R.string.providers_aliyun_new_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = workspaceId,
                                onValueChange = { workspaceId = it },
                                label = { Text("WorkspaceId") },
                                placeholder = { Text("mb-xxxxx") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(
                                onClick = {
                                    onApplyAliyunNew(workspaceId)
                                    workspaceId = ""
                                },
                                enabled = workspaceId.isNotBlank()
                            ) { Text(stringResource(R.string.providers_apply)) }
                        }
                        if (isNew && onResetAliyunLegacy != null) {
                            TextButton(onClick = onResetAliyunLegacy) {
                                Text(stringResource(R.string.providers_restore_legacy))
                            }
                        }
                    }

                    hint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (!provider.isPreset) {
                        OutlinedButton(onClick = onDelete) {
                            Text(stringResource(R.string.providers_delete_custom))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCustomCard(onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.providers_add_custom_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                stringResource(R.string.providers_add_custom_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.providers_name_label)) },
                placeholder = { Text(stringResource(R.string.providers_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Base URL") },
                placeholder = { Text("https://your-gateway.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            FilledTonalButton(
                onClick = {
                    onAdd(name, url)
                    name = ""
                    url = ""
                },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.providers_add))
            }
        }
    }
}

/** MD3 标准缓动曲线（The motion system · Motion tokens） */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** 加载圈最短展示时长（ms）：数据到了也先转这么久再滑入，避免一闪而过 */
private const val MinLoadingMillis = 600L
