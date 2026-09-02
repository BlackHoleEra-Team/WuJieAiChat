package team.bhe.bhaistudio.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import team.bhe.bhaistudio.ai.model.ChatCompletionChunk
import team.bhe.bhaistudio.ai.model.ChatCompletionResponse
import team.bhe.bhaistudio.ai.model.OpenAiUsage
import team.bhe.bhaistudio.ai.model.ChatRequest
import team.bhe.bhaistudio.ai.model.ChatResult
import team.bhe.bhaistudio.ai.model.ChatToolCall
import team.bhe.bhaistudio.ai.model.StreamChunk
import team.bhe.bhaistudio.data.db.entity.ProviderProtocol
import team.bhe.bhaistudio.data.db.entity.SearchParamStyle
import team.bhe.bhaistudio.data.db.entity.ThinkingParamStyle

/**
 * 服务商端点（协议层的全部所需信息）
 *
 * 参考 RikkaHub `Provider<T : ProviderSetting>` 的无状态设计：
 * 配置作为参数传入，实现类不持有任何状态——
 * 同一个 [OpenAiProvider] 实例可以同时服务任意多个服务商配置。
 *
 * [apiKey] 为明文，仅在内存中流转；由 [team.bhe.bhaistudio.data.repository.ProviderConfigRepository]
 * 在发请求前解密，用完即弃。
 */
data class ProviderEndpoint(
    val baseUrl: String,
    val chatPath: String,
    val apiKey: String,
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI,
    val thinkingStyle: ThinkingParamStyle = ThinkingParamStyle.NONE,
    val searchStyle: SearchParamStyle = SearchParamStyle.NONE
)

/**
 * OpenAI 兼容协议的通用实现——整个 AI 层唯一需要的协议类
 *
 * DeepSeek / Kimi / 阿里云百炼 / Ollama / 任意中转站都提供 OpenAI 兼容端点，
 * SSE 格式完全一致（`data: {...}` + `[DONE]`）。
 * 桌面端为三家各写一个 API 类、且每家重复实现一遍 SSE 解析（js 下的 kimi-api.js 等）；
 * 这里的差异全部由 [ProviderEndpoint] 的风格字段驱动，一个类吃掉所有服务商。
 *
 * 以后若要接 Google / Anthropic 原生协议，再加一个同级实现类即可，
 * 上层（[ProviderFactory] 与全部 UI）无需改动。
 */
