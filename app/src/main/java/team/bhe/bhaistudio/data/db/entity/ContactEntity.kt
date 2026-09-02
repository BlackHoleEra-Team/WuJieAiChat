package team.bhe.bhaistudio.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 角色（联系人）
 *
 * 对应桌面端 ContactConfig/{id}.json。
 *
 * 与桌面端的关键差异：**角色与会话解耦**。
 * 桌面端"一个联系人 = 一段会话"，Android 版拆成 [ContactEntity] + [ConversationEntity]，
 * 这样多 Agent 群聊时，同一批角色可以出现在不同会话里，无需改动表结构。
 *
 * @param id 主键，与桌面端一致使用时间戳字符串，便于数据迁移
 * @param providerConfigId 关联的 [ProviderConfigEntity.id]——服务商、密钥、
 *   可用模型列表都在那里。角色只负责"人设 + 用哪个模型"
 * @param model 模型名
 * @param name 昵称（桌面端字段名为 nickname）
 * @param systemPrompt 人设
 * @param avatarUri 头像路径
 * @param roleplay 角色扮演开关。开启时：关闭流式、允许分段回复
 * @param webSearch 联网搜索
 * @param deepThink 深度思考
 * @param topP / temperature / thinkingBudget 高级参数，默认值取自桌面端
 */
@Entity(
    tableName = "contact",
    indices = [Index("createdAt")]
)
data class ContactEntity(
    @PrimaryKey val id: String,

    val providerConfigId: String,
    val model: String = "",

    val name: String = "",
    val systemPrompt: String = "",
    val avatarUri: String? = null,

    val roleplay: Boolean = false,

    /**
     * 关闭流式传输：开启后回复一次性完整出现（走非流式请求）。
     * 与角色扮演选择性联动——勾选角色扮演会自动开启，但可独立开关。
     */
    val disableStreaming: Boolean = false,

    /**
     * 延迟发送：与「关闭流式传输」互斥（必须先关流式才能启用）。
     * 开启后等待 AI 完整回复（延迟时长由 AI 的生成耗时决定），
     * 等待期间标题栏显示「正在输入…」。
     */
    val enableDelay: Boolean = false,

    val webSearch: Boolean = false,
    val deepThink: Boolean = false,

    /**
     * 联网搜索模式：true = 使用自定义搜索 API（Firecrawl/Tavily/Bing/serper），
     * false = 使用服务商内置搜索（需服务商支持）。
     * 仅在 [webSearch] 开启时生效。
     */
    val useCustomSearch: Boolean = false,

    val topP: Float = 0.8f,
    val temperature: Float = 0.7f,
    val thinkingBudget: Int = 4000,

    /**
     * 手动指定的上下文窗口大小（token）。
     * 0 = 按模型名自动推断（[team.bhe.bhaistudio.ai.ModelContextWindow]）。
     */
    val contextWindow: Int = 0,

    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long? = null,

    val isPinned: Boolean = false,
    val isMuted: Boolean = false
)
