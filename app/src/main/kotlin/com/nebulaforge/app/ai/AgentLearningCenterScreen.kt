package com.nebulaforge.app.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nebulaforge.app.NebulaForgeApplication
import java.util.Locale

@Composable
fun AgentLearningCenterScreen(app: NebulaForgeApplication) {
    val stats = app.agentLearningStats()
    val local = app.localEmbeddingStatus()
    val failures = app.recentFailurePatterns(12)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Agent 学习控制中心", style = MaterialTheme.typography.headlineSmall)
        Text("管理项目记忆、经验、离线向量、失败模式与持续学习数据。模型权重不会在后台被偷偷修改。", style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("学习状态", style = MaterialTheme.typography.titleMedium)
                Text("记忆：${stats?.memories ?: 0} · 经验：${stats?.experiences ?: 0} · 已验证：${stats?.verifiedExperiences ?: 0}")
                Text("学习记录：${stats?.learningRecords ?: 0} · 失败模式：${stats?.failurePatterns ?: 0} · 多轮对话：${stats?.conversationTurns ?: 0}")
                Text("平均 Reward：${"%.3f".format(Locale.ROOT, stats?.averageReward ?: 0.0)}")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Embedding", style = MaterialTheme.typography.titleMedium)
                Text("状态：${local.mode}")
                Text("维度：${local.dimension}")
                Text(if (local.modelPath != null) "模型：${local.modelPath}" else "当前无本地 Token-Vector 模型文件；断网时使用内置确定性离线向量编码器。")
                Text("在线 Embedding 可用时优先使用在线向量；失败时自动回退到离线向量。")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.exportAgentTrainingDataset() }) { Text("导出学习数据") }
        }
        Text("失败模式库", style = MaterialTheme.typography.titleMedium)
        if (failures.isEmpty()) Text("当前项目还没有失败模式。")
        LazyColumn(Modifier.fillMaxSize()) {
            items(failures, key = { it.id }) { failure ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(failure.title, style = MaterialTheme.typography.titleMedium)
                        Text("根因：${failure.rootCause}")
                        Text("修复：${failure.remediation}", style = MaterialTheme.typography.bodySmall)
                        Text("出现 ${failure.occurrences} 次 · 已解决 ${failure.resolved} 次 · 当前 Reward ${"%.2f".format(Locale.ROOT, failure.decayedReward())}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
