package team.bhe.bhaistudio.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import team.bhe.bhaistudio.ai.ApiTester
import team.bhe.bhaistudio.data.db.dao.ProviderConfigDao
import team.bhe.bhaistudio.data.db.entity.PresetProviders
import team.bhe.bhaistudio.data.db.entity.ProviderConfigEntity
import team.bhe.bhaistudio.data.db.entity.ProviderProtocol

/**
 * 服务商配置仓库
 *
 * 对应桌面端「三家 API 类 + localStorage.apiKeys」的合体，
 * 以及 RikkaHub 的 ProviderSetting 管理（models 可增删、可从 API 拉取）。
 *
 * 快捷填写能力来自 [seedPresetsIfEmpty]：首次启动把 DeepSeek / Kimi / 百炼 / Ollama
 * 的 baseUrl、默认模型、能力声明写入数据库，用户只需要填一个密钥。
 * 这与 AIRI 的 53 个内置 provider 预设是同一思路（它把 baseUrl 内置在 provider 工厂里）。
 */
class ProviderConfigRepository(
    private val dao: ProviderConfigDao,
    private val client: OkHttpClient
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<ProviderConfigEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<ProviderConfigEntity?> = dao.observeById(id)

    suspend fun getById(id: String): ProviderConfigEntity? = dao.getById(id)

    /** 全部配置（含密文），迁移导出时用 */
    suspend fun listAll(): List<ProviderConfigEntity> = dao.listAll()

    /**
     * 首次启动写入内置预设。
     * 幂等：已有配置时不做任何事。
     */
    suspend fun seedPresetsIfEmpty() {
        if (dao.count() > 0) return
        val now = System.currentTimeMillis()

        // OpenAI 兼容服务商（30 家）
        val openAiPresets = PresetProviders.ALL.map { preset ->
            ProviderConfigEntity(
                id = preset.id,
                name = preset.name,
                baseUrl = preset.baseUrl,
                models = preset.models,
                isPreset = true,
                supportsSearch = preset.supportsSearch,
                supportsThinking = preset.supportsThinking,
                thinkingStyle = preset.thinkingStyle,
                searchStyle = preset.searchStyle,
                createdAt = now
            )
        }

        // 原生协议服务商（Anthropic / Google）
        val anthropic = PresetProviders.AnthropicPreset()
        val google = PresetProviders.GooglePreset()
        val nativePresets = listOf(
            ProviderConfigEntity(
                id = anthropic.id,
                name = anthropic.name,
                baseUrl = anthropic.baseUrl,
                protocol = ProviderProtocol.ANTHROPIC,
                models = anthropic.models,
                isPreset = true,
                supportsSearch = anthropic.supportsSearch,
                supportsThinking = anthropic.supportsThinking,
                createdAt = now
            ),
            ProviderConfigEntity(
                id = google.id,
                name = google.name,
                baseUrl = google.baseUrl,
                protocol = ProviderProtocol.GOOGLE,
                models = google.models,
                isPreset = true,
                supportsSearch = google.supportsSearch,
                supportsThinking = google.supportsThinking,
                createdAt = now
            )
        )

        dao.insertAll(openAiPresets + nativePresets)
    }

    /**
     * 升级内置预设的默认模型列表（幂等，启动时调用）。
     *
     * 预设数据只在首次 seed 时写入数据库，之后代码更新预设不会自动同步，
     * 导致老用户看到过时的模型（如 DeepSeek 还是 deepseek-chat）。
     *
     * "温和升级"：仅当当前模型列表与新预设**完全没有交集**时（说明还是旧版
     * 预设数据、用户没有拉取/修改过），才覆盖为新预设——避免覆盖用户拉到的真实模型。
     */
    suspend fun upgradePresetModels() {
        val updates = PresetProviders.ALL.map { it.id to it.models } + listOf(
            PresetProviders.AnthropicPreset().id to PresetProviders.AnthropicPreset().models,
            PresetProviders.GooglePreset().id to PresetProviders.GooglePreset().models
        )
        updates.forEach { (id, models) ->
            val current = dao.getById(id) ?: return@forEach
            if (current.isPreset && current.models != models && current.models.none { it in models }) {
                dao.update(id, models, current.name, current.baseUrl, current.chatPath)
            }
        }
    }

    /** 给预设或自定义配置填入密钥 */
    suspend fun saveApiKey(configId: String, plainKey: String) {
        val config = dao.getById(configId) ?: return
        dao.insert(
            config.copy(
                encryptedApiKey = KeyCrypto.encrypt(plainKey),
                maskedApiKey = KeyCrypto.mask(plainKey)
            )
        )
    }

    /**
     * 取明文密钥，仅供 AI 请求使用。
     * 调用方不得将其写日志或任何持久化位置。
     */
    suspend fun getDecryptedKey(configId: String): String? {
        val config = dao.getById(configId) ?: return null
        if (config.encryptedApiKey.isBlank()) return null
        return KeyCrypto.decrypt(config.encryptedApiKey)
    }

    /**
     * 从服务商的 `/models` 端点拉取可用模型列表。
     *
     * 这是 RikkaHub `Provider.listModels()` 的等价实现。
     * OpenAI 兼容端点普遍支持该接口（DeepSeek / Kimi / 百炼 / Ollama / 中转站都行），
     * 返回格式统一为 `{ "data": [{ "id": "模型名" }] }`。
     *
     * 对齐 CodeWhale 的做法：URL 归一化——优先 `baseUrl/v1/models`（OpenAI 兼容规范），
     * 失败或空结果时回退 `baseUrl/models`（DeepSeek / Perplexity 等根路径端点）。
     */
    suspend fun fetchModels(config: ProviderConfigEntity): Result<List<String>> {
        val key = getDecryptedKey(config.id).orEmpty()
        var lastError: Throwable? = null
        for (url in modelsUrlCandidates(config.baseUrl)) {
            try {
                val models = fetchModelsFrom(url, key)
                if (models.isNotEmpty()) return Result.success(models)
                lastError = IllegalStateException("该端点返回了空模型列表")
            } catch (e: Throwable) {
                lastError = e
            }
        }
        return Result.failure(lastError ?: IllegalStateException("无法获取模型列表"))
    }

    private fun fetchModelsFrom(url: String, key: String): List<String> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<ModelsResponse>(body)
            parsed.data.map { it.id }.filter { it.isNotBlank() }.distinct().sorted()
        }
    }

    /**
     * 生成 `/models` 候选 URL。
     *  · baseUrl 已带显式 /vN 版本号（如 /v1、/v4）→ 直接在其后拼 /models
     *  · 否则 → 标准 /v1/models 优先，根路径 /models 兜底
     */
    private fun modelsUrlCandidates(baseUrl: String): List<String> {
        val base = baseUrl.trimEnd('/')
        val hasVersion = Regex(".*/v\\d+$").matches(base)
        val candidates = mutableListOf<String>()
        if (!hasVersion) candidates += "$base/v1/models"
        candidates += "$base/models"
        return candidates.distinct()
    }

    /** 更新模型列表与基础信息 */
    suspend fun update(
        id: String,
        name: String,
        baseUrl: String,
        chatPath: String,
        models: List<String>
    ) = dao.update(id, models.distinct(), name, baseUrl, chatPath)

    /**
     * 新增自定义 OpenAI 兼容服务（中转站 / 自建网关等）。
     * 快捷填写的另一个入口：除了内置预设，任意兼容服务都能这样接入。
     */
    suspend fun addCustom(
        name: String,
        baseUrl: String,
        models: List<String> = emptyList()
    ): ProviderConfigEntity {
        val entity = ProviderConfigEntity(
            id = "custom-${System.currentTimeMillis()}",
            name = name,
            baseUrl = baseUrl.trimEnd('/'),
            models = models,
            isPreset = false,
            createdAt = System.currentTimeMillis()
        )
        dao.insert(entity)
        return entity
    }

    /** 预设不可删除，只能停用 */
    suspend fun delete(id: String) {
        val config = dao.getById(id) ?: return
        if (!config.isPreset) dao.deleteById(id)
    }

    /** 更新服务商可用性测试结果 */
    suspend fun setAvailability(id: String, available: Boolean?) {
        val config = dao.getById(id) ?: return
        dao.insert(config.copy(isAvailable = available))
    }

    /** 清除服务商密钥（回到未设置状态） */
    suspend fun clearApiKey(configId: String) {
        val config = dao.getById(configId) ?: return
        dao.insert(
            config.copy(
                encryptedApiKey = "",
                maskedApiKey = "",
                isAvailable = null
            )
        )
    }

    /**
     * 对已设置密钥的服务商逐个做最小请求测试（App 启动时调用）。
     * 跳过未设置密钥的配置（保留 null = 未测试，UI 显示 "?"）。
     */
    suspend fun testAllConfigured(tester: ApiTester) {
        dao.listAll()
            .filter { it.encryptedApiKey.isNotBlank() }
            .forEach { config ->
                val key = KeyCrypto.decrypt(config.encryptedApiKey)
                val ok = if (key != null) tester.test(config, key) else false
                dao.insert(config.copy(isAvailable = ok))
            }
    }

    @Serializable
    private data class ModelsResponse(
        val data: List<ModelEntry> = emptyList()
    )

    @Serializable
    private data class ModelEntry(
        val id: String = ""
    )
}