class OpenAiProvider(
    private val client: OkHttpClient
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // ─────────────────────────────────────────────────────
    // 流式
    // ─────────────────────────────────────────────────────

    fun streamChat(
        endpoint: ProviderEndpoint,
        request: ChatRequest
    ): Flow<StreamChunk> = callbackFlow {
        val body = buildBody(endpoint, request, stream = true)

        val httpRequest = Request.Builder()
            .url(endpoint.baseUrl + endpoint.chatPath)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var inputTokens = 0
        var outputTokens = 0

        val listener = object : EventSourceListener() {

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == DONE_SIGNAL) {
                    trySend(StreamChunk.Done(inputTokens, outputTokens))
                    close()
                    return
                }

                // 单个 chunk 解析失败不应中断整个流（服务商偶尔发空块或心跳）
                runCatching { json.decodeFromString<ChatCompletionChunk>(data) }
                    .onSuccess { chunk ->
                        // 流式 usage 通常只在最后一个 chunk 携带，取最后一次非空值
                        chunk.usage?.let {
                            if (it.promptTokens > 0) inputTokens = it.promptTokens
                            if (it.completionTokens > 0) outputTokens = it.completionTokens
                        }
                        val delta = chunk.choices.firstOrNull()?.delta ?: return
                        delta.reasoningContent?.let { trySend(StreamChunk.Thinking(it)) }
                        delta.content?.let { trySend(StreamChunk.Text(it)) }
                        delta.toolCalls?.forEach { toolCall ->
                            toolCall.function?.arguments?.let { args ->
                                trySend(
                                    StreamChunk.ToolCall(
                                        index = toolCall.index,
                                        name = toolCall.function?.name,
                                        argumentsDelta = args,
                                        id = toolCall.id
                                    )
                                )
                            }
                        }
                    }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val detail = response?.let { "HTTP ${it.code}" } ?: t?.message ?: "未知错误"
                trySend(StreamChunk.Error("请求失败：$detail", t))
                close(t)
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(StreamChunk.Done(inputTokens, outputTokens))
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(httpRequest, listener)

        // 流被取消（用户离开页面）时断开 SSE 连接，避免后台继续读流
        awaitClose { eventSource.cancel() }
    }

    // ─────────────────────────────────────────────────────
    // 非流式
    // ─────────────────────────────────────────────────────

    suspend fun chat(
        endpoint: ProviderEndpoint,
        request: ChatRequest
    ): ChatResult {
        val httpRequest = Request.Builder()
            .url(endpoint.baseUrl + endpoint.chatPath)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .header("Content-Type", "application/json")
            .post(buildBody(endpoint, request, stream = false).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}：${response.body?.string()}")
            }
            val raw = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<ChatCompletionResponse>(raw)
            val message = parsed.choices.firstOrNull()?.message
            val usage = parsed.usage
            return ChatResult(
                content = message?.content.orEmpty(),
                thinkingContent = message?.reasoningContent,
                // 非流式下模型可能先回工具调用（无正文），由上层执行工具后追加请求
                toolCalls = message?.toolCalls.orEmpty().map {
                    ChatToolCall(
                        index = it.index,
                        name = it.function?.name.orEmpty(),
                        arguments = it.function?.arguments.orEmpty(),
                        id = it.id
                    )
                },
                inputTokens = usage?.promptTokens ?: 0,
                outputTokens = usage?.completionTokens ?: 0
            )
        }
    }

    // ─────────────────────────────────────────────────────

    /**
     * 构造请求体。
     *
     * 桌面端把历史拼成文本塞进 system prompt（index.js:5730），
     * 这里改为标准 messages 数组——更符合各服务商原生协议。
     */
    private fun buildBody(
        endpoint: ProviderEndpoint,
        request: ChatRequest,
        stream: Boolean
    ): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", stream)

        put("messages", buildJsonArray {
            request.history.forEach { msg ->
                when {
                    // assistant 的工具调用回合：按 OpenAI 协议写 tool_calls 数组。
                    // 部分兼容服务严格要求 content 字段存在（可为 null），显式给 null 更稳
                    msg.toolCalls.isNotEmpty() -> add(buildJsonObject {
                        put("role", "assistant")
                        if (msg.content.isNotBlank()) put("content", msg.content) else put("content", JsonNull)
                        put("tool_calls", buildJsonArray {
                            msg.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    })
                                })
                            }
                        })
                    })

                    // 工具执行结果：role=tool + tool_call_id（标准 OpenAI 协议）
                    msg.role == "tool" -> add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", msg.toolCallId ?: "")
                        put("content", msg.content)
                    })

                    else -> add(buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            }
        })

        request.temperature?.let { put("temperature", it) }
        request.topP?.let { put("top_p", it) }

        // 深度思考：三家写法不同，风格来自服务商配置
        if (request.enableThinking) {
            when (endpoint.thinkingStyle) {
                ThinkingParamStyle.THINKING_OBJECT ->
                    put("thinking", buildJsonObject { put("type", "enabled") })

                ThinkingParamStyle.ENABLE_FLAG ->
                    put("enable_thinking", true)

                ThinkingParamStyle.NONE -> Unit
            }
        }

        // 工具列表：搜索工具（自定义或内置）+ 自动记忆工具，可并存
        val tools = mutableListOf<JsonObject>()

        if (request.useCustomSearch) {
            // 自定义联网搜索：声明 web_search 工具，客户端执行搜索并回填
            tools += WEB_SEARCH_TOOL
        } else if (request.enableSearch) {
            // 服务商内置搜索：三家机制不同
            when (endpoint.searchStyle) {
                SearchParamStyle.ENABLE_FLAG ->
                    put("enable_search", true)

                SearchParamStyle.KIMI_BUILTIN -> tools += KIMI_SEARCH_TOOL

                SearchParamStyle.NONE -> Unit
            }
        }

        // 自动记忆：声明 save_memory 工具，主代理可主动发起记忆
        if (request.includeMemoryTool) {
            tools += MEMORY_TOOL
        }

        // 已有记忆时声明 recall（按需取正文）+ rescore（重新评定重要性）
        if (request.includeRecallTool) {
            tools += RECALL_TOOL
            tools += RESCORE_TOOL
        }

        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray { tools.forEach { add(it) } })
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DONE_SIGNAL = "[DONE]"
        private const val WEB_SEARCH = "\$web_search"

        /** Kimi 内置联网搜索工具 */
        private val KIMI_SEARCH_TOOL: JsonObject = buildJsonObject {
            put("type", "builtin_function")
            put("function", buildJsonObject { put("name", WEB_SEARCH) })
        }

        /** 自定义联网搜索工具：客户端执行第三方搜索 API 并回填结果 */
        private val WEB_SEARCH_TOOL: JsonObject = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "web_search")
                put("description", WEB_SEARCH_DESCRIPTION)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "搜索关键词，用简洁的中文或英文")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("query")) })
                })
            })
        }

        private const val WEB_SEARCH_DESCRIPTION =
            "搜索网络获取实时信息。当用户需要最新消息、天气、时事、或你不确定的事实核查时调用，不要编造。"

        /** recall 工具：按需取回记忆正文（索引常驻，正文不注入） */
        private val RECALL_TOOL: JsonObject = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "recall")
                put("description", RECALL_TOOL_DESCRIPTION)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "想回忆的具体事情，可用人物、话题或事件关键词描述")
                        })
                    })
                    put("additionalProperties", false)
                })
            })
        }

        private const val RECALL_TOOL_DESCRIPTION =
            "回忆你与对方之间过去发生的事。活跃记忆的检索索引写在系统提示里，但较早的记忆可能已随时间淡忘、不在索引中。" +
            "当对方问起过去的事（喜好、经历、约定等），或你感觉这件事好像之前提到过却记不清细节时调用本工具，" +
            "用关键词描述想回忆的事，系统会取回对应记忆正文，包括已淡忘的旧记忆。取回后仍没有相关内容就如实说不知道，不要编造。"

        /** rescore 工具：AI 重新评定某条记忆的重要性 */
        private val RESCORE_TOOL: JsonObject = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "rescore")
                put("description", RESCORE_TOOL_DESCRIPTION)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "要重新评定的记忆，用内容或关键词描述")
                        })
                        put("importance", buildJsonObject {
                            put("type", "number")
                            put("description", "新的重要性 0~1")
                        })
                    })
                    put("additionalProperties", false)
                })
            })
        }

        private const val RESCORE_TOOL_DESCRIPTION =
            "重新评定某条记忆对你的重要性（0~1）。用于记忆价值随相处变化时：原来觉得重要的事后来不重要了，或反过来。系统会按描述找到相关记忆并更新分数，命中沉睡的记忆会一并唤醒。"

        /** 自动记忆工具：主代理自主决定"哪段对话值得存"，参数只给编号范围 */
        private val MEMORY_TOOL: JsonObject = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "save_memory")
                put("description", MEMORY_TOOL_DESCRIPTION)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("importance", buildJsonObject {
                            put("type", "number")
                            put("description", "这段记忆的重要性 0~1（可选）：0.9+ 身份/承诺/决定性经历；0.6~0.8 稳定偏好与长期状态；0.3~0.6 普通有价值事件；<0.3 琐事。不确定就给个大概")
                        })
                        put("from", buildJsonObject {
                            put("type", "integer")
                            put("description", "要总结的起始消息编号（历史中 [1] 开始升序）")
                        })
                        put("to", buildJsonObject {
                            put("type", "integer")
                            put("description", "要总结的结束消息编号，一个事件可横跨多轮")
                        })
                        put("note", buildJsonObject {
                            put("type", "string")
                            put("description", MEMORY_NOTE_DESCRIPTION)
                        })
                        put("longTerm", buildJsonObject {
                            put("type", "boolean")
                            put("description", "true=长期记忆，false=短期记忆")
                        })
                        put("remove", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("description", "已被新信息推翻的旧记忆原文（可选）")
                        })
                        put("replace", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("old", buildJsonObject {
                                        put("type", "string")
                                        put("description", "要更新的旧记忆原文")
                                    })
                                    put("new", buildJsonObject {
                                        put("type", "string")
                                        put("description", "更新后的新表述")
                                    })
                                })
                                put("required", buildJsonArray { add(JsonPrimitive("old")); add(JsonPrimitive("new")) })
                            })
                            put("description", "把旧记忆更新为新表述（可选），如搬家、换工作后修正")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("from")); add(JsonPrimitive("to")) })
                    put("additionalProperties", false)
                })
            })
        }

        private const val MEMORY_NOTE_DESCRIPTION =
            "用你自己的口吻直接写出这段对话里你真正想记住的是什么（1~3 条，可以就是你想记住的原话）。" +
            "from/to 范围内往往还夹杂别的闲聊，我会**只整理你这里写的内容**，不会把范围里的其它事也记进去。" +
            "所以这里写清楚重点即可，不确定就一句话概括"

        private const val MEMORY_TOOL_DESCRIPTION =
            "把值得记住的信息保存/更新为你的记忆。支持三种操作，可组合使用：" +
            "1. 新增：对话中出现值得记住的新信息时，用 from/to 指定该事件覆盖的消息编号范围（[1] 开始，可横跨多轮），" +
            "并用 note 以你自己的口吻说明这段对话你理解到了什么；" +
            "2. 删除：已有记忆已被新信息推翻时，把其原文放进 remove；" +
            "3. 更新：已有记忆表述发生变化时（如搬家、换工作），用 replace 把 old 改写为 new。" +
            "只记录真正重要、会影响你们未来相处的事实：身份与称呼、关系、重要经历、偏好、承诺、长期状态。" +
            "寒暄客套、临时信息、你自己刚说过的话的复述、对方一时的情绪都不要记。" +
            "已有记忆已在系统提示中列出，不要重复保存。没有值得记住的新内容就不要调用本工具。"
    }
}
