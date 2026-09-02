package team.bhe.bhaistudio.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import team.bhe.bhaistudio.data.db.entity.ProviderConfigEntity
import team.bhe.bhaistudio.data.db.entity.ProviderProtocol
import java.util.concurrent.TimeUnit

/**
 * 服务商可用性测试器——用**最小 token** 请求验证密钥/网络是否可用。
 *
 * 三家协议各发一个只含 "hi" 的最短请求：
 *   · OpenAI 兼容：POST {baseUrl}{chatPath}，max_tokens=1
 *   · Anthropic：  POST {baseUrl}/v1/messages，max_tokens=1
 *   · Gemini：     POST {baseUrl}/models/{model}:generateContent
 *
 * 只用 HTTP 状态码判断（2xx = 可用），不解析响应体，不产生任何对话记录。
 * 测试请求带 15s 总超时，避免不可用的服务商拖住启动流程。
 */
class ApiTester(
    client: OkHttpClient
) {

    private val testClient = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 测试单个服务商。无密钥或没有可用模型时视为不可用。 */
    suspend fun test(config: ProviderConfigEntity, plainKey: String): Boolean =
        withContext(Dispatchers.IO) {
            if (plainKey.isBlank()) return@withContext false
            val model = config.models.firstOrNull() ?: return@withContext false
            runCatching {
                when (config.protocol) {
                    ProviderProtocol.OPENAI -> testOpenAi(config, model, plainKey)
                    ProviderProtocol.ANTHROPIC -> testAnthropic(config, model, plainKey)
                    ProviderProtocol.GOOGLE -> testGoogle(config, model, plainKey)
                }
            }.getOrDefault(false)
        }

    private fun testOpenAi(config: ProviderConfigEntity, model: String, key: String): Boolean {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 1)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "hi")
                })
            })
        }.toString()
        val request = Request.Builder()
            .url(config.baseUrl + config.chatPath)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private fun testAnthropic(config: ProviderConfigEntity, model: String, key: String): Boolean {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 1)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "hi")
                })
            })
        }.toString()
        val request = Request.Builder()
            .url(config.baseUrl + "/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private fun testGoogle(config: ProviderConfigEntity, model: String, key: String): Boolean {
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", "hi") })
                    })
                })
            })
        }.toString()
        val request = Request.Builder()
            .url("${config.baseUrl}/models/${model}:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): Boolean =
        testClient.newCall(request).execute().use { it.isSuccessful }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
