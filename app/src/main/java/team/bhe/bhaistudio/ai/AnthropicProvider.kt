package team.bhe.bhaistudio.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import team.bhe.bhaistudio.ai.model.ChatRequest
import team.bhe.bhaistudio.ai.model.ChatResult
import team.bhe.bhaistudio.ai.model.StreamChunk

/**
 * Anthropic 原生协议（Claude messages API）
 *
 * 与 OpenAI 兼容协议完全不同：
 *   · 端点        POST /v1/messages
 *   · 鉴权        头 x-api-key + anthropic-version
 *   · 请求体      system 是独立字段，不在 messages 里
 *   · 流式事件     content_block_delta（text_delta / thinking_delta）
 *   · max_tokens  必填
 *
 * SSE 事件在 `data` 载荷里自带 `type` 字段，解析靠它。
 */
class AnthropicProvider(
    private val client: OkHttpClient
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun streamChat(
        endpoint: ProviderEndpoint,
        request: ChatRequest
    ): Flow<StreamChunk> = callbackFlow {
        val body = buildBody(request, stream = true)

        val httpRequest = Request.Builder()
            .url(endpoint.baseUrl + "/v1/messages")
            .header("x-api-key", endpoint.apiKey)
            .header("anthropic-version", "2023-06-01")
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
                // 解析失败忽略该事件，不中断流（可能有 ping 等）
                runCatching { json.decodeFromString<AnthropicChunk>(data) }
                    .onSuccess { chunk ->
                        // message_start 带 input_tokens；message_delta 带累计 output_tokens
                        chunk.message?.usage?.let { if (it.inputTokens > 0) inputTokens = it.inputTokens }
                        chunk.usage?.let { if (it.outputTokens > 0) outputTokens = it.outputTokens }

                        when (chunk.type) {
                            "content_block_start" -> {
                                // tool_use 起始块：name / id 在这里给出（id 供回写历史用）
                                if (chunk.contentBlock?.type == "tool_use") {
                                    chunk.contentBlock.name?.let {
                                        trySend(
                                            StreamChunk.ToolCall(
                                                index = chunk.index,
                                                name = it,
                                                argumentsDelta = "",
                                                id = chunk.contentBlock.id
                                            )
                                        )
                                    }
                                }
                            }

                            "content_block_delta" -> when (chunk.delta?.type) {
                                "text_delta" -> chunk.delta.text?.let {
                                    trySend(StreamChunk.Text(it))
                                }

                                "thinking_delta" -> chunk.delta.thinking?.let {
                                    trySend(StreamChunk.Thinking(it))
                                }

                                // tool_use 的 input 是分片 JSON 增量
                                "input_json_delta" -> chunk.delta.partialJson?.let {
                                    trySend(StreamChunk.ToolCall(chunk.index, null, it))
                                }
                            }

                            "message_stop" -> Unit // 由 onClosed 收尾
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
        awaitClose { eventSource.cancel() }
    }

    suspend fun chat(
        endpoint: ProviderEndpoint,
        request: ChatRequest
    ): ChatResult {
        val httpRequest = Request.Builder()
            .url(endpoint.baseUrl + "/v1/messages")
            .header("x-api-key", endpoint.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(buildBody(request, stream = false).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}：${response.body?.string()}")
            }
            val parsed = json.decodeFromString<AnthropicResponse>(response.body?.string().orEmpty())
            val text = parsed.content.firstOrNull { it.type == "text" }?.text.orEmpty()
            val thinking = parsed.content.firstOrNull { it.type == "thinking" }?.thinking
            return ChatResult(
                content = text,
                thinkingContent = thinking,
                inputTokens = parsed.usage?.inputTokens ?: 0,
                outputTokens = parsed.usage?.outputTokens ?: 0
            )
        }
    }

    /** Anthropic 的 system 是独立字段，messages 只保留 user/assistant */
    private fun buildBody(request: ChatRequest, stream: Boolean): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", MAX_TOKENS)
        put("stream", stream)

        // 深度思考（extended thinking）：需要模型支持，预算须小于 max_tokens
        if (request.enableThinking) {
            put("thinking", buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", request.thinkingBudget.coerceIn(1024, MAX_TOKENS - 256))
            })
        }

        // 工具列表：搜索（自定义 custom tool 或内置 server tool）+ 自动记忆（custom tool）
        val tools = mutableListOf<JsonObject>()
        if (request.useCustomSearch) {
            tools += CUSTOM_WEB_SEARCH_TOOL
        } else if (request.enableSearch) {
            tools += buildJsonObject { put("type", WEB_SEARCH_TOOL) }
        }
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

        val system = request.history
            .filter { it.role == "system" }
            .joinToString("\n") { it.content }
        if (system.isNotBlank()) put("system", system)

        put("messages", buildJsonArray {
            request.history.filter { it.role != "system" }.forEach { msg ->
                when {
                    // assistant 工具调用回合 → content 数组：text 块 + tool_use 块（含完整 input 参数）
                    msg.toolCalls.isNotEmpty() -> add(buildJsonObject {
                        put("role", "assistant")
                        put("content", buildJsonArray {
                            msg.content.takeIf { it.isNotBlank() }?.let {
                                add(buildJsonObject { put("type", "text"); put("text", it) })
                            }
                            msg.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put(
                                        "input",
                                        runCatching { json.decodeFromString<JsonObject>(tc.arguments) }
                                            .getOrElse { buildJsonObject { } }
                                    )
                                })
                            }
                        })
                    })

                    // 工具执行结果 → user 消息的 tool_result 块（Anthropic 协议）
                    msg.role == "tool" -> add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", msg.toolCallId ?: "")
                                put("content", msg.content)
                            })
                        })
                    })

                    else -> add(buildJsonObject {
                        put("role", msg.role) // user / assistant 与 Anthropic 一致
                        put("content", msg.content)
                    })
                }
            }
        })
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_TOKENS = 4096
        const val WEB_SEARCH_TOOL = "web_search_20250305"

        /** 自定义联网搜索工具：客户端执行第三方搜索 API 并回填结果 */
        private val CUSTOM_WEB_SEARCH_TOOL: JsonObject = buildJsonObject {
            put("type", "custom")
            put("name", "web_search")
            put("description", WEB_SEARCH_DESCRIPTION)
            put("input_schema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "搜索关键词，用简洁的中文或英文")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
            })
        }

        private const val WEB_SEARCH_DESCRIPTION =
            "搜索网络获取实时信息。当用户需要最新消息、天气、时事、或你不确定的事实核查时调用，不要编造。"

        /** recall 工具（Anthropic custom tool 格式） */
        private val RECALL_TOOL: JsonObject = buildJsonObject {
            put("type", "custom")
            put("name", "recall")
            put("description", RECALL_TOOL_DESCRIPTION)
            put("input_schema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "想回忆的具体事情，可用人物、话题或事件关键词描述")
                    })
                })
            })
        }

        private const val RECALL_TOOL_DESCRIPTION =
            "回忆你与对方之间过去发生的事。活跃记忆的检索索引写在系统提示里，但较早的记忆可能已随时间淡忘、不在索引中。" +
            "当对方问起过去的事（喜好、经历、约定等），或你感觉这件事好像之前提到过却记不清细节时调用本工具，" +
            "用关键词描述想回忆的事，系统会取回对应记忆正文，包括已淡忘的旧记忆。取回后仍没有相关内容就如实说不知道，不要编造。"

        /** rescore 工具（Anthropic custom tool 格式） */
        private val RESCORE_TOOL: JsonObject = buildJsonObject {
            put("type", "custom")
            put("name", "rescore")
            put("description", RESCORE_TOOL_DESCRIPTION)
            put("input_schema", buildJsonObject {
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
            })
        }

        private const val RESCORE_TOOL_DESCRIPTION =
            "重新评定某条记忆对你的重要性（0~1）。用于记忆价值随相处变化时：原来觉得重要的事后来不重要了，或反过来。系统会按描述找到相关记忆并更新分数，命中沉睡的记忆会一并唤醒。"

        /** 自动记忆工具（Anthropic custom tool 格式） */
        private val MEMORY_TOOL: JsonObject = buildJsonObject {
            put("type", "custom")
            put("name", "save_memory")
            put("description", MEMORY_TOOL_DESCRIPTION)
            put("input_schema", buildJsonObject {
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

// ── Anthropic 协议响应模型 ──────────────────────────────────

@Serializable
private data class AnthropicChunk(
    val type: String = "",
    val index: Int = 0,
    val delta: AnthropicDelta? = null,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
    /** message_delta 事件携带的累计 output_tokens */
    val usage: AnthropicUsage? = null,
    /** message_start 事件里的 message.usage 携带 input_tokens */
    val message: AnthropicMessageStart? = null
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0
)

@Serializable
private data class AnthropicMessageStart(
    val usage: AnthropicUsage? = null
)

@Serializable
private data class AnthropicDelta(
    val type: String = "",
    val text: String? = null,
    val thinking: String? = null,
    @SerialName("partial_json") val partialJson: String? = null
)

@Serializable
private data class AnthropicContentBlock(
    val type: String = "",
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null
)

@Serializable
private data class AnthropicResponse(
    val content: List<AnthropicContent> = emptyList(),
    val usage: AnthropicUsage? = null
)

@Serializable
private data class AnthropicContent(
    val type: String = "",
    val text: String? = null,
    val thinking: String? = null
)
