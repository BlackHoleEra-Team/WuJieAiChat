package team.bhe.bhaistudio.ai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 对话请求
 *
 * 三家的字段差异很大（见各 Provider 实现），这里只定义**公共部分**，
 * 服务商独有参数由 `extras` 承载，避免接口为了兼容而臃肿。
 *
 * @param apiKey 明文密钥。仅在内存中流转，不落库、不写日志
 * @param model 模型名
 * @param history 历史消息（已含 system prompt）
 * @param enableThinking 深度思考
 * @param enableSearch 联网搜索
 * @param thinkingBudget 深度思考的 token 预算（Anthropic thinking.budget_tokens / Gemini thinkingBudget）
 * @param includeMemoryTool 是否声明 save_memory 工具（自动记忆开关）
 * @param useCustomSearch 是否使用自定义联网搜索 API（声明 web_search 工具，替代服务商内置搜索）
 * @param extras 服务商独有参数，如 DeepSeek 的 reasoning_effort、阿里云的 enable_search
 */
data class ChatRequest(
    val apiKey: String,
    val model: String,
    val history: List<ChatMessageDto>,
    val enableThinking: Boolean = false,
    val enableSearch: Boolean = false,
    val temperature: Float? = null,
    val topP: Float? = null,
    val thinkingBudget: Int = 0,
    val includeMemoryTool: Boolean = false,
    val useCustomSearch: Boolean = false,
    /** 已有记忆时声明 recall 工具（按需取回记忆正文），记忆为空时不声明避免空转 */
    val includeRecallTool: Boolean = false,
    val extras: Map<String, JsonElement> = emptyMap()
)

/** 主代理 web_search 工具参数：搜索关键词 */
@Serializable
data class WebSearchArgs(
    val query: String = ""
)

/** 主代理 recall 工具参数：想回忆的具体事情 */
@Serializable
data class RecallArgs(
    val query: String = ""
)

/** 主代理 rescore 工具参数：重新评定某记忆的重要性 */
@Serializable
data class RescoreArgs(
    val query: String = "",
    val importance: Float = 0.5f
)

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String = "",
    /**
     * assistant 的工具调用回合（标准 agent loop 回写用）：
     * 携带模型本轮要调用的工具清单，协议侧映射为
     * OpenAI `tool_calls` / Anthropic `tool_use` / Gemini `functionCall`。
     */
    val toolCalls: List<ChatToolCallRef> = emptyList(),
    /** 工具结果消息的标识：OpenAI `tool` 的 tool_call_id / Anthropic tool_use_id */
    val toolCallId: String? = null,
    /** 工具结果对应的工具名（Gemini functionResponse 需要） */
    val toolName: String? = null
)

/** assistant 工具调用回合里的一次调用引用（协议无关的中间表示） */
@Serializable
data class ChatToolCallRef(
    val id: String,
    val name: String,
    /** 完整 JSON 参数字符串 */
    val arguments: String
)

/**
 * 流式输出的块
 *
 * 桌面端用多回调（onMessage / onThinking / onComplete），
 * 且不同方法的回调语义还不一致（有的传增量、有的传累积全文）——这是踩坑源头。
 * Android 版统一为单一 sealed 流，语义明确：
 *   [Text] / [Thinking] **永远传增量**，由调用方负责拼接。
 */
sealed interface StreamChunk {

    /** 正文增量 */
    data class Text(val delta: String) : StreamChunk

    /** 思考过程增量（reasoning_content） */
    data class Thinking(val delta: String) : StreamChunk

    /**
     * 工具调用增量。
     *
     * arguments 是增量分片，由调用方按 index 拼接；
     * name / id 只可能在首个分片出现（不同协议：OpenAI 的 tool_calls.id、
     * Anthropic 的 tool_use.id；Gemini 无 id，用 name 配对）。
     */
    data class ToolCall(
        val index: Int,
        val name: String?,
        val argumentsDelta: String,
        /** 工具调用 id（OpenAI tool_calls.id / Anthropic tool_use.id，回写历史用） */
        val id: String? = null
    ) : StreamChunk

    /** 正常结束，携带服务商返回的真实 usage（流式拿不到时为 0） */
    data class Done(
        val inputTokens: Int = 0,
        val outputTokens: Int = 0
    ) : StreamChunk

    /** 出错，同时终止流 */
    data class Error(val message: String, val cause: Throwable? = null) : StreamChunk
}

