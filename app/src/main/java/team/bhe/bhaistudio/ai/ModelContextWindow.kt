package team.bhe.bhaistudio.ai

/**
 * 模型上下文窗口事实表（参考 CodeWhale 的 model_catalog / capability matrix）。
 *
 * 本地按模型名前缀匹配，匹配不到给默认 128k。
 * 窗口大小不精确到每家最新版，但足够驱动"是否该压缩"的判断。
 */
object ModelContextWindow {

    private const val DEFAULT = 128_000

    fun forModel(model: String): Int {
        val m = model.lowercase()
        return when {
            m.contains("claude") -> 200_000
            m.contains("gemini") || m.contains("gemma") -> 1_000_000
            m.contains("grok") -> 256_000
            m.contains("deepseek") -> 128_000
            m.contains("kimi") || m.contains("moonshot") -> 128_000
            m.contains("qwen") || m.contains("glm") || m.contains("doubao") -> 128_000
            m.contains("gpt") || m.contains("o1") || m.contains("o3") || m.contains("o4") -> 128_000
            m.contains("llama") || m.contains("mistral") -> 128_000
            m.contains("ernie") || m.contains("hunyuan") || m.contains("spark") || m.contains("minimax") -> 128_000
            m.contains("local-model") || m.contains("ollama") || m.contains("lmstudio") -> 32_000
            else -> DEFAULT
        }
    }
}
