package team.bhe.bhaistudio.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 记忆（短期 / 长期）
 *
 * 对应桌面端 long-term-memories/{contactId}_memories.json 的数组元素，
 * 现在分两层：
 *   · [MemoryType.SHORT_TERM] 短期：近期的临时事项（正在进行的活动、短期计划），
 *     时效性高，注入时靠前
 *   · [MemoryType.LONG_TERM] 长期：稳定的跨会话信息（身份、价值观、重大经历），
 *     稳定可靠，是陪伴感的核心
 *
 * 生成方式：
 *   · 手动：聊天页「存为记忆」→ AI 对最近对话做摘要（默认长期）
 *   · 自动：主代理通过 save_memory 工具指定对话编号范围 → 记忆子代理总结存库
 *
 * @param contactId 归属的角色
 * @param indexSummary 覆盖性检索关键词（多钩子索引）：记忆子代理在总结时逐要点提炼，
 *   用「；」分隔，每条覆盖一个信息点，任一命中即可召回整条记忆；
 *   为空时由仓库从 [summary] 首行派生兜底
 * @param summary AI 生成的摘要正文（记忆本体）
 * @param originalMessages 被摘要的原始消息（JSON），保留以便回溯
 * @param memoryType 短期 / 长期
 * @param accessCount 被检索/调取次数，用于激活权重与遗忘曲线
 * @param lastAccessAt 最后被调取时间（null = 从未调取）
 */
@Entity(
    tableName = "memory",
    indices = [Index("contactId"), Index("createTime")]
)
data class MemoryEntity(
    @PrimaryKey val id: Long = System.currentTimeMillis(),

    val contactId: String,
    val summary: String = "",
    val indexSummary: String = "",
    val originalMessages: List<MemorySourceMessage> = emptyList(),
    val memoryType: MemoryType = MemoryType.LONG_TERM,

    val createTime: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val lastAccessAt: Long? = null,

    /**
     * 重要性打分 0.0~1.0（AI 保存时评定，可被 rescore 工具修正）。
     * 决定 ACTIVE 存活时长与沉没优先级：越高越不容易被遗忘。
     */
    val importance: Float = 0.5f,

    /** 可及性状态：ACTIVE（活跃可及）/ INACTIVE（沉睡）/ RECYCLED（拾忆区） */
    val state: MemoryState = MemoryState.ACTIVE,

    /** 进入当前状态的时间戳（0 = 尚未初始化，由状态机首刷时重算） */
    val enteredStateAt: Long = 0L,

    /** ACTIVE 到期时间戳（0 = 未排期，由状态机按 importance/accessCount 排期） */
    val activeUntil: Long = 0L,

    /** 进入拾忆区的时间（恢复时用于重新计时） */
    val recycledAt: Long? = null
)

/** 记忆分层：短期（临时的） / 长期（稳定的） */
enum class MemoryType {
    SHORT_TERM,
    LONG_TERM
}

/** 记忆可及性状态（生命周期状态机） */
enum class MemoryState {
    /** 活跃：索引进系统提示词，可被自动想起 */
    ACTIVE,

    /** 沉睡：平时不注入，可被 recall/browse 主动检索 */
    INACTIVE,

    /** 拾忆区：被遗忘/被删除的记忆，可恢复；数据不物理删除直到用户清空 */
    RECYCLED
}

/**
 * 记忆所引用的原始消息（快照，不随原消息删除而消失）
 *
 * Room 通过 [team.bhe.bhaistudio.data.db.Converters] 以 kotlinx.serialization
 * 序列化存储，必须标注 [Serializable]。
 */
@Serializable
data class MemorySourceMessage(
    val role: String,
    val content: String,
    val time: Long = System.currentTimeMillis()
)
