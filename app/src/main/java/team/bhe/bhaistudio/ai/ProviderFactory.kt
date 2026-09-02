package team.bhe.bhaistudio.ai

import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import team.bhe.bhaistudio.ai.model.ChatRequest
import team.bhe.bhaistudio.ai.model.ChatResult
import team.bhe.bhaistudio.ai.model.StreamChunk
import team.bhe.bhaistudio.data.db.entity.ProviderProtocol

/**
 * AI 请求入口——按协议分派到具体实现
 *
 * 参考 RikkaHub `ProviderManager.getProviderByType()` 的按类型分派：
 *   OPENAI    → [OpenAiProvider]   （DeepSeek / Kimi / 百炼 / Ollama / 中转站…）
 *   ANTHROPIC → [AnthropicProvider]（Claude 原生）
 *   GOOGLE    → [GoogleProvider]   （Gemini 原生）
 *
 * 新增协议时：写一个实现类 + 在 when 里加一行，上层与 UI 不用动。
 */
class ProviderFactory(
    client: OkHttpClient
) {

    private val openAi = OpenAiProvider(client)
    private val anthropic = AnthropicProvider(client)
    private val google = GoogleProvider(client)

    fun streamChat(
        endpoint: ProviderEndpoint,
        request: ChatRequest
    ): Flow<StreamChunk> = when (endpoint.protocol) {
        ProviderProtocol.OPENAI -> openAi.streamChat(endpoint, request)
        ProviderProtocol.ANTHROPIC -> anthropic.streamChat(endpoint, request)
        ProviderProtocol.GOOGLE -> google.streamChat(endpoint, request)
    }

    suspend fun chat(
        endpoint: ProviderEndpoint,
        request: ChatRequest
    ): ChatResult = when (endpoint.protocol) {
        ProviderProtocol.OPENAI -> openAi.chat(endpoint, request)
        ProviderProtocol.ANTHROPIC -> anthropic.chat(endpoint, request)
        ProviderProtocol.GOOGLE -> google.chat(endpoint, request)
    }
}
