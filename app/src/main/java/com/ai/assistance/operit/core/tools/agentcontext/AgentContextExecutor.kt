package com.ai.assistance.operit.core.tools.agentcontext

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.AgentMemoryEntity
import com.ai.assistance.operit.data.model.AgentPlanEntity
import com.ai.assistance.operit.data.model.AgentScratchpadEntity
import com.ai.assistance.operit.util.AppLogger
import java.util.UUID

/**
 * op1moga: Agent 上下文三件套执行器。
 *
 * 对齐太墟 harness/AgentContextExecutor.kt 的语义与输出文案，
 * 把 memory / plan / scratchpad 三个能力接到 Op1 的工具系统上。
 *
 * 会话标识用 Op1 的 chatId 代替太墟的 sessionId，语义等价。
 */
class AgentContextExecutor(private val context: Context) {

    private companion object {
        const val TAG = "AgentContextExecutor"

        const val MAX_MEMORY_KEY_CHARS = 128
        const val MAX_MEMORY_VALUE_CHARS = 4_096
        const val MAX_MEMORY_QUERY_CHARS = 256
        const val MAX_MEMORIES_PER_OWNER = 100
        const val MAX_MEMORY_LIST = 100

        val VALID_SCOPES = setOf("global", "project", "session")
        val VALID_KINDS = setOf("preference", "rule", "fact", "project_info")
        val VALID_VOLATILITY = setOf("reference", "project", "user")
    }

    private val dao get() = AppDatabase.getDatabase(context).agentContextDao()

    // ==================== 参数读取辅助 ====================

    private fun com.ai.assistance.operit.data.model.AITool.arg(name: String): String? =
        parameters.find { it.name == name }?.value?.trim()

    private fun com.ai.assistance.operit.data.model.AITool.boolArg(name: String): Boolean =
        arg(name)?.lowercase() == "true"

    private fun com.ai.assistance.operit.data.model.AITool.longArg(name: String): Long? =
        arg(name)?.toLongOrNull()

    private fun com.ai.assistance.operit.data.model.AITool.intArg(name: String): Int? =
        arg(name)?.toIntOrNull()

    // ==================== 记忆 (Memory) ====================

    suspend fun executeMemory(
        tool: com.ai.assistance.operit.data.model.AITool,
        sessionId: String,
        workspace: String
    ): Pair<Boolean, String> {
        val action = tool.arg("action")?.lowercase() ?: "list"
        return when (action) {
            "save" -> saveMemory(tool, sessionId, workspace)
            "verify" -> verifyMemory(tool, sessionId, workspace)
            "query", "search" -> queryMemories(tool, sessionId, workspace)
            "list" -> listMemories(tool, sessionId, workspace)
            "delete" -> deleteMemory(tool, sessionId, workspace)
            else -> false to "未知的 memory 动作: $action"
        }
    }

