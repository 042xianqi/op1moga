package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.AgentMemoryEntity
import com.ai.assistance.operit.data.model.AgentScratchpadEntity
import kotlinx.coroutines.launch

/**
 * op1moga: Agent 上下文管理页
 *
 * 三件套里的 memory / scratchpad 只写不读，用户看不到 AI 存了什么。
 * 本页把它们摊开：记忆按 scope 分组展示、草稿按当前会话展示，均支持删除。
 *
 * 对齐太墟 AgentContext 的语义：memory 有 scope/kind/subjectKey/revision/pinned，
 * scratchpad 是 per-session 的临时键值。
 */
@Composable
fun AgentContextScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getDatabase(context).agentContextDao() }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("长期记忆", "工作草稿")

    val memories by dao.observeAllMemories().collectAsState(initial = emptyList())
    // 草稿按会话存，这里展示全部会话的草稿（不同会话的 key 可能重名，故带上 sessionId 前缀区分）
    var scratchpads by remember { mutableStateOf<List<AgentScratchpadEntity>>(emptyList()) }
    LaunchedEffect(Unit) {
        scratchpads = runCatching { dao.listAllScratchpads() }.getOrDefault(emptyList())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.SemiBold else null,
                        )
                    },
                )
            }
        }

        when (selectedTab) {
            0 ->
                MemoryList(
                    memories = memories,
                    onDelete = { memory ->
                        scope.launch {
                            dao.deleteMemoryById(memory.id)
                        }
                    },
                )
            1 ->
                ScratchpadList(
                    scratchpads = scratchpads,
                    onDelete = { pad ->
                        scope.launch {
                            dao.deleteScratchpad(pad.sessionId, pad.key)
                            scratchpads = runCatching { dao.listAllScratchpads() }
                                .getOrDefault(emptyList())
                        }
                    },
                )
        }
    }
}

@Composable
private fun MemoryList(
    memories: List<AgentMemoryEntity>,
    onDelete: (AgentMemoryEntity) -> Unit,
) {
    if (memories.isEmpty()) {
        EmptyHint("还没有长期记忆。当你表达偏好、规范或重要事实时，AI 会主动记住。")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(memories, key = { it.id }) { memory ->
            MemoryCard(memory = memory, onDelete = { onDelete(memory) })
        }
    }
}

@Composable
private fun MemoryCard(memory: AgentMemoryEntity, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = memory.key,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (memory.pinned) {
                    Text(
                        text = "钉选",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip("${memory.scope}/${memory.kind}")
                if (memory.revision > 1) MetaChip("v${memory.revision}")
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = memory.value,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (memory.value.length > 80) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(
                        if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScratchpadList(
    scratchpads: List<AgentScratchpadEntity>,
    onDelete: (AgentScratchpadEntity) -> Unit,
) {
    if (scratchpads.isEmpty()) {
        EmptyHint("还没有工作草稿。AI 排查问题时会把临时分析写在这里。")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(scratchpads, key = { "${it.sessionId}#${it.key}" }) { pad ->
            ScratchpadCard(pad = pad, onDelete = { onDelete(pad) })
        }
    }
}

@Composable
private fun ScratchpadCard(pad: AgentScratchpadEntity, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pad.key,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = pad.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Box(
        modifier =
            Modifier
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