/**
 * 非流式的完整结果
 *
 * 角色扮演模式（roleplay）下桌面端关闭流式，改用一次性返回再分段播放，
 * 所以这个结果类型是必需的。
 *
 * 模型可能只返回 [toolCalls] 而没有正文（自动记忆 / 联网搜索工具优先触发），
 * 调用方需要执行工具后追加一轮请求拿到最终回复，不能直接把空正文落库。
 */
data class ChatResult(
    val content: String,
    val thinkingContent: String? = null,
    /** 非流式响应中的工具调用（OpenAI 兼容协议） */
    val toolCalls: List<ChatToolCall> = emptyList(),
    /** 服务商返回的真实输入/输出 token（拿不到时为 0） */
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
)

/** 非流式响应里的一个工具调用 */
data class ChatToolCall(
    val index: Int = 0,
    val name: String = "",
    /** 完整 JSON 参数字符串 */
    val arguments: String = "",
    /** OpenAI tool_calls.id（部分厂商不回传时为空） */
    val id: String? = null
)

/**
 * 主代理 save_memory 工具调用的参数。
 *
 * 主代理负责"判断 + 划范围 + 说明重点"，内容由客户端的记忆子代理以主代理的身份整理：
 *   · [from] / [to] —— 要总结的对话消息编号区间（历史中 [1] 开始升序，可横跨多轮）
 *   · [note] —— 主代理以自己的视角告诉子代理"这段对话我理解到了什么、什么值得记住"。
 *     这是避免子代理瞎总结的关键：子代理拿不到对话时的心理活动，只能靠这条备注对齐视角
 *   · [longTerm] —— true 存长期记忆，false 存短期记忆
 *   · [remove] —— 已被新信息推翻的旧记忆内容（宽匹配删除）
 *   · [replace] —— 把旧记忆**更新**为新表述（原子替换，如搬家、换工作后修正）
 *
 * 三种操作可组合：新增 + 删除 + 更新。
 */
@Serializable
data class SaveMemoryArgs(
    val from: Int = 0,
    val to: Int = 0,
    val note: String? = null,
    val title: String? = null,
    val longTerm: Boolean = true,
    /** 重要性 0~1（可选）。给了会原样传给子代理落库；没给由子代理按规则自评 */
    val importance: Float? = null,
    val remove: List<String> = emptyList(),
    val replace: List<MemoryReplace> = emptyList()
)

/** 记忆更新：把 [old] 改写为 [new]（保留原记录，刷新时间戳） */
@Serializable
data class MemoryReplace(
    val old: String = "",
    val new: String = ""
)

/**
 * 上下文窗口占用状态（本地估算）。
 *
 * [used] 已用 token 数，[total] 模型上下文窗口大小，
 * [ratio] 占用比例，UI 据此驱动进度条与警告变色。
 */
data class ContextUsage(
    val used: Int,
    val total: Int
) {
    val ratio: Float get() = if (total <= 0) 0f else used.toFloat() / total
}

// ─────────────────────────────────────────────────────────
// OpenAI 兼容协议的响应体（三家共用）
// ─────────────────────────────────────────────────────────

@Serializable
data class ChatCompletionChunk(
    val choices: List<ChunkChoice> = emptyList(),
    /** 流式下通常只在最后一个 chunk 携带 */
    val usage: OpenAiUsage? = null
)

/** OpenAI 兼容协议的 token 用量（usage 字段） */
@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0
)

@Serializable
data class ChunkChoice(
    val delta: ChunkDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChunkDelta(
    val content: String? = null,

    /**
     * 思考过程。
     * DeepSeek / Kimi 为 reasoning_content，阿里云为 reasoning_content（兼容模式）。
     */
    @SerialName("reasoning_content") val reasoningContent: String? = null,

    /** 工具调用增量（OpenAI 兼容协议） */
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null
)

@Serializable
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: ToolCallFunctionDelta? = null
)

@Serializable
data class ToolCallFunctionDelta(
    val name: String? = null,
    /** JSON 字符串，流式下为增量分片 */
    val arguments: String? = null
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ResponseChoice> = emptyList(),
    val usage: OpenAiUsage? = null
)

@Serializable
data class ResponseChoice(
    val message: ResponseMessage? = null
)

@Serializable
data class ResponseMessage(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ResponseToolCall>? = null
)

@Serializable
data class ResponseToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ResponseToolCallFunction? = null
)

@Serializable
data class ResponseToolCallFunction(
    val name: String? = null,
    /** 非流式下为完整 JSON 参数字符串 */
    val arguments: String? = null
)
