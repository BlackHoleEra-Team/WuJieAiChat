package team.bhe.bhaistudio.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 服务商配置
 *
 * 参考 RikkaHub 的 `ProviderSetting.OpenAI`（ai/provider/ProviderSetting.kt:54）：
 * **一个 OpenAI 兼容配置 = baseUrl + apiKey + 模型列表**。
 *
 * DeepSeek / Kimi / 百炼 / Ollama / 任意中转站，本质上都只是这套配置的不同取值。
 * 所以不再为每家写一个 Provider 类，而是把差异全部数据化——
 * 新增服务商时零代码，用户在设置页填个 baseUrl 就行。
 *
 * @param name 显示名，如 "DeepSeek"、"我的中转站"
 * @param baseUrl OpenAI 兼容根地址，如 `https://api.deepseek.com`
 * @param chatPath 聊天端点路径，绝大多数服务都是默认值
 * @param encryptedApiKey 密钥密文（AES-GCM，密钥由 Keystore 保管）
 * @param models 可用模型名列表。可从 `/models` 拉取，也可手填
 * @param isPreset 内置预设不可删除，只能改密钥
 * @param supportsSearch / supportsThinking 能力声明，UI 据此置灰开关
 * @param protocol 协议类型：OpenAI 兼容 / Anthropic 原生 / Google 原生
 * @param thinkingStyle 深度思考参数的字段风格。三家写法不同，见 [ThinkingParamStyle]
 * @param searchStyle 联网搜索参数的字段风格，见 [SearchParamStyle]
 */
