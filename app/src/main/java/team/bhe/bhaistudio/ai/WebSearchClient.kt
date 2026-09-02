package team.bhe.bhaistudio.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import team.bhe.bhaistudio.data.db.entity.SearchConfigEntity
import team.bhe.bhaistudio.data.db.entity.SearchProvider

/**
 * 自定义联网搜索执行器——把查询发给第三方搜索 API，返回格式化文本。
 *
 * 四家端点（2026 年验证仍有效）：
 *   · Firecrawl：POST https://api.firecrawl.dev/v1/search，Authorization: Bearer
 *   · Tavily：   POST https://api.tavily.com/search，key 放请求体
 *   · Bing：     GET  https://api.bing.microsoft.com/v7.0/search，Ocp-Apim-Subscription-Key 头
 *   · serper：   POST https://google.serper.dev/search，X-API-KEY 头
 *
 * 结果统一格式化为「序号. 标题 / URL / 摘要」文本，交给模型回答。
 */
class WebSearchClient(
    private val client: OkHttpClient
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(config: SearchConfigEntity, query: String): Result<String> = runCatching {
        if (config.apiKey.isBlank()) error("该搜索配置缺少 API Key")
        val formatted = withContext(Dispatchers.IO) {
            when (config.provider) {
                SearchProvider.FIRECRAWL -> firecrawl(config.apiKey, query)
                SearchProvider.TAVILY -> tavily(config.apiKey, query)
                SearchProvider.BING -> bing(config.apiKey, query)
                SearchProvider.SERPER -> serper(config.apiKey, query)
            }
        }
        if (formatted.isBlank()) error("「${config.provider.displayName}」没有搜索到结果")
        formatted
    }

    // ── 四家实现 ──

    private fun firecrawl(key: String, query: String): String {
        val body = buildJsonObject {
            put("query", query)
            put("limit", 5)
        }.toString()
        val raw = post("https://api.firecrawl.dev/v1/search", body, "Authorization" to "Bearer $key")
        val items = json.decodeFromString<FirecrawlResponse>(raw).data
        return format(items.map { SearchResult(it.title, it.url, it.description) })
    }

    private fun tavily(key: String, query: String): String {
        val body = buildJsonObject {
            put("api_key", key)
            put("query", query)
            put("max_results", 5)
            put("search_depth", "basic")
        }.toString()
        val raw = post("https://api.tavily.com/search", body)
        val items = json.decodeFromString<TavilyResponse>(raw).results
        return format(items.map { SearchResult(it.title, it.url, it.content) })
    }

    private fun bing(key: String, query: String): String {
        val url = "https://api.bing.microsoft.com/v7.0/search?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&count=5"
        val raw = get(url, "Ocp-Apim-Subscription-Key" to key)
        val items = json.decodeFromString<BingResponse>(raw).webPages?.value.orEmpty()
        return format(items.map { SearchResult(it.name, it.url, it.snippet) })
    }

    private fun serper(key: String, query: String): String {
        val body = buildJsonObject {
            put("q", query)
            put("num", 5)
        }.toString()
        val raw = post("https://google.serper.dev/search", body, "X-API-KEY" to key)
        val items = json.decodeFromString<SerperResponse>(raw).organic
        return format(items.map { SearchResult(it.title, it.link, it.snippet) })
    }

    // ── HTTP 封装 ──

    private fun post(url: String, body: String, vararg headers: Pair<String, String>): String {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        headers.forEach { builder.header(it.first, it.second) }
        return execute(builder.build())
    }

    private fun get(url: String, vararg headers: Pair<String, String>): String {
        val builder = Request.Builder().url(url)
        headers.forEach { builder.header(it.first, it.second) }
        return execute(builder.build())
    }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${text.take(300)}")
            }
            text
        }

    private fun format(results: List<SearchResult>): String =
        results.take(5).withIndex().joinToString("\n") { (index, r) ->
            "${index + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
        }

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// ── 各家响应模型 ──

@Serializable
private data class FirecrawlResponse(val data: List<FirecrawlItem> = emptyList())
@Serializable
private data class FirecrawlItem(val title: String = "", val url: String = "", val description: String = "")

@Serializable
private data class TavilyResponse(val results: List<TavilyItem> = emptyList())
@Serializable
private data class TavilyItem(val title: String = "", val url: String = "", val content: String = "")

@Serializable
private data class BingResponse(val webPages: BingWebPages? = null)
@Serializable
private data class BingWebPages(val value: List<BingItem> = emptyList())
@Serializable
private data class BingItem(val name: String = "", val url: String = "", val snippet: String = "")

@Serializable
private data class SerperResponse(val organic: List<SerperItem> = emptyList())
@Serializable
private data class SerperItem(val title: String = "", val link: String = "", val snippet: String = "")
