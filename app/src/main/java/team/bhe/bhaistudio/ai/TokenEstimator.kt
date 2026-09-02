package team.bhe.bhaistudio.ai

import kotlin.math.ceil

/**
 * 本地 token 估算（参考 CodeWhale compaction.rs）：
 *   · 正文约 4 字符 ≈ 1 token
 *   · system prompt 约 3 字符 ≈ 1 token
 *   · 每条消息固定 12 token 结构开销 + 整体 framing 48 token
 *
 * 不追求精确（不调 tokenizer），够驱动上下文窗口进度条与压缩阈值即可。
 */
object TokenEstimator {

    private const val FRAMING_OVERHEAD = 48
    private const val PER_MESSAGE_OVERHEAD = 12

    /** 正文 token 估算：~4 字符/token */
    fun text(text: String): Int = ceil(text.length / 4.0).toInt()

    /** system prompt token 估算：~3 字符/token */
    fun system(text: String): Int = ceil(text.length / 3.0).toInt()

    /** 历史消息总开销：每条消息 12 结构 + 正文估算，再加 framing 48 */
    fun history(messages: List<Pair<String, String>>): Int =
        messages.sumOf { (_, content) -> PER_MESSAGE_OVERHEAD + text(content) } + FRAMING_OVERHEAD

    /** 上下文总占用 = system + 历史 */
    fun total(systemText: String, messages: List<Pair<String, String>>): Int =
        system(systemText) + history(messages)
}