    private suspend fun saveMemory(
        tool: com.ai.assistance.operit.data.model.AITool,
        sessionId: String,
        workspace: String
    ): Pair<Boolean, String> {
        val key = tool.arg("key")?.takeIf { it.isNotBlank() }
            ?: return false to "memory save 必须提供 key"
        val value = tool.arg("value")?.takeIf { it.isNotBlank() }
            ?: return false to "memory save 必须提供 value"

        if (key.length > MAX_MEMORY_KEY_CHARS) {
            return false to "key 超长（上限 $MAX_MEMORY_KEY_CHARS 字符）"
        }
        if (value.length > MAX_MEMORY_VALUE_CHARS) {
            return false to "value 超长（上限 $MAX_MEMORY_VALUE_CHARS 字符），请拆分或精简"
        }

        val scope = (tool.arg("scope")?.lowercase() ?: "global")
        if (scope !in VALID_SCOPES) {
            return false to "scope 仅支持 global/project/session"
        }
        val ownerId = memoryOwner(scope, sessionId, workspace)
            ?: return false to "scope=$scope 需要对应的项目工作区或会话上下文"
        if (scope != "global" && ownerId.isBlank()) {
            return false to "scope=$scope 缺少 ownerId（project 需要工作区，session 需要会话）"
        }

        val kind = (tool.arg("kind")?.lowercase() ?: "fact")
        if (kind !in VALID_KINDS) {
            return false to "kind 仅支持 preference/rule/fact/project_info"
        }

        val volatility = (tool.arg("volatility")?.lowercase() ?: "reference")
        if (volatility !in VALID_VOLATILITY) {
            return false to "volatility 仅支持 reference/project/user"
        }

        val subjectKey = tool.arg("subject_key")?.takeIf { it.isNotBlank() } ?: ""
        val pinned = tool.boolArg("pinned")
        val now = System.currentTimeMillis()
        val expiresAt = tool.longArg("expires_at")?.takeIf { it > 0 }

        // 先按 key 找，再按 subjectKey 找，做冲突去重
        val byKey = dao.getMemoryByKey(key, scope, ownerId)
        val bySubject = if (subjectKey.isNotBlank()) {
            dao.getMemoryBySubjectKey(subjectKey, scope, ownerId)
        } else {
            null
        }
        val existing = byKey ?: bySubject

        if (existing == null && dao.countMemories(scope, ownerId) >= MAX_MEMORIES_PER_OWNER) {
            return false to "[$scope] 记忆已达上限 $MAX_MEMORIES_PER_OWNER 条，请先删除旧记忆"
        }

        val id = existing?.id ?: UUID.randomUUID().toString()
        val valueChanged = existing?.value != value
        val newRevision = (existing?.revision ?: 0) + if (valueChanged) 1 else 0

        dao.saveMemory(
            AgentMemoryEntity(
                id = id,
                scope = scope,
                ownerId = ownerId,
                kind = kind,
                key = key,
                value = value,
                subjectKey = subjectKey,
                revision = if (newRevision < 1) 1 else newRevision,
                pinned = pinned,
                expiresAt = expiresAt,
                lastVerifiedAt = existing?.lastVerifiedAt ?: 0,
                volatility = volatility,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )

        val conflictNote =
            if (existing != null && subjectKey.isNotBlank() && existing.key != subjectKey &&
                existing.key != key
            ) {
                " 同主题($subjectKey)已去重并升级为 revision=${if (newRevision < 1) 1 else newRevision}"
            } else {
                ""
            }

        val msg = if (existing != null) {
            val revNote =
                if (valueChanged) {
                    "，升级为 revision=${if (newRevision < 1) 1 else newRevision}"
                } else {
                    "（内容未变化，revision=${existing.revision}）"
                }
            "已更新既有长期记忆[$revNote，原值被覆盖][$scope/$kind] $key = $value$conflictNote"
        } else {
            "已成功存储长期记忆 [$scope/$kind] $key = $value"
        }
        return true to msg
    }

    private suspend fun verifyMemory(
        tool: com.ai.assistance.operit.data.model.AITool,
        sessionId: String,
        workspace: String
    ): Pair<Boolean, String> {
        val id = tool.arg("id")
        val key = tool.arg("key")
        val target = when {
            !id.isNullOrBlank() -> dao.getMemoryById(id)
            !key.isNullOrBlank() -> {
                val scope = (tool.arg("scope")?.lowercase() ?: "global")
                val ownerId = memoryOwner(scope, sessionId, workspace)
                    ?: return false to "scope 仅支持 global/project/session"
                dao.getMemoryByKey(key, scope, ownerId)
            }
            else -> return false to "memory verify 需提供 id 或 key"
        } ?: return false to "未找到要核验的记忆"

        val now = System.currentTimeMillis()
        dao.touchMemory(target.id, now)
        return true to "已刷新记忆 ${target.key} 的新鲜度（lastVerifiedAt 续期）"
    }

    private suspend fun queryMemories(
        tool: com.ai.assistance.operit.data.model.AITool,
        sessionId: String,
        workspace: String
    ): Pair<Boolean, String> {
        val query = (tool.arg("query") ?: tool.arg("key"))?.takeIf { it.isNotBlank() }
            ?: return false to "memory query 必须提供 query 或 key"
        val now = System.currentTimeMillis()
        val includeExpired = tool.boolArg("include_expired")
        val results = dao.searchMemories(
            query = query.take(MAX_MEMORY_QUERY_CHARS),
            projectOwnerId = projectOwner(workspace),
            sessionId = sessionId,
            limit = MAX_MEMORY_LIST
        ).filter { includeExpired || isFresh(it, now) }

        return if (results.isEmpty()) {
            true to "未查询到与 '$query' 相关的记忆"
        } else {
            val formatted = results.joinToString("\n") { renderMemory(it, lineBreak = true) }
            true to "查询到以下记忆：\n$formatted"
        }
    }

    private suspend fun listMemories(
        tool: com.ai.assistance.operit.data.model.AITool,
        sessionId: String,
        workspace: String
    ): Pair<Boolean, String> {
        val now = System.currentTimeMillis()
        val includeExpired = tool.boolArg("include_expired")
        val fresh = dao.getFreshMemories(
            projectOwnerId = projectOwner(workspace),
            sessionId = sessionId,
            pinned = false,
            now = now,
            limit = MAX_MEMORY_LIST
        )
        val expired = if (includeExpired) {
            dao.getMemoriesForContext(projectOwner(workspace), sessionId)
                .filter { !isFresh(it, now) }
        } else {
            emptyList()
        }
        val results = (fresh + expired).distinctBy { it.id }

        return if (results.isEmpty()) {
            true to "当前暂无长期记忆"
        } else {
            val formatted = results.joinToString("\n") { renderMemory(it) }
            true to "已保存的记忆列表：\n$formatted"
        }
    }

    private suspend fun deleteMemory(
        tool: com.ai.assistance.operit.data.model.AITool,
        sessionId: String,
        workspace: String
    ): Pair<Boolean, String> {
        val id = tool.arg("id")
        val key = tool.arg("key")
        return if (!id.isNullOrBlank()) {
            val memory = dao.getMemoryById(id) ?: return false to "未找到记忆 id=$id"
            val visible = when (memory.scope) {
                "global" -> memory.ownerId.isEmpty()
                "project" ->
                    memory.ownerId == projectOwner(workspace) && memory.ownerId.isNotBlank()
                "session" -> memory.ownerId == sessionId && sessionId.isNotBlank()
                else -> false
            }
            if (!visible) return false to "无权删除不属于当前项目或会话的记忆"
            dao.deleteMemoryById(id)
            true to "已删除记忆 id=$id"
        } else if (!key.isNullOrBlank()) {
            val scope = (tool.arg("scope")?.lowercase() ?: "global")
            val ownerId = memoryOwner(scope, sessionId, workspace)
                ?: return false to "scope 仅支持 global/project/session，且需有对应上下文"
            dao.deleteMemoryByKey(key, scope, ownerId)
            true to "已删除记忆 [$scope] key=$key"
        } else {
            false to "memory delete 需提供 id 或 key"
        }
    }

    // ==================== 计划 (Plan) ====================

    suspend fun executePlan(
        tool: com.ai.assistance.operit.data.model.AITool,
        sessionId: String
    ): Pair<Boolean, String> {
        val action = tool.arg("action")?.lowercase() ?: "get"
        return when (action) {
            "replace_active", "set_active", "create" -> {
                val goal = tool.arg("goal")?.takeIf { it.isNotBlank() }
                    ?: return false to "plan replace_active 必须提供 goal 目标描述"
                val steps = tool.arg("steps") ?: "[]"
                dao.savePlan(
                    AgentPlanEntity(
                        sessionId = sessionId,
                        goal = goal,
                        stepsJson = steps,
                        status = "active",
                        updatedAt = System.currentTimeMillis()
                    )
                )
                true to
                    "已成功创建/更新任务执行规划：\n目标：$goal\n步骤：$steps\n\n" +
                    "提示：请按规划推进执行。每个步骤完成时调用 plan(action=\"advance\", ...) 更新步骤状态；" +
                    "遇到阻碍时调用 replace_active 调整方案；全部完成时调用 clear_active。"
            }
            "get_active", "get" -> {
                val plan = dao.getActivePlan(sessionId)
                if (plan == null) {
                    true to "当前会话暂无活跃任务计划"
                } else {
                    true to
                        "当前活跃任务计划：\n目标：${plan.goal}\n步骤：${plan.stepsJson}\n状态：${plan.status}"
                }
            }
            "advance", "update_steps" -> {
                val plan = dao.getActivePlan(sessionId)
                    ?: return false to "当前会话没有可推进的活跃计划，请先用 replace_active 创建"
                val newStepsJson = tool.arg("steps") ?: plan.stepsJson
                // status 是计划生命周期枚举（active/completed/cancelled），不是自然语言进度描述。
                // 模型常把阶段说明塞进 status，直接写入会导致 getActivePlan 永久找不到记录。
                val requestedStatus = tool.arg("status")?.lowercase()
                val newStatus = when (requestedStatus) {
                    "completed", "cancelled", "active" -> requestedStatus
                    else -> plan.status
                }
                dao.savePlan(
                    plan.copy(
                        stepsJson = newStepsJson,
                        status = newStatus,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                true to
                    "【规划进度已同步更新】\n状态：$newStatus\n当前步骤：$newStepsJson\n请继续推进后续任务。"
            }
            "clear_active", "clear" -> {
                dao.deletePlanBySession(sessionId)
                true to "已清空当前会话的任务规划"
            }
            else -> false to "未知的 plan 动作: $action"
        }
    }

    // ==================== 草稿 (Scratchpad) ====================

    suspend fun executeScratchpad(
        tool: com.ai.assistance.operit.data.model.AITool,
        sessionId: String
    ): Pair<Boolean, String> {
        val action = tool.arg("action")?.lowercase() ?: "list"
        return when (action) {
            "save", "set" -> {
                val key = tool.arg("key")?.takeIf { it.isNotBlank() }
                    ?: return false to "scratchpad save 必须提供 key"
                val value = tool.arg("value")?.takeIf { it.isNotBlank() }
                    ?: return false to "scratchpad save 必须提供 value"
                dao.saveScratchpad(
                    AgentScratchpadEntity(
                        sessionId = sessionId,
                        key = key,
                        value = value,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                true to "已记录工作草稿 [$key] = $value"
            }
            "get" -> {
                val key = tool.arg("key")?.takeIf { it.isNotBlank() }
                    ?: return false to "scratchpad get 必须提供 key"
                val item = dao.getScratchpad(sessionId, key)
                if (item == null) {
                    true to "草稿 [$key] 不存在"
                } else {
                    true to "草稿 [$key]：${item.value}"
                }
            }
            "list" -> {
                val list = dao.listScratchpads(sessionId)
                if (list.isEmpty()) {
                    true to "当前无工作草稿记录"
                } else {
                    val formatted = list.joinToString("\n") { "- [${it.key}]: ${it.value}" }
                    true to "当前工作草稿：\n$formatted"
                }
            }
            "delete" -> {
                val key = tool.arg("key")?.takeIf { it.isNotBlank() }
                    ?: return false to "scratchpad delete 必须提供 key"
                dao.deleteScratchpad(sessionId, key)
                true to "已删除草稿 [$key]"
            }
            "clear" -> {
                dao.clearScratchpads(sessionId)
                true to "已清空当前任务的所有工作草稿"
            }
            else -> false to "未知的 scratchpad 动作: $action"
        }
    }

    // ==================== 内部辅助 ====================

    private fun memoryOwner(scope: String, sessionId: String, workspace: String): String? =
        when (scope) {
            "global" -> ""
            "project" -> projectOwner(workspace).takeIf { it.isNotBlank() }
            "session" -> sessionId.trim().takeIf { it.isNotBlank() }
            else -> null
        }

    private fun projectOwner(workspace: String): String = workspace.trim().trimEnd('/')

    /** 新鲜度判定：expiresAt 为 null 或晚于 now 视为新鲜（过期只降权，不删除）。 */
    private fun isFresh(memory: AgentMemoryEntity, now: Long): Boolean {
        val expires = memory.expiresAt
        return expires == null || expires > now
    }

    private fun renderMemory(memory: AgentMemoryEntity, lineBreak: Boolean = false): String {
        val suffix = buildString {
            if (memory.pinned) append(" pinned")
            if (memory.expiresAt != null && !isFresh(memory, System.currentTimeMillis())) {
                append(" 已过期")
            }
            append(" v${memory.revision}")
        }
        val separator = if (lineBreak) "\n        " else " "
        return if (lineBreak) {
            "- [${memory.scope}/${memory.kind}] ${memory.key}: ${memory.value}" +
                "$separator(volatility=${memory.volatility}, freshness=$suffix)"
        } else {
            "- [${memory.scope}/${memory.kind}] ${memory.key}: ${memory.value}" +
                "$separator(volatility=${memory.volatility}, freshness=$suffix)"
        }
    }
}