@Entity(tableName = "provider_config")
data class ProviderConfigEntity(
    @PrimaryKey val id: String,

    val name: String,
    val baseUrl: String,
    val chatPath: String = "/chat/completions",

    val protocol: ProviderProtocol = ProviderProtocol.OPENAI,

    val encryptedApiKey: String = "",
    val maskedApiKey: String = "",

    val models: List<String> = emptyList(),

    val enabled: Boolean = true,
    val isPreset: Boolean = false,

    val supportsSearch: Boolean = false,
    val supportsThinking: Boolean = true,

    val thinkingStyle: ThinkingParamStyle = ThinkingParamStyle.THINKING_OBJECT,
    val searchStyle: SearchParamStyle = SearchParamStyle.NONE,

    /**
     * 服务商可用性测试结果：
     *   null = 未测试（尚未设置密钥 / 启动测试未完成）
     *   true = 可用
     *   false = 不可用（密钥错误 / 网络不通）
     */
    val isAvailable: Boolean? = null,

    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 协议类型
 *
 * OPENAI      OpenAI 兼容协议（覆盖 DeepSeek / Kimi / 百炼 / Ollama / 中转站等绝大多数）
 * ANTHROPIC   Claude 原生 messages API
 * GOOGLE      Gemini 原生 generateContent API
 */
enum class ProviderProtocol {
    OPENAI,
    ANTHROPIC,
    GOOGLE
}

/**
 * 深度思考参数的三种写法——这就是桌面端三个 API 类唯一的实质差异，
 * 数据化之后不再需要三个类：
 *   DeepSeek / Kimi → {"thinking": {"type": "enabled"}}
 *   阿里云百炼      → {"enable_thinking": true}
 *   不支持/不发送   → NONE
 */
enum class ThinkingParamStyle {
    NONE,
    THINKING_OBJECT,
    ENABLE_FLAG
}

/**
 * 联网搜索参数的三种写法：
 *   阿里云百炼 → {"enable_search": true}
 *   Kimi      → {"tools": [{"type": "builtin_function", "function": {"name": "$web_search"}}]}
 *   不支持     → NONE（DeepSeek 官方 API 无搜索能力）
 */
enum class SearchParamStyle {
    NONE,
    ENABLE_FLAG,
    KIMI_BUILTIN
}

/**
 * 内置服务商预设
 *
 * 对标 AIRI 的 53 个内置 provider 元数据（packages/stage-ui/src/libs/providers/providers/）：
 * baseUrl 内置好，用户只需填密钥。
 *
 * 说明：
 *   · 下方为 **OpenAI 兼容协议** 的常见服务商（30+ 家，含中文/国际/本地/聚合中转）
 *   · Anthropic / Google 原生协议预设见 [AnthropicPreset]/[GooglePreset]，由
 *     [team.bhe.bhaistudio.ai.AnthropicProvider]/[GoogleProvider] 实现
 *   · AIRI 中的音频类（ElevenLabs/Voicevox 等）与编码类服务商与聊天功能无关，
 *     待做语音时另行纳入
 *
 * 聚合中转（OpenRouter / 硅基流动 / 302.AI / AiHubMix）只需一把 key，
 * 即可访问旗下数百模型——这是最省的接法。
 */
object PresetProviders {

    data class Preset(
        val id: String,
        val name: String,
        val baseUrl: String,
        val models: List<String>,
        val supportsSearch: Boolean = false,
        val supportsThinking: Boolean = true,
        val thinkingStyle: ThinkingParamStyle = ThinkingParamStyle.THINKING_OBJECT,
        val searchStyle: SearchParamStyle = SearchParamStyle.NONE
    )

    /** 本地运行的服务，模拟器用 10.0.2.2 指代宿主机 localhost */
    private fun localHost(port: Int) = "http://10.0.2.2:$port"

    val ALL = listOf(
        // ── 中国大陆官方直连 ──
        Preset("preset-deepseek", "DeepSeek", "https://api.deepseek.com",
            models = listOf("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp")),
        Preset("preset-kimi", "Kimi (月之暗面)", "https://api.moonshot.cn/v1",
            models = listOf("kimi-k2.5", "kimi-k2-thinking"),
            supportsSearch = true, searchStyle = SearchParamStyle.KIMI_BUILTIN),
        Preset("preset-aliyun", "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf("qwen-plus", "qwen-max", "qwen-turbo"),
            supportsSearch = true, thinkingStyle = ThinkingParamStyle.ENABLE_FLAG,
            searchStyle = SearchParamStyle.ENABLE_FLAG),
        Preset("preset-zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
            models = listOf("glm-4-plus", "glm-4-air", "glm-4-flash")),
        Preset("preset-doubao", "豆包 (火山方舟)", "https://ark.cn-beijing.volces.com/api/v3",
            models = listOf("doubao-seed-1-6", "doubao-1-5-pro-32k", "doubao-1-5-lite-32k")),
        Preset("preset-minimax", "MiniMax", "https://api.minimaxi.com/v1",
            models = listOf("MiniMax-Text-01", "abab6.5s-chat")),
        Preset("preset-stepfun", "阶跃星辰", "https://api.stepfun.com/v1",
            models = listOf("step-2-16k", "step-1-8k")),
        Preset("preset-lingyi", "零一万物 Yi", "https://api.lingyiwanwu.com/v1",
            models = listOf("yi-large", "yi-medium", "yi-lightning")),
        Preset("preset-baichuan", "百川智能", "https://api.baichuan-ai.com/v1",
            models = listOf("Baichuan4-Turbo", "Baichuan3-Turbo-128K")),
        Preset("preset-hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1",
            models = listOf("hunyuan-turbo", "hunyuan-pro", "hunyuan-standard")),
        Preset("preset-qianfan", "百度千帆", "https://qianfan.baidubce.com/v2",
            models = listOf("ernie-4.0-turbo-8k", "ernie-3.5-8k", "ernie-speed-8k")),
        Preset("preset-spark", "讯飞星火", "https://spark-api-open.xf-yun.com/v1",
            models = listOf("max-32k", "generalv3.5", "lite")),
        Preset("preset-modelscope", "魔搭 ModelScope", "https://api-inference.modelscope.cn/v1",
            models = listOf("Qwen/Qwen3-235B-A22B-Instruct", "deepseek-ai/DeepSeek-V3.2")),

        // ── 国际官方直连 ──
        Preset("preset-openai", "OpenAI", "https://api.openai.com/v1",
            models = listOf("gpt-5", "gpt-5-mini", "gpt-5.6-sol")),
        Preset("preset-groq", "Groq", "https://api.groq.com/openai/v1",
            models = listOf("llama-4-maverick", "llama-4-scout", "deepseek-r1-distill-llama-70b")),
        Preset("preset-mistral", "Mistral", "https://api.mistral.ai/v1",
            models = listOf("mistral-large-latest", "mistral-small-latest")),
        Preset("preset-xai", "xAI (Grok)", "https://api.x.ai/v1",
            models = listOf("grok-4", "grok-4-fast")),
        // Perplexity 的 sonar 模型天然联网（内部自带搜索），无需也不支持额外参数，
        // 因此不声明 supportsSearch，避免「开了开关却不生效」的误导。
        Preset("preset-perplexity", "Perplexity", "https://api.perplexity.ai",
            models = listOf("sonar-pro", "sonar")),
        Preset("preset-together", "Together AI", "https://api.together.xyz/v1",
            models = listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "deepseek-ai/DeepSeek-V3")),
        Preset("preset-fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1",
            models = listOf("accounts/fireworks/models/llama-v3p3-70b-instruct")),
        Preset("preset-cerebras", "Cerebras", "https://api.cerebras.ai/v1",
            models = listOf("llama-3.3-70b", "llama-3.1-8b")),
        Preset("preset-nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1",
            models = listOf("meta/llama-3.3-70b-instruct", "deepseek-ai/deepseek-r1")),

        // ── 聚合中转（一把 key 用几百个模型）──
        Preset("preset-openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            models = listOf("anthropic/claude-sonnet-4-5", "openai/gpt-5", "deepseek/deepseek-chat")),
        Preset("preset-siliconflow", "硅基流动", "https://api.siliconflow.cn/v1",
            models = listOf("deepseek-ai/DeepSeek-V3.2", "Qwen/Qwen3-235B-A22B-Instruct", "THUDM/GLM-4-9B-Chat")),
        Preset("preset-302", "302.AI", "https://api.302.ai/v1",
            models = listOf("gpt-5", "deepseek-chat", "claude-sonnet-4-5")),
        Preset("preset-aihubmix", "AiHubMix", "https://aihubmix.com/v1",
            models = listOf("gpt-5", "claude-sonnet-4-5", "deepseek-chat")),

        // ── 本地 / 自建 ──
        Preset("preset-ollama", "Ollama（本地）", localHost(11434) + "/v1",
            models = listOf("qwen3:8b", "deepseek-r1:8b", "llama3.2:3b")),
        Preset("preset-lmstudio", "LM Studio（本地）", localHost(1234) + "/v1",
            models = listOf("local-model"))
    )

    /**
     * Anthropic 原生协议预设。
     * 不进 [ALL]（协议不同），由 [AnthropicProvider] 消费。
     */
    data class AnthropicPreset(
        val id: String = "preset-anthropic",
        val name: String = "Anthropic (Claude)",
        val baseUrl: String = "https://api.anthropic.com",
        val models: List<String> = listOf("claude-sonnet-4-5", "claude-opus-4-5", "claude-haiku-4-5"),
        val supportsThinking: Boolean = true,
        val supportsSearch: Boolean = true
    )

    /**
     * Google 原生协议预设。
     * 不进 [ALL]（协议不同），由 [GoogleProvider] 消费。
     */
    data class GooglePreset(
        val id: String = "preset-google",
        val name: String = "Google Gemini",
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        val models: List<String> = listOf("gemini-3.0-pro", "gemini-3.0-flash", "gemini-2.5-pro"),
        val supportsSearch: Boolean = true,
        val supportsThinking: Boolean = true
    )
}
