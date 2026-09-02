package team.bhe.bhaistudio.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * Google 原生协议（Gemini generateContent API）
 *
 * 与 OpenAI / Anthropic 都不同：
 *   · 端点        POST /models/{model}:streamGenerateContent
 *   · 鉴权        头 x-goog-api-key
 *   · 角色名      用户是 "user"，模型是 "model"（不是 assistant）
 *   · system      独立字段 systemInstruction
 *   · 流式格式    SSE，无 [DONE]——连接关闭即结束
 *   · 思考过程    parts 中的 {thought: true, text: "..."}
 *
 * 注意：Google 的流式端点即使开了 `alt=sse` 仍是标准 SSE，
 * 用 okhttp-sse 解析即可。
 */
class GoogleProvider(
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
        val url = "${endpoint.baseUrl}/models/${request.model}:streamGenerateContent"
        val body = buildBody(request)

        val httpRequest = Request.Builder()
            .url(url)
            .header("x-goog-api-key", endpoint.apiKey)
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
                runCatching { json.decodeFromString<GoogleStreamChunk>(data) }
                    .onSuccess { chunk ->
                        // usageMetadata 在流式最后一个 chunk 携带完整用量
                        chunk.usageMetadata?.let {
                            if (it.promptTokenCount > 0) inputTokens = it.promptTokenCount
                            if (it.candidatesTokenCount > 0) outputTokens = it.candidatesTokenCount
                        }
                        chunk.candidates.firstOrNull()?.content?.parts?.forEach { part ->
                            when {
                                // functionCall 一次性给出完整 name + args
                                part.functionCall != null -> {
                                    val name = part.functionCall.name
                                    val args = part.functionCall.args?.toString().orEmpty()
                                    trySend(StreamChunk.ToolCall(0, name, args))
                                }

                                part.thought == true && !part.text.isNullOrBlank() ->
                                    trySend(StreamChunk.Thinking(part.text!!))

                                !part.text.isNullOrBlank() ->
                                    trySend(StreamChunk.Text(part.text!!))
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
                // Google 流式无 [DONE]，连接关闭即结束
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
        val url = "${endpoint.baseUrl}/models/${request.model}:generateContent"
        val httpRequest = Request.Builder()
            .url(url)
            .header("x-goog-api-key", endpoint.apiKey)
            .header("Content-Type", "application/json")
            .post(buildBody(request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}：${response.body?.string()}")
            }
            val parsed = json.decodeFromString<GoogleStreamChunk>(response.body?.string().orEmpty())
            var text = StringBuilder()
            var thinking = StringBuilder()
            parsed.candidates.firstOrNull()?.content?.parts?.forEach { part ->
                when {
                    part.thought == true && !part.text.isNullOrBlank() -> thinking.append(part.text)
                    !part.text.isNullOrBlank() -> text.append(part.text)
                }
            }
            return ChatResult(
                content = text.toString(),
                thinkingContent = thinking.toString().takeIf { it.isNotBlank() },
                inputTokens = parsed.usageMetadata?.promptTokenCount ?: 0,
                outputTokens = parsed.usageMetadata?.candidatesTokenCount ?: 0
            )
        }
    }

    /** Gemini 的 system 独立成 systemInstruction，角色名 user / model */
    private fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        val system = request.history
            .filter { it.role == "system" }
            .joinToString("\n") { it.content }
        if (system.isNotBlank()) {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", system) })
                })
            })
        }

        // 深度思考：thinkingConfig 启用（Flash/Pro 支持），includeThoughts 让思考块随流返回
        if (request.enableThinking || request.thinkingBudget > 0) {
            put("generationConfig", buildJsonObject {
                put("includeThoughts", true)
                if (request.thinkingBudget > 0) {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingBudget", request.thinkingBudget)
                    })
                }
            })
        }

        // 工具列表：搜索（自定义 functionDeclarations 或内置 googleSearch）+ 自动记忆
        val tools = mutableListOf<JsonObject>()
        if (request.useCustomSearch) {
            tools += WEB_SEARCH_TOOL
        } else if (request.enableSearch) {
            tools += buildJsonObject { put("googleSearch", buildJsonObject {}) }
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

        put("contents", buildJsonArray {
            request.history.filter { it.role != "system" }.forEach { msg ->
                when {
                    // assistant 工具调用回合 → model 的 functionCall part
                    msg.toolCalls.isNotEmpty() -> add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            msg.content.takeIf { it.isNotBlank() }?.let {
                                add(buildJsonObject { put("text", it) })
                            }
                            msg.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("functionCall", buildJsonObject {
                                        put("name", tc.name)
                                        put(
                                            "args",
                                            runCatching { json.decodeFromString<JsonObject>(tc.arguments) }
                                                .getOrElse { buildJsonObject { } }
                                        )
                                    })
                                })
                            }
                        })
                    })

                    // 工具执行结果 → user 的 functionResponse part（按函数名配对）
                    msg.role == "tool" -> add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", msg.toolName ?: "")
                                    put("response", buildJsonObject { put("result", msg.content) })
                                })
                            })
                        })
                    })

                    else -> add(buildJsonObject {
                        put("role", if (msg.role == "assistant") "model" else "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", msg.content) })
                        })
                    })
                }
            }
        })
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 自定义联网搜索工具：客户端执行第三方搜索 API 并回填结果 */
        private val WEB_SEARCH_TOOL: JsonObject = buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                add(buildJsonObject {
                    put("name", "web_search")
                    put("description", WEB_SEARCH_DESCRIPTION)
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "STRING")
                                put("description", "搜索关键词，用简洁的中文或英文")
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("query")) })
                    })
                })
            })
        }

        private const val WEB_SEARCH_DESCRIPTION =
            "搜索网络获取实时信息。当用户需要最新消息、天气、时事、或你不确定的事实核查时调用，不要编造。"

        /** recall 工具（Gemini functionDeclarations 格式） */
        private val RECALL_TOOL: JsonObject = buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                add(buildJsonObject {
                    put("name", "recall")
                    put("description", RECALL_TOOL_DESCRIPTION)
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "STRING")
                                put("description", "想回忆的具体事情，可用人物、话题或事件关键词描述")
                            })
                        })
                    })
                })
            })
        }

        private const val RECALL_TOOL_DESCRIPTION =
            "回忆你与对方之间过去发生的事。活跃记忆的检索索引写在系统提示里，但较早的记忆可能已随时间淡忘、不在索引中。" +
            "当对方问起过去的事（喜好、经历、约定等），或你感觉这件事好像之前提到过却记不清细节时调用本工具，" +
            "用关键词描述想回忆的事，系统会取回对应记忆正文，包括已淡忘的旧记忆。取回后仍没有相关内容就如实说不知道，不要编造。"

        /** rescore 工具（Gemini functionDeclarations 格式） */
        private val RESCORE_TOOL: JsonObject = buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                add(buildJsonObject {
                    put("name", "rescore")
                    put("description", RESCORE_TOOL_DESCRIPTION)
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "STRING")
                                put("description", "要重新评定的记忆，用内容或关键词描述")
                            })
                            put("importance", buildJsonObject {
                                put("type", "NUMBER")
                                put("description", "新的重要性 0~1")
                            })
                        })
                    })
                })
            })
        }

        private const val RESCORE_TOOL_DESCRIPTION =
            "重新评定某条记忆对你的重要性（0~1）。用于记忆价值随相处变化时：原来觉得重要的事后来不重要了，或反过来。系统会按描述找到相关记忆并更新分数，命中沉睡的记忆会一并唤醒。"

        /** 自动记忆工具（Gemini functionDeclarations 格式） */
        private val MEMORY_TOOL: JsonObject = buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                add(buildJsonObject {
                    put("name", "save_memory")
                    put("description", MEMORY_TOOL_DESCRIPTION)
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("importance", buildJsonObject {
                                put("type", "NUMBER")
                                put("description", "这段记忆的重要性 0~1（可选）：0.9+ 身份/承诺/决定性经历；0.6~0.8 稳定偏好与长期状态；0.3~0.6 普通有价值事件；<0.3 琐事。不确定就给个大概")
                            })
                            put("from", buildJsonObject {
                                put("type", "INTEGER")
                                put("description", "要总结的起始消息编号（历史中 [1] 开始升序）")
                            })
                            put("to", buildJsonObject {
                                put("type", "INTEGER")
                                put("description", "要总结的结束消息编号，一个事件可横跨多轮")
                            })
                            put("note", buildJsonObject {
                                put("type", "STRING")
                                put("description", MEMORY_NOTE_DESCRIPTION)
                            })
                            put("longTerm", buildJsonObject {
                                put("type", "BOOLEAN")
                                put("description", "true=长期记忆，false=短期记忆")
                            })
                            put("remove", buildJsonObject {
                                put("type", "ARRAY")
                                put("items", buildJsonObject { put("type", "STRING") })
                                put("description", "已被新信息推翻的旧记忆原文（可选）")
                            })
                            put("replace", buildJsonObject {
                                put("type", "ARRAY")
                                put("items", buildJsonObject {
                                    put("type", "OBJECT")
                                    put("properties", buildJsonObject {
                                        put("old", buildJsonObject {
                                            put("type", "STRING")
                                            put("description", "要更新的旧记忆原文")
                                        })
                                        put("new", buildJsonObject {
                                            put("type", "STRING")
                                            put("description", "更新后的新表述")
                                        })
                                    })
                                    put("required", buildJsonArray { add(JsonPrimitive("old")); add(JsonPrimitive("new")) })
                                })
                                put("description", "把旧记忆更新为新表述（可选），如搬家、换工作后修正")
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("from")); add(JsonPrimitive("to")) })
                    })
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

// ── Gemini 协议响应模型 ──────────────────────────────────────

@Serializable
private data class GoogleStreamChunk(
    val candidates: List<GoogleCandidate> = emptyList(),
    @SerialName("usageMetadata") val usageMetadata: GoogleUsageMetadata? = null
)

@Serializable
private data class GoogleUsageMetadata(
    @SerialName("promptTokenCount") val promptTokenCount: Int = 0,
    @SerialName("candidatesTokenCount") val candidatesTokenCount: Int = 0
)

@Serializable
private data class GoogleCandidate(
    val content: GoogleContent? = null
)

@Serializable
private data class GoogleContent(
    val parts: List<GooglePart> = emptyList()
)

@Serializable
private data class GooglePart(
    val text: String? = null,
    /** true 表示这是思考过程块 */
    val thought: Boolean? = null,
    /** 工具调用（Gemini 一次给出完整 name + args） */
    @SerialName("functionCall") val functionCall: GoogleFunctionCall? = null
)

@Serializable
private data class GoogleFunctionCall(
    val name: String? = null,
    val args: JsonObject? = null
)
