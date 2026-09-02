package team.bhe.bhaistudio.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 自定义联网搜索配置
 *
 * 角色打开「自定义联网搜索」后，由客户端调用这里配置的第三方搜索 API
 * 获取实时信息，再把结果交给模型回答。支持四家：
 *   · Firecrawl —— POST api.firecrawl.dev/v1/search，Bearer 认证
 *   · Tavily    —— POST api.tavily.com/search，key 在请求体
 *   · Bing      —— GET api.bing.microsoft.com/v7.0/search，Ocp-Apim-Subscription-Key 头
 *   · serper    —— POST google.serper.dev/search，X-API-KEY 头
 *
 * 允许多条配置，启用时**按添加顺序取第一个**。
 *
 * @param apiKey 密文（AES-GCM，密钥由 Keystore 保管），仓库层解密后使用
 */
@Entity(tableName = "search_config")
data class SearchConfigEntity(
    @PrimaryKey val id: String,
    val provider: SearchProvider = SearchProvider.TAVILY,
    val apiKey: String = "",
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 支持的搜索服务商 */
enum class SearchProvider {
    FIRECRAWL,
    TAVILY,
    BING,
    SERPER;

    val displayName: String
        get() = when (this) {
            FIRECRAWL -> "Firecrawl"
            TAVILY -> "Tavily Search"
            BING -> "Bing Web Search API"
            SERPER -> "Serper (Google)"
        }
}
