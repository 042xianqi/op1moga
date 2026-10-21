package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.assistance.operit.data.model.AgentMemoryEntity
import com.ai.assistance.operit.data.model.AgentPlanEntity
import com.ai.assistance.operit.data.model.AgentScratchpadEntity
import kotlinx.coroutines.flow.Flow

/**
 * op1moga: Agent 上下文三件套 DAO
 *
 * 对齐太墟 AgentContextDao.kt 的查询语义，表名与列名保持一致，
 * 便于后续与太墟实现互为参照。
 */
@Dao
interface AgentContextDao {

    // ========== 长期记忆 (Memory) ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMemory(memory: AgentMemoryEntity)

    @Query("SELECT * FROM agent_memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: String): AgentMemoryEntity?

    @Query(
        "SELECT * FROM agent_memories WHERE `key` = :key AND scope = :scope AND ownerId = :ownerId LIMIT 1"
    )
    suspend fun getMemoryByKey(key: String, scope: String, ownerId: String): AgentMemoryEntity?

    /** 按主题键（subjectKey）定位同主题记忆，用于冲突去重与 revision 判定。 */
    @Query(
        "SELECT * FROM agent_memories WHERE subjectKey = :subjectKey AND scope = :scope AND ownerId = :ownerId LIMIT 1"
    )
    suspend fun getMemoryBySubjectKey(
        subjectKey: String,
        scope: String,
        ownerId: String
    ): AgentMemoryEntity?

    /**
     * 钉选记忆：总是注入 system prompt 稳定前缀，绕过检索与新鲜度过滤。
     */
    @Query(
        """
        SELECT * FROM agent_memories
        WHERE pinned = 1
          AND (
                (scope = 'global' AND ownerId = '')
             OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
             OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId)
          )
        ORDER BY updatedAt DESC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getPinnedMemories(
        projectOwnerId: String,
        sessionId: String,
        limit: Int = 50
    ): List<AgentMemoryEntity>

    /** 未过期的新鲜记忆。 */
    @Query(
        """
        SELECT * FROM agent_memories
        WHERE (expiresAt IS NULL OR expiresAt > :now)
          AND pinned = :pinned
          AND (
                (scope = 'global' AND ownerId = '')
             OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
             OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId)
          )
        ORDER BY updatedAt DESC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getFreshMemories(
        projectOwnerId: String,
        sessionId: String,
        pinned: Boolean,
        now: Long,
        limit: Int
    ): List<AgentMemoryEntity>

    /** 续期：确认记忆仍然有效，刷新 lastVerifiedAt（新鲜度信号，不删除）。 */
    @Query(
        "UPDATE agent_memories SET lastVerifiedAt = :now WHERE id = :id AND (expiresAt IS NULL OR expiresAt > :now)"
    )
    suspend fun touchMemory(id: String, now: Long)

    @Query(
        """
        SELECT * FROM agent_memories
        WHERE (scope = 'global' AND ownerId = '')
           OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
           OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId)
        ORDER BY updatedAt DESC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getMemoriesForContext(
        projectOwnerId: String,
        sessionId: String,
        limit: Int = 200
    ): List<AgentMemoryEntity>

    @Query("SELECT COUNT(*) FROM agent_memories WHERE scope = :scope AND ownerId = :ownerId")
    suspend fun countMemories(scope: String, ownerId: String): Int

    @Query("SELECT * FROM agent_memories ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<AgentMemoryEntity>>

    @Query(
        """
        SELECT * FROM agent_memories
        WHERE ((scope = 'global' AND ownerId = '')
            OR (:projectOwnerId != '' AND scope = 'project' AND ownerId = :projectOwnerId)
            OR (:sessionId != '' AND scope = 'session' AND ownerId = :sessionId))
          AND (`key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%')
        ORDER BY updatedAt DESC, id ASC
        LIMIT :limit
        """
    )
    suspend fun searchMemories(
        query: String,
        projectOwnerId: String,
        sessionId: String,
        limit: Int
    ): List<AgentMemoryEntity>

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String)

    @Query("DELETE FROM agent_memories WHERE `key` = :key AND scope = :scope AND ownerId = :ownerId")
    suspend fun deleteMemoryByKey(key: String, scope: String, ownerId: String)

    // ========== 任务规划 (Plan) ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlan(plan: AgentPlanEntity)

    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getPlanBySession(sessionId: String): AgentPlanEntity?

    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId AND status = 'active' LIMIT 1")
    suspend fun getActivePlan(sessionId: String): AgentPlanEntity?

    @Query("DELETE FROM agent_plans WHERE sessionId = :sessionId")
    suspend fun deletePlanBySession(sessionId: String)

    // ========== 工作草稿 (Scratchpad) ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveScratchpad(scratchpad: AgentScratchpadEntity)

    @Query("SELECT * FROM agent_scratchpads WHERE sessionId = :sessionId AND `key` = :key LIMIT 1")
    suspend fun getScratchpad(sessionId: String, key: String): AgentScratchpadEntity?

    @Query("SELECT * FROM agent_scratchpads WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    suspend fun listScratchpads(sessionId: String): List<AgentScratchpadEntity>

    /** op1moga: 管理页需要跨会话总览全部草稿 */
    @Query("SELECT * FROM agent_scratchpads ORDER BY updatedAt DESC")
    suspend fun listAllScratchpads(): List<AgentScratchpadEntity>

    @Query("DELETE FROM agent_scratchpads WHERE sessionId = :sessionId AND `key` = :key")
    suspend fun deleteScratchpad(sessionId: String, key: String)

    @Query("DELETE FROM agent_scratchpads WHERE sessionId = :sessionId")
    suspend fun clearScratchpads(sessionId: String)
}
