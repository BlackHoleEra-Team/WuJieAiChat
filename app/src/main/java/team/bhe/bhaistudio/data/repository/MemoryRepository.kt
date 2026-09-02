package team.bhe.bhaistudio.data.repository

import kotlin.jvm.Volatile
import kotlinx.coroutines.flow.Flow
import team.bhe.bhaistudio.data.db.dao.MemoryDao
import team.bhe.bhaistudio.data.db.entity.MemoryEntity
import team.bhe.bhaistudio.data.db.entity.MemoryState
import team.bhe.bhaistudio.data.db.entity.MemoryType

/**
 * 记忆仓库
 *
 * 对应桌面端的 add-long-term-memory / get-long-term-memories / delete-long-term-memory，
 * 现在分短期（[MemoryType.SHORT_TERM]）与长期（[MemoryType.LONG_TERM]）两层。
 *
 * 记忆的生成（AI 摘要）不在本类，而在 `ChatRepository`，
 * 因为需要用到联系人的模型与密钥发起 AI 请求。
 */
class MemoryRepository(
    private val dao: MemoryDao
) {

    // ─────────────────────────────────────────────────────
    // 主键分配
    // ─────────────────────────────────────────────────────

    /** 上一次分配的主键，保证同毫秒内多条新记忆不会因 [System.currentTimeMillis] 重复而互相覆盖 */
    @Volatile
    private var lastMemoryId = 0L

    /** 生成单调递增的记忆主键（[MemoryEntity.id] 默认值是时间戳，同毫秒并发会撞主键被 REPLACE 覆盖） */
    @Synchronized
    fun nextMemoryId(): Long {
        val now = System.currentTimeMillis()
        lastMemoryId = if (now > lastMemoryId) now else lastMemoryId + 1
        return lastMemoryId
    }

    // ─────────────────────────────────────────────────────
    // 记忆生命周期（ACTIVE → INACTIVE → RECYCLED）
    // ─────────────────────────────────────────────────────

    /** 沉睡多久后低分记忆进拾忆区 */
    private val recycleAfterMs = 90L * 24 * 3600_000

    /** 低于该分数才可能被自动送进拾忆区（高分沉睡不回收，仍可被主动回忆捞回） */
    private val recycleImportanceThreshold = 0.35f

    /** 分数 → ACTIVE 基础存活时长；被反复想起（accessCount，提取练习效应）会额外延长 */
    private fun activeDurationMs(importance: Float, accessCount: Int): Long {
        val baseDays = when {
            importance >= 0.8f -> 30L
            importance >= 0.5f -> 7L
            else -> 2L
        }
        val extraDays = minOf(accessCount, 14).toLong()
        return (baseDays + extraDays) * 24L * 3600_000L
    }

    /**
     * 生命周期刷新（惰性：注入/检索前自动执行；冷启动补一次）：
     *   · 未排期的 ACTIVE（新建/唤醒）→ 按分数排 activeUntil
     *   · ACTIVE 到期 → INACTIVE（沉睡）
     *   · INACTIVE 沉睡超期且低分 → RECYCLED（进拾忆区）
     *   · 超保留期的 RECYCLED → 物理清空
     */
    suspend fun refreshLifecycle() {
        val now = System.currentTimeMillis()
        dao.listAll().forEach { m ->
            when (m.state) {
                MemoryState.ACTIVE -> {
                    if (m.activeUntil == 0L) {
                        dao.insert(m.copy(activeUntil = now + activeDurationMs(m.importance, m.accessCount)))
                    } else if (now > m.activeUntil) {
                        dao.insert(m.copy(
                            state = MemoryState.INACTIVE,
                            enteredStateAt = now,
                            activeUntil = 0L
                        ))
                    }
                }
                MemoryState.INACTIVE -> {
                    val slept = now - m.enteredStateAt
                    if (slept >= recycleAfterMs && m.importance < recycleImportanceThreshold) {
                        dao.insert(m.copy(
                            state = MemoryState.RECYCLED,
                            enteredStateAt = now,
                            activeUntil = 0L,
                            recycledAt = now
                        ))
                    }
                }
                MemoryState.RECYCLED -> {
                    // 超过保留期的拾忆记忆才真正物理清空（天数由设置「拾忆自动清空时间」驱动）
                    if (recycleRetentionDays > 0) {
                        val retained = m.recycledAt ?: m.enteredStateAt
                        if (now - retained >= recycleRetentionDays * 24L * 3600_000L) {
                            dao.deleteById(m.id)
                        }
                    }
                }
            }
        }
    }

    /** 拾忆保留期（天）。由设置「拾忆自动清空时间」驱动；0 = 永不自动清空，只手动清 */
    @Volatile
    var recycleRetentionDays: Int = 30

    /** 物理清空超过 [retentionMs] 的拾忆记忆（由用户设置的清空时间驱动） */
    suspend fun purgeRecycled(retentionMs: Long) {
        val now = System.currentTimeMillis()
        dao.listAll()
            .filter { it.state == MemoryState.RECYCLED && (it.recycledAt ?: 0L) > 0 && now - it.recycledAt!! >= retentionMs }
            .forEach { dao.deleteById(it.id) }
    }

    /** 用户反悔：把拾忆区的记忆恢复，重新进入 ACTIVE 并计时 */
    suspend fun restore(id: Long) {
        val entity = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        dao.insert(entity.copy(
            state = MemoryState.ACTIVE,
            enteredStateAt = now,
            activeUntil = now + activeDurationMs(entity.importance, entity.accessCount),
            recycledAt = null
        ))
    }

    /** 用户清空某个角色的记忆：全部未进拾忆的记忆转拾忆（可反悔，不物理删除） */
    suspend fun recycleContact(contactId: String) {
        val now = System.currentTimeMillis()
        dao.listByContact(contactId)
            .filter { it.state != MemoryState.RECYCLED }
            .forEach { m ->
                dao.insert(m.copy(
                    state = MemoryState.RECYCLED,
                    enteredStateAt = now,
                    activeUntil = 0L,
                    recycledAt = now
                ))
            }
    }

    /** 用户手动删除：进拾忆区（可反悔恢复），不物理删除 */
    suspend fun recycle(id: Long) {
        val entity = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        dao.insert(entity.copy(
            state = MemoryState.RECYCLED,
            enteredStateAt = now,
            activeUntil = 0L,
            recycledAt = now
        ))
    }

    /** 立即清空拾忆区（用户手动触发） */
    suspend fun emptyRecycled() = purgeRecycled(0L)

    /** 某角色拾忆区（记忆页内不展示，仅供管理/恢复入口使用） */
    fun observeRecycled(contactId: String): Flow<List<MemoryEntity>> =
        dao.observeRecycled(contactId)

    /** 全部拾忆记忆（拾忆页全局列表，跨角色） */
    fun observeAllRecycled(): Flow<List<MemoryEntity>> =
        dao.observeAllRecycled()

    fun observeByContact(contactId: String): Flow<List<MemoryEntity>> =
        dao.observeByContact(contactId)

    suspend fun listByContact(contactId: String): List<MemoryEntity> =
        dao.listByContact(contactId)

    suspend fun add(memory: MemoryEntity) = dao.insert(memory.scheduled())

    /**
     * 去重写入：内容与该角色任一已存记忆重复（精确或包含）则跳过。
     *
     * 自动记忆场景下子代理可能重复总结，这是最后一道闸。
     *
     * @return true=已写入；false=重复被跳过
     */
    suspend fun addIfNew(memory: MemoryEntity): Boolean {
        val prepared = memory.scheduled()
        val existing = listByContact(prepared.contactId)
        val normalized = prepared.summary.trim()
        if (existing.any { isDuplicate(it.summary, normalized) }) return false
        dao.insert(prepared)
        return true
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clearContact(contactId: String) = dao.deleteByContact(contactId)

    /**
     * 重新打分（AI rescore / 用户手动调整）。
     * 重要性改变后由状态机按新分数重排 ACTIVE 存活时长。
     */
    suspend fun rescore(id: Long, importance: Float) {
        val entity = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        dao.insert(entity.copy(
            importance = importance.coerceIn(0f, 1f),
            state = MemoryState.ACTIVE,
            enteredStateAt = now,
            activeUntil = now + activeDurationMs(importance.coerceIn(0f, 1f), entity.accessCount),
            recycledAt = null
        ))
    }

    /**
     * 按查询词重新打分（AI rescore 工具用）：命中一条或多条记忆，
     * 统一更新 importance 并唤醒回 ACTIVE（重新评定 = 它仍值得被记得）。
     *
     * @return 更新的条数
     */
    suspend fun rescoreByQuery(contactId: String, query: String, importance: Float): Int {
        val key = query.trim()
        if (key.isEmpty()) return 0
        val hits = dao.searchByKeyword(contactId, key)
        if (hits.isEmpty()) return 0
        val score = importance.coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        hits.forEach { m ->
            dao.insert(m.copy(
                importance = score,
                state = MemoryState.ACTIVE,
                enteredStateAt = now,
                activeUntil = now + activeDurationMs(score, m.accessCount),
                recycledAt = null
            ))
        }
        return hits.size
    }

    /**
     * 按关键词检索记忆（正文 + 索引）。
     * 分层记忆的"按需调取"入口，未来由 recall 工具使用。
     *
     * recall 的查询词往往是自然语言长句（如"主人最喜欢吃的食物 意大利面"），
     * 整句 LIKE 几乎必 miss；因此先整句查一次，未命中时切词做 OR 合并检索，
     * 仍无命中再用「最长公共子串」做宽松召回，覆盖"完全忘记、只能模糊描述"的提问。
     */
    suspend fun search(contactId: String, keyword: String): List<MemoryEntity> {
        val key = keyword.trim()
        if (key.isEmpty()) return emptyList()
        refreshLifecycle()
        val whole = dao.searchByKeyword(contactId, key)
        if (whole.isNotEmpty()) return whole

        // 整句未命中：按空白/标点切词，逐词检索合并去重（保留命中先后顺序）
        val terms = key.split(SPLIT_TERMS).map { it.trim() }.filter { it.isNotEmpty() }
        val collected = LinkedHashMap<Long, MemoryEntity>()
        terms.forEach { term ->
            dao.searchByKeyword(contactId, term).forEach { collected[it.id] = it }
        }
        if (collected.isNotEmpty()) return collected.values.toList()

        // 描述性提问兜底：查询是"对方爱吃什么"这类泛述，与任何具体词都无重叠。
        // 用「查询词与记忆正文/索引的最长公共子串」做宽松召回（公共 ≥2 字即可视为候选），
        // 按重叠长度排序取前 3，由上层结合语义判断相关性。
        val compact = if (key.length > 24) key.take(24) else key
        return dao.listByContact(contactId)
            .asSequence()
            .filter { it.state != MemoryState.RECYCLED }
            .mapNotNull { m ->
                val overlap = maxOf(
                    longestCommonSubstring(compact, m.summary),
                    longestCommonSubstring(compact, m.indexSummary)
                )
                if (overlap >= 2) m to overlap else null
            }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
            .toList()
    }

    /**
     * recall 的**索引全量检索**兜底（最后手段，非迫不得已不触发）：
     * 常规检索（整句/切词/公共子串）全空时，把该角色全部可及记忆（活跃 + 沉睡，不含拾忆）
     * 按重要性排序返回，让模型在完整清单里人工定位——覆盖"记忆索引有缺陷/查询过于泛化"
     * 导致任何词都匹配不上的情况。
     */
    suspend fun listAllForRecall(contactId: String, limit: Int = 40): List<MemoryEntity> =
        dao.listByContact(contactId)
            .filter { it.state != MemoryState.RECYCLED }
            .sortedWith(
                compareByDescending<MemoryEntity> { it.importance }
                    .thenByDescending { it.lastAccessAt ?: it.createTime }
            )
            .take(limit)

    private val SPLIT_TERMS =
        Regex("""[\s,，;；、.。!！?？"“”‘’'():：\[\]{}<>《》【】·]+""")

    /** 两串的最长公共连续子串长度（中文短文本 DP，够用） */
    private fun longestCommonSubstring(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        var best = 0
        for (i in a.indices) {
            for (j in b.indices) {
                if (a[i] == b[j]) {
                    dp[i + 1][j + 1] = dp[i][j] + 1
                    if (dp[i + 1][j + 1] > best) best = dp[i + 1][j + 1]
                }
            }
        }
        return best
    }

    /**
     * 记录一次记忆调取（提取练习效应）：
     * 访问计数 +1、刷新最近访问；若记忆处于沉睡/拾忆被唤起则回 ACTIVE 并按新分数重新计时。
     */
    suspend fun markAccessed(id: Long) {
        val now = System.currentTimeMillis()
        val entity = dao.getById(id) ?: return
        if (entity.state != MemoryState.ACTIVE) {
            dao.insert(entity.copy(
                state = MemoryState.ACTIVE,
                enteredStateAt = now,
                activeUntil = now + activeDurationMs(entity.importance, entity.accessCount),
                recycledAt = null
            ))
        }
        dao.bumpAccess(id, now)
    }

    /**
     * 入库前准备：索引兜底派生 + ACTIVE 未排期则按分数/调用次数排存活时长，
     * 保证新记忆立即进入状态机，不需要等下一次刷新。
     */
    private fun MemoryEntity.scheduled(): MemoryEntity {
        val withIdx = if (indexSummary.isNotBlank()) this
            else copy(indexSummary = deriveIndex(summary))
        if (withIdx.state != MemoryState.ACTIVE || withIdx.activeUntil > 0L) return withIdx
        val now = System.currentTimeMillis()
        return withIdx.copy(activeUntil = now + activeDurationMs(withIdx.importance, withIdx.accessCount))
    }

    /** 兜底索引（仅当模型没给关键词时用）：取正文首行（去掉换行），最多 40 字 */
    private fun deriveIndex(summary: String): String =
        summary.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(40)
            .orEmpty()

    /** 按内容删除（自动记忆的 remove 指令用），宽匹配：相等 / 一方包含另一方 */
    suspend fun deleteByContent(contactId: String, content: String) {
        listByContact(contactId)
            .filter { isDuplicate(it.summary.trim(), content.trim()) }
            .forEach { dao.deleteById(it.id) }
    }

    /**
     * 保存前检索合并候选（L3）：在该角色**非拾忆区**记忆里找与新总结高度相关的旧记忆。
     *
     * 判定 = 2-gram 文本相似 + 关键词命中加分；超过阈值返回最相似的一条，否则 null（视为新记忆）。
     * 检索范围含 ACTIVE + INACTIVE——沉睡的记忆也能被"再次想起"而合并，避免克隆。
     */
    suspend fun findMergeCandidate(
        contactId: String,
        newSummary: String,
        keywords: List<String>
    ): MemoryEntity? {
        val candidates = dao.listByContact(contactId).filter { it.state != MemoryState.RECYCLED }
        var best: MemoryEntity? = null
        var bestScore = 0f
        for (old in candidates) {
            var score = textSimilarity(newSummary, old.summary)
            // 新关键词命中旧记忆（整串出现即算强信号）
            for (kw in keywords) {
                if (kw.length >= 2 && (old.summary.contains(kw) || old.indexSummary.contains(kw))) score += 0.2f
            }
            // 旧关键词被新总结重新覆盖（沉睡记忆被再次提起）
            if (old.indexSummary.isNotBlank()) {
                for (kw in old.indexSummary.split('；')) {
                    if (kw.length >= 2 && newSummary.contains(kw)) score += 0.2f
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = old
            }
        }
        return if (best != null && bestScore >= 0.4f) best else null
    }

    /**
     * 合并更新（L3）：保留原记录身份（id/创建时间/调用次数），
     * 覆盖正文与索引，importance 取两边较大值，唤醒回 ACTIVE 并刷新计时。
     */
    suspend fun updateMerged(
        id: Long,
        summary: String,
        keywords: String,
        importance: Float
    ) {
        val existing = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        dao.insert(
            existing.copy(
                summary = summary,
                indexSummary = keywords,
                importance = importance.coerceIn(0f, 1f),
                state = MemoryState.ACTIVE,
                enteredStateAt = now,
                activeUntil = 0L, // 由状态机按新分数重新排期
                recycledAt = null,
                lastAccessAt = now
            )
        )
    }

    /** 2-gram 文本相似度（0~1） */
    private fun textSimilarity(a: String, b: String): Float {
        val setA = a.windowed(2).toSet()
        val setB = b.windowed(2).toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val inter = setA.intersect(setB).size
        return inter * 2f / (setA.size + setB.size)
    }

    /**
     * 更新记忆（自动记忆的 replace 指令用）：
     * 把 [old] 改写为 [new]，保留原记录 id 与类型，刷新时间戳。
     * 找不到旧记忆或新内容为空则不做任何事。
     *
     * @return true=已更新
     */
    suspend fun replace(contactId: String, old: String, new: String): Boolean {
        val cleanedNew = new.trim()
        if (cleanedNew.isEmpty()) return false
        val target = listByContact(contactId)
            .firstOrNull { isDuplicate(it.summary.trim(), old.trim()) }
            ?: return false
        // 更新时同步刷新索引（正文变了，旧索引可能不再匹配），并唤醒重新计时
        val now = System.currentTimeMillis()
        dao.insert(
            target.copy(
                summary = cleanedNew,
                indexSummary = deriveIndex(cleanedNew),
                state = MemoryState.ACTIVE,
                enteredStateAt = now,
                activeUntil = now + activeDurationMs(target.importance, target.accessCount),
                recycledAt = null,
                createTime = now
            )
        )
        return true
    }

    suspend fun count(contactId: String): Int = dao.countByContact(contactId)

    /**
     * 拼装注入到 system prompt 的**记忆索引**（常驻层，很轻）。
     *
     * 仿生对照（人类元记忆）：人不会时刻在脑内回放全部往事，而是常驻一份
     * 「我知道我记得什么」的轻索引；遇到相关语境再按需把细节取回。
     * 所以这里只注入每条记忆的关键词索引（indexSummary），正文由主代理
     * 通过 recall 工具按需调取，避免把全部记忆正文塞进上下文。
     *
     * 分两层：
     *   · 长期记忆 → 稳定可靠，标注「已保存的记忆，勿重复保存」
     *   · 短期记忆 → 近期动态，时效性强
     */
    suspend fun buildMemoryPrompt(contactId: String): String {
        refreshLifecycle()
        val all = dao.listByContact(contactId)
        // 只注入 ACTIVE 的可及索引；沉睡/拾忆的记忆不进系统提示词。
        // 可及层容量有限（元记忆没有上限会退回全量注入）：按分数高优先、最近活跃优先，再截断 TOP-N。
        val ranked = all
            .filter { it.state == MemoryState.ACTIVE }
            .sortedWith(
                compareByDescending<MemoryEntity> { it.importance }
                    .thenByDescending { it.lastAccessAt ?: it.createTime }
            )
        val longTerm = ranked.filter { it.memoryType == MemoryType.LONG_TERM }.take(15)
        val shortTerm = ranked.filter { it.memoryType == MemoryType.SHORT_TERM }.take(10)
        if (longTerm.isEmpty() && shortTerm.isEmpty()) {
            // 没有可及的活跃记忆，但库里沉淀过沉睡/拾忆记忆：
            // 注入极轻的"元记忆存在感"（不含任何内容），引导模型在相关语境主动 recall 捞回旧记忆，
            // 否则模型会以为从未发生过这些事，直接回答"不知道"。
            val dormant = all.count { it.state != MemoryState.ACTIVE }
            if (dormant == 0) return ""
            return buildString {
                appendLine("<过去的记忆>")
                appendLine("在你们的相处中，你曾沉淀过 $dormant 条关于对方的记忆，但多数已随时间淡忘、不在上面的可及索引里。")
                appendLine("如果对方问起过去的事，或你隐约觉得这件事之前提到过却记不清细节，请调用 recall 工具取回，不要凭空猜测。")
                appendLine("</过去的记忆>")
            }
        }

        return buildString {
            if (longTerm.isNotEmpty()) {
                appendLine("<已保存的记忆>")
                appendLine("以下是已经保存的记忆的检索索引（每条一行）。")
                appendLine("若当前话题涉及其中某条而你需要它的具体细节，请调用 recall 工具取回正文后再回答，不要凭空猜测或捏造。")
                appendLine("请勿重复保存相同内容。")
                longTerm.forEachIndexed { index, memory ->
                    appendLine("${index + 1}. ${memory.indexSummary.ifBlank { memory.summary.take(30) }}")
                }
                appendLine("</已保存的记忆>")
            }
            if (shortTerm.isNotEmpty()) {
                appendLine("<近期动态>")
                appendLine("以下是近期动态的检索索引。需要细节同样用 recall 工具取回。")
                shortTerm.forEachIndexed { index, memory ->
                    appendLine("${index + 1}. ${memory.indexSummary.ifBlank { memory.summary.take(30) }}")
                }
                appendLine("</近期动态>")
            }
        }
    }

    /**
     * 判断两条记忆是否视为重复：
     * 完全相等，或（双方都足够长时）一方包含另一方。
     */
    private fun isDuplicate(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return a.length > 8 && b.length > 8 && (a.contains(b) || b.contains(a))
    }
}
