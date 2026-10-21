package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * op1moga: Agent 上下文三件套数据层（对齐太墟 CoreDatabase/AgentContextEntities.kt）
 *
 * 与 Op1 原有 MemoryLibrary（对话记忆图谱）的区别：
 * - MemoryLibrary 是「事后自动分析对话 → 沉淀记忆库」，由系统定期触发；
 * - 本实体是「Agent 主动写入的长期事实」，由模型在对话中通过 memory 工具按需调用。
 * 两者互不干扰，工具名也不冲突（Op1 现有的是 query_memory / get_memory_by_title）。
 */

/**
 * 长期语义与事实记忆实体。
 *
 * 用于记录用户的长期偏好、项目架构规范、全局事实。
 *
 * 设计要点（沿用太墟语义）：
 * - subjectKey: 主题冲突去重键（如 "project.package_manager"）
 * - revision: 不可变修订版本号（每次内容变更 +1）
 * - pinned: 是否钉选到 system prompt 稳定前缀（正交于检索召回）
 * - expiresAt: 过期时间戳（null = 永不过期，过期只降权不删除）
 * - lastVerifiedAt: 最后验证新鲜度时间戳
 * - volatility: 新鲜度分级（reference / project / user）
 */
@Entity(
    tableName = "agent_memories",
    indices = [
        Index(value = ["scope", "ownerId", "key"]),
        Index(value = ["scope", "ownerId", "subjectKey"]),
        Index(value = ["pinned"]),
        Index(value = ["expiresAt"]),
    ],
)
data class AgentMemoryEntity(
    @PrimaryKey
    val id: String,
    /** global / project / session */
    val scope: String,
    /** global 为空；project 为稳定 workspace；session 为 chatId。 */
    @ColumnInfo(defaultValue = "''")
    val ownerId: String = "",
    /** preference / rule / fact / project_info */
    val kind: String,
    val key: String,
    val value: String,
    /** 主题冲突去重键（dotted format），空字符串表示无主题 */
    @ColumnInfo(defaultValue = "''")
    val subjectKey: String = "",
    /** 修订版本号（从 1 开始，每次内容变更递增） */
    @ColumnInfo(defaultValue = "1")
    val revision: Int = 1,
    /** 是否钉选到 system prompt 稳定前缀 */
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,
    /** 过期时间戳（毫秒，null = 永不过期） */
    @ColumnInfo(defaultValue = "null")
    val expiresAt: Long? = null,
    /** 最后验证新鲜度时间戳（毫秒） */
    @ColumnInfo(defaultValue = "0")
    val lastVerifiedAt: Long = 0,
    /** 新鲜度波动率：reference=稳定参考，project=项目上下文，user=用户偏好易变 */
    @ColumnInfo(defaultValue = "reference")
    val volatility: String = "reference",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 结构化多步骤任务执行计划实体。
 *
 * 一个会话（chatId）最多一条活跃计划，主键即 sessionId。
 */
@Entity(tableName = "agent_plans")
data class AgentPlanEntity(
    @PrimaryKey
    val sessionId: String,
    val goal: String,
    /** List<PlanStep> 的 JSON 表示 */
    val stepsJson: String,
    /** active / completed / cancelled */
    val status: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 会话/任务局部工作草稿便签实体。
 *
 * 同一个会话下可有多条，按 key 区分。
 */
@Entity(tableName = "agent_scratchpads", primaryKeys = ["sessionId", "key"])
data class AgentScratchpadEntity(
    val sessionId: String,
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
