package team.bhe.bhaistudio.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 分段回复调度器
 *
 * 移植自桌面端 `js/index.js` 的 `SegmentedReply`（第 2041 行起），算法保持一致。
 *
 * ── 它解决什么问题 ────────────────────────────────────────
 * 让 AI 的长回复像真人一样**分成多条消息依次发出**，
 * 而不是"一大坨一次性刷出来"或"一个字一个字匀速吐"。
 *
 * ── 桌面端的实现要点（已全部保留）──────────────────────────
 * 1. **代码块保护**：Markdown 代码块内部不按标点切碎，整块作为一段
 * 2. **中英标点**：`[。！？；.!?;]` 六类中英标点都认
 * 3. **平方根延迟**：`sqrt(字数) × 0.6` 秒——短句快、长句慢但增长平缓
 *    这是老 Android 版（线性 `(n-1)×0.5+0.5`）最重要的修正
 * 4. **随机抖动**：±30%，避免节奏机械
 * 5. **钳制**：0.8 ~ 6 秒
 *
 * ── 相对桌面端的改进 ──────────────────────────────────────
 * 桌面端用 `setTimeout` + `abortSignal` 手动检查取消；
 * 这里用协程 `delay` + `ensureActive`，作用域一取消就自动停止，
 * 不会出现"页面销毁了还在往旧列表里塞消息"的泄漏。
 */
class SegmentedReplyScheduler {

    /**
     * 智能切分：先按行处理代码块，再对普通文本按标点切分
     */
    fun splitTextSmart(text: String): List<String> {
        val segments = mutableListOf<String>()
        var current = StringBuilder()
        var inCodeBlock = false

        for (line in text.split("\n")) {
            val trimmed = line.trim()

            // 代码块边界
            if (trimmed.startsWith(CODE_FENCE)) {
                if (!inCodeBlock) {
                    // 进入代码块前，先把已累积的普通文本切掉
                    if (current.isNotBlank()) {
                        segments.addAll(splitByPunctuation(current.toString().trim()))
                        current.clear()
                    }
                    inCodeBlock = true
                    current.append(line).append('\n')
                } else {
                    // 代码块结束，整块作为一段
                    current.append(line)
                    segments.add(current.toString().trim())
                    current.clear()
                    inCodeBlock = false
                }
                continue
            }

            // 代码块内部原样累积，不参与标点切分
            if (inCodeBlock) {
                current.append(line).append('\n')
                continue
            }

            current.append(line).append('\n')

            // 普通文本：遇到句末标点即成段
            val accumulated = current.toString().trim()
            if (accumulated.isNotEmpty() && SENTENCE_END.containsMatchIn(accumulated)) {
                segments.add(accumulated)
                current.clear()
            }
        }

        // 收尾：未闭合的代码块或剩余文本，降级为按标点切分
        if (current.isNotBlank()) {
            segments.addAll(splitByPunctuation(current.toString().trim()))
        }

        // 一个都切不出来（比如没有标点），整体返回
        if (segments.isEmpty() && text.isNotBlank()) {
            return listOf(text.trim())
        }
        return segments
    }

    /**
     * 按标点切分普通文本，标点保留在段尾
     */
    private fun splitByPunctuation(text: String): List<String> {
        val matches = SENTENCE_PATTERN.findAll(text).toList()
        if (matches.isEmpty()) return listOf(text)

        val segments = mutableListOf<String>()
        var lastEnd = 0
        for (match in matches) {
            segments.add(match.value.trim())
            lastEnd = match.range.last + 1
        }
        // 末尾没有标点的残余部分
        text.substring(lastEnd).trim().takeIf { it.isNotEmpty() }?.let { segments.add(it) }
        return segments
    }

    /**
     * 计算一段文字显示后要停顿多久（秒）
     *
     * 平方根是关键：线性增长会让长句慢到离谱（老 Android 版 20 字要停 10 秒），
     * 平方根让长句的停顿增长平缓。
     *
     * 参考：20 字 ≈ 2.7 秒，100 字 ≈ 6 秒（触顶）
     */
    fun calculateDelay(text: String): Float {
        val base = sqrt(text.length.toFloat()) * DELAY_COEFFICIENT
        val jitter = JITTER_MIN + Random.nextFloat() * JITTER_RANGE
        return (base * jitter).coerceIn(MIN_DELAY, MAX_DELAY)
    }

    /**
     * 依次播放各段。
     *
     * @param segments 由 [splitTextSmart] 得到的分段
     * @param onSegment 回调，参数为该段内容与序号。每段落库即调用，不等下一段
     *
     * 取消方式：外部协程作用域 cancel 即可，无需额外信号。
     */
    suspend fun play(
        segments: List<String>,
        onSegment: suspend (segment: String, index: Int) -> Unit
    ) {
        for ((index, segment) in segments.withIndex()) {
            coroutineContext.ensureActive()
            onSegment(segment, index)

            if (index < segments.lastIndex) {
                delay((calculateDelay(segment) * 1000).toLong())
            }
        }
    }

    private companion object {
        const val CODE_FENCE = "```"

        /** 句末是否以标点结尾 */
        val SENTENCE_END = Regex("[。！？；.!?;]$")

        /** 匹配 "内容 + 标点" */
        val SENTENCE_PATTERN = Regex("[^。！？；.!?;]+[。！？；.!?;]")

        const val DELAY_COEFFICIENT = 0.6f
        const val JITTER_MIN = 0.7f
        const val JITTER_RANGE = 0.6f
        const val MIN_DELAY = 0.8f
        const val MAX_DELAY = 6f
    }
}
