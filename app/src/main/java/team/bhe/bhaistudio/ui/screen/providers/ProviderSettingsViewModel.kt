package team.bhe.bhaistudio.ui.screen.providers

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.ai.ApiTester
import team.bhe.bhaistudio.data.db.entity.ProviderConfigEntity
import team.bhe.bhaistudio.data.repository.ProviderConfigRepository
import team.bhe.bhaistudio.data.repository.withAppLanguage

/**
 * 服务商管理页
 *
 * 34 家预设（baseUrl 内置）+ 自定义服务商的统一入口。
 * 用户只需做一件事：**填密钥**。
 * 预设不可删除（[ProviderConfigEntity.isPreset]），可随时拉取/手填模型列表。
 */
class ProviderSettingsViewModel(
    context: Application,
    private val repository: ProviderConfigRepository,
    private val apiTester: ApiTester
) : AndroidViewModel(context) {

    /** 已设置密钥的服务商置顶，其余按添加顺序排在后面 */
    val providers: StateFlow<List<ProviderConfigEntity>> = repository.observeAll()
        .map { list -> list.sortedByDescending { it.maskedApiKey.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 卡片展开状态：id → expanded */
    val expanded = mutableStateMapOf<String, Boolean>()

    /** 各卡片忙碌标记（拉取模型中） */
    val busy = mutableStateMapOf<String, Boolean>()

    /** 各卡片提示（保存成功 / 拉取失败等） */
    val hint = mutableStateMapOf<String, String>()

    // ── 可用性测试弹窗状态 ──

    /** 是否正在测试服务商（弹加载圈） */
    var testing by mutableStateOf(false)
        private set

    /** 测试结果：null=未完成，true=可用，false=不可用 */
    var testResult by mutableStateOf<Boolean?>(null)
        private set

    /** 正在测试的服务商名 */
    var testingName by mutableStateOf("")
        private set

    /** 关闭测试结果弹窗 */
    fun dismissTestResult() {
        testResult = null
    }

    /** 密钥保存/清除成功：UI 据此清空对应卡片的输入框 */
    private val _savedKey = MutableSharedFlow<String>()
    val savedKey: SharedFlow<String> = _savedKey.asSharedFlow()

    fun toggle(id: String) {
        expanded[id] = expanded[id] != true
    }

    fun saveKey(id: String, plainKey: String) {
        val key = plainKey.trim()
        if (key.isBlank()) return
        viewModelScope.launch {
            val config = repository.getById(id) ?: return@launch
            testingName = config.name

            // 先测试再保存：弹窗显示加载圈 → 结果（✓/✕）
            testing = true
            testResult = null
            hint[id] = getApplication<Application>().withAppLanguage().getString(R.string.providers_hint_testing)
            val ok = apiTester.test(config, key)
            testing = false
            testResult = ok

            if (ok) {
                // 测试通过才落库，并自动拉取真实模型
                repository.saveApiKey(id, key)
                repository.setAvailability(id, true)
                hint[id] = getApplication<Application>().withAppLanguage().getString(R.string.providers_hint_ok_fetching)
                _savedKey.emit(id)
                fetchModels(id)
            } else {
                // 测试失败：不写库、不删除，已保存的密钥原样保留
                hint[id] = if (config.maskedApiKey.isNotBlank()) {
                    getApplication<Application>().withAppLanguage().getString(R.string.providers_hint_fail_keep)
                } else {
                    getApplication<Application>().withAppLanguage().getString(R.string.providers_hint_fail)
                }
            }
        }
    }

    /** 清除已设置的密钥 */
    fun clearKey(id: String) {
        viewModelScope.launch {
            repository.clearApiKey(id)
            _savedKey.emit(id)
        }
    }

    fun fetchModels(id: String) {
        viewModelScope.launch {
            busy[id] = true
            hint.remove(id)
            val config = repository.getById(id) ?: run {
                busy[id] = false
                return@launch
            }
            repository.fetchModels(config)
                .onSuccess { models ->
                    if (models.isEmpty()) {
                        hint[id] = getApplication<Application>().withAppLanguage().getString(R.string.providers_hint_models_empty)
                    } else {
                        repository.update(id, config.name, config.baseUrl, config.chatPath, models)
                        hint[id] = getApplication<Application>().withAppLanguage().getString(R.string.providers_hint_models_fetched, models.size)
                    }
                }
                .onFailure {
                    hint[id] = getApplication<Application>().withAppLanguage().getString(
                        R.string.providers_hint_models_error,
                        it.message ?: getApplication<Application>().withAppLanguage().getString(R.string.common_unknown_error)
                    )
                }
            busy[id] = false
        }
    }

    fun addCustom(name: String, baseUrl: String) {
        val n = name.trim()
        val b = baseUrl.trim()
        if (n.isBlank() || b.isBlank()) return
        viewModelScope.launch {
            repository.addCustom(n, b)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    // ── 阿里云百炼：新版接口（WorkspaceId 域名）切换 ──

    /** 阿里云百炼预设 id */
    private val aliyunPresetId = "preset-aliyun"

    /** 切换到新版 WorkspaceId 接口：https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1 */
    fun applyAliyunNewEndpoint(workspaceId: String) {
        val id = workspaceId.trim()
        if (id.isBlank()) return
        viewModelScope.launch {
            val config = repository.getById(aliyunPresetId) ?: return@launch
            repository.update(
                id = config.id,
                name = config.name,
                baseUrl = "https://$id.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                chatPath = config.chatPath,
                models = config.models
            )
            hint[aliyunPresetId] = getApplication<Application>().withAppLanguage().getString(R.string.providers_hint_aliyun_new)
        }
    }

    /** 恢复旧版公共域名：https://dashscope.aliyuncs.com/compatible-mode/v1 */
    fun resetAliyunLegacyEndpoint() {
        viewModelScope.launch {
            val config = repository.getById(aliyunPresetId) ?: return@launch
            repository.update(
                id = config.id,
                name = config.name,
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                chatPath = config.chatPath,
                models = config.models
            )
            hint[aliyunPresetId] = getApplication<Application>().withAppLanguage().getString(R.string.providers_hint_aliyun_legacy)
        }
    }
}
