package com.nebulaforge.app.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.res.stringResource
import com.nebulaforge.app.R
import com.nebulaforge.app.mcp.McpServerStore

import com.nebulaforge.core.mcp.McpServerState

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nebulaforge.core.projectmodel.ProjectTemplateGenerator
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.core.agent.AiAttachment
import com.nebulaforge.core.agent.AiAttachmentKind
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 工作台（**只保留任务模式**）。
 *
 * 为什么删掉聊天模式：聊天模式只能给文字建议，不能读代码、不能跑命令、不能改文件，
 * 用户拿到建议后还得自己动手 —— 那部分价值由任务模式（工具循环 + Diff 审查 + 记忆沉淀）完整覆盖，
 * 留着只会让用户在两个入口之间反复切换、并误以为「AI 不能改我的项目」。
 *
 * 计划流程（[AgentPlanScreen]，先出计划再批准执行）仍保留，作为「多步任务」的显式形态，
 * 从顶栏的「计划」按钮进入，主界面不再有模式切换。
 */
@Composable
fun AiWorkspaceScreen(
    app: NebulaForgeApplication,
    onLearningCenter: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    onOpenPlan: () -> Unit = {},
    onOpenMcp: () -> Unit = {}
) {
    TaskWorkbench(
        app = app,
        onOpenAiSettings = onOpenAiSettings,
        onOpenPlan = onOpenPlan,
        onLearningCenter = onLearningCenter,
        onOpenMcp = onOpenMcp
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskWorkbench(
    app: NebulaForgeApplication,
    onOpenAiSettings: () -> Unit,
    onOpenPlan: () -> Unit,
    onLearningCenter: () -> Unit,
    onOpenMcp: () -> Unit
) {
    val state by app.chatState.collectAsStateWithLifecycle()
    val approval by app.approvalBroker.pending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // ★ 任务计时：任务在跑时每 500ms 刷新一次，顶栏就能实时显示「用时 01:23」。
    val taskNow = rememberTicker(state.taskActive)
    // ★ 键盘一弹出来就把「辅助信息条」（任务卡片 / 用量条 / MCP 条 / 报错摘要 / 妙招 / 附件行）
    //   全部收起。真机反馈：「用键盘输入文字时看不见输入框」—— 这些条都挂在滚动容器**外面**，
    //   键盘占掉半屏后它们把输入框顶到屏幕下方看不见了。收起它们，输入框永远贴着键盘上方。
    val imeVisible = WindowInsets.isImeVisible
    var input by remember { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<WorkbenchPanel?>(null) }
    // ── 消息长按菜单（见 MessageActionSheet）：这四个 state 是它的全部入口 ──────────
    /** 长按命中的那条消息（非空 = 菜单已弹出）。 */
    var menuTarget by remember { mutableStateOf<AiChatMessage?>(null) }
    /** 「编辑」对话框的目标。 */
    var editTarget by remember { mutableStateOf<AiChatMessage?>(null) }
    /** 「自由复制 / 长按选择文字范围」的可选文字对话框目标。 */
    var selectTarget by remember { mutableStateOf<AiChatMessage?>(null) }
    /** 「转为快捷指令」的命名对话框目标。 */
    var shortcutTarget by remember { mutableStateOf<AiChatMessage?>(null) }
    val shortcuts by app.shortcuts.collectAsStateWithLifecycle()
    // 「回滚到此消息」「点快捷指令」要把文字放回输入框：由 Application 推草稿、这里消费。
    val composerDraft by app.composerDraft.collectAsStateWithLifecycle()
    LaunchedEffect(composerDraft) {
        val text = composerDraft
        if (text != null) {
            input = text
            app.consumeComposerDraft()
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 附件选择：GetContent 一次覆盖图片 / 文本 / 压缩包（按 MIME 自动分派到文本抽取或图片编码）。
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) app.attachUri(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) app.attachUri(uri)
    }

    val activeTitle = state.sessions.firstOrNull { it.id == state.activeSessionId }?.title ?: "新会话"

    // imePadding 兜底：万一系统走的是「平移」而不是「缩放窗口」（华为部分版本会这样），
    // 没有它内容会整体被键盘盖住。
    Column(Modifier.fillMaxSize().imePadding()) {
        // ---------- 顶栏：会话名 + 任务阶段 + 模型 + 面板入口 ----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { historyOpen = true }) {
                Icon(Icons.Default.History, contentDescription = "历史会话")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "AI 工作台 · 任务模式" + if (activeTitle != "新会话") " · $activeTitle" else "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(state.phaseLabel)
                        append(" · ")
                        append("${state.messages.count { it.role == AiMessageRole.ASSISTANT && it.text.isNotBlank() }} 条回答")
                        if (state.tricks.isNotEmpty()) append(" · 妙招 ${state.tricks.size}")
                        if (state.memories.isNotEmpty()) append(" · 记忆 ${state.memories.size}")
                        // ★ 本轮任务用时：跑动中实时走表，结束后固定为总用时（以前只有一句「正在调用工具…」）。
                        val taskElapsed = state.taskElapsedMs(taskNow)
                        if (taskElapsed > 0L) append(" · ⏱ " + formatElapsed(taskElapsed))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 用户要求：任务/面板不要各占一个顶栏图标，而是直接在工作台里内联显示。
            // 入口统一收在下方开关行右侧的面板 chip 里（点开就在工作台内显示内容）。
            TextButton(onClick = onOpenAiSettings) {
                Text(
                    state.modelLabel.ifBlank { "未配置模型" },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(78.dp)
                )
            }
            IconButton(onClick = { app.newChatSession() }) {
                Icon(Icons.Default.Add, contentDescription = "新会话")
            }
        }

        // ---------- 权限开关：联网 / 执行命令 / 写文件（与右侧「项目」面板联动）----------
        WorkbenchSwitches(
            state = state,
            onOnline = { app.setOnline(it) },
            onCommand = { app.setAllowCommand(it) },
            onWrite = { app.setAllowWrite(it) },
            onProject = { panel = WorkbenchPanel.PROJECT },
            autoAll = app.approvalBroker.autoAll.collectAsState().value,
            onAutoAll = { app.setAutoApproveAll(it) },
            onPanel = { panel = it }
        )
        // 任务卡片内联显示：构建/运行进度、问题数、停止按钮都在工作台里，一眼可见。
        // 键盘弹起时收起（见 imeVisible 的说明）。
        if (!imeVisible) InlineTaskCard(app)

        // ---------- 消息流 ----------
        if (state.messages.isEmpty()) {
            EmptyChatHint(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    // 长按任意一条消息 → 弹出图 5 那套菜单（复制/钉住/编辑/快捷指令/回滚/分支/删除…）。
                    val openMenu: () -> Unit = { menuTarget = message }
                    when (message.role) {
                        AiMessageRole.USER -> UserBubble(
                            message = message,
                            onLongPress = openMenu,
                            onRestoreContext = { app.excludeChatMessage(message.id, collapsed = true) }
                        )
                        AiMessageRole.ASSISTANT -> AssistantBlock(
                            message = message,
                            onCopy = { copyToClipboard(context, message.text) },
                            onRegenerate = { app.regenerateLastReply() },
                            onDelete = { app.deleteChatMessage(message.id) },
                            onContinue = { app.continueChatGeneration() },
                            onLongPress = openMenu,
                            onRestoreContext = { app.excludeChatMessage(message.id, collapsed = true) }
                        )
                        AiMessageRole.TOOL -> ToolCard(
                            message = message,
                            onCopy = { copyToClipboard(context, message.toolOutput) },
                            onLongPress = openMenu
                        )
                    }
                }
            }
        }

        // 流式时自动跟随到底部（只在内容变化时触发，不会和用户手动滚动打架）
        val lastMessage = state.messages.lastOrNull()
        LaunchedEffect(state.messages.size, lastMessage?.text?.length, lastMessage?.streaming) {
            if (state.messages.isNotEmpty()) {
                runCatching { listState.animateScrollToItem(state.messages.lastIndex) }
            }
        }

        // ---------- 上下文占用（图 3 的 "38032/128000 (29%)"）----------
        // 以下整块（用量条 / MCP 条 / 附件 / 报错摘要 / 妙招）都在滚动容器外面，
        // 键盘弹起时全部收起 → 保证输入框可见可点（真机反馈的「看不见输入框」）。
        // ---------- 待发送附件（★ 不受键盘影响）----------
        // 这一行必须始终可见：它是「待发送内容」，不是辅助信息。系统文件选择器会先收起键盘、
        // 返回后再把键盘弹回来，若此时把附件行藏起来，用户看不到任何反馈 —— 只会认为「附件上传失败」。
        // 真机反馈的「突然无法上传图片和附件」就是它：文件其实已经复制进私有目录了，只是没显示。
        if (state.pendingAttachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.pendingAttachments.forEach { attachment ->
                    AssistChip(
                        onClick = { app.removePendingAttachment(attachment.id) },
                        label = {
                            Text(
                                attachment.chipLabel,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (attachment.kind == AiAttachmentKind.IMAGE) Icons.Default.Image else Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = "移除附件", modifier = Modifier.size(14.dp))
                        }
                    )
                }
                TextButton(onClick = { app.clearPendingAttachments() }) { Text("清空") }
            }
        }

        if (!imeVisible) {
        ContextUsageBar(state)
        McpStatusStrip(app = app, onOpenMcp = onOpenMcp)

        if (state.message.isNotBlank()) {
            // ★ maxLines 是硬约束：这行文字在滚动容器**外面**，一旦无限长就会把输入框顶出屏幕
            //   （真机症状：任务失败后停在报错页、回不到 AI 工作台）。长文本给用户看详情，
            //   但绝不占用超过 3 行。
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }

        // ---------- 快捷指令：长按消息「转为快捷指令」存下来的模板，点一下填进输入框 ----------
        if (shortcuts.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = "快捷指令",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                shortcuts.forEach { shortcut ->
                    AssistChip(
                        onClick = { app.useShortcut(shortcut.id) },
                        label = {
                            Text(
                                shortcut.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
                TextButton(onClick = { shortcuts.lastOrNull()?.let { app.deleteShortcut(it.id) } }) {
                    Text("删最后一条", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        }

        // ---------- 输入区（按图：一个圆角胶囊里放附件/图片/输入框，右侧圆形发送键）----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IconButton(
                        onClick = { attachmentPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = "添加附件", modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "添加图片", modifier = Modifier.size(20.dp))
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "输入指令或问题…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        minLines = 1,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            errorBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = if (state.busy) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(
                    enabled = state.busy || input.isNotBlank() || state.pendingAttachments.isNotEmpty(),
                    onClick = {
                        if (state.busy) {
                            app.stopChatGeneration()
                        } else {
                            val text = input
                            input = ""
                            app.sendTaskMessage(text, state.pendingAttachments)
                        }
                    }
                ) {
                    Icon(
                        if (state.busy) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        contentDescription = if (state.busy) "停止" else "发送",
                        modifier = Modifier.size(20.dp),
                        tint = if (state.busy) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }


    // ---------- 消息长按菜单（图 5：复制/钉住/编辑/快捷指令/移出上下文/回滚/分支/删除…）----------
    menuTarget?.let { target ->
        MessageActionSheet(
            context = context,
            app = app,
            message = target,
            onDismiss = { menuTarget = null },
            onEdit = { editTarget = target; menuTarget = null },
            onSelectText = { selectTarget = target; menuTarget = null },
            onNameShortcut = { shortcutTarget = target; menuTarget = null }
        )
    }
    editTarget?.let { target ->
        EditMessageDialog(
            initial = target.text,
            onDismiss = { editTarget = null },
            onSave = { app.editChatMessage(target.id, it) }
        )
    }
    selectTarget?.let { target ->
        SelectableTextDialog(
            context = context,
            text = fullTextOf(target),
            onDismiss = { selectTarget = null }
        )
    }
    shortcutTarget?.let { target ->
        NameShortcutDialog(
            initialTitle = com.nebulaforge.app.ai.AiShortcutStore(context).suggestTitle(target.text),
            body = fullTextOf(target),
            onDismiss = { shortcutTarget = null },
            onSave = { title -> app.saveShortcut(title, fullTextOf(target)) }
        )
    }

    // ---------- 历史会话抽屉 ----------
    if (historyOpen) {
        ModalBottomSheet(onDismissRequest = { historyOpen = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("历史会话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        app.newChatSession(); historyOpen = false
                    }) { Text("新建") }
                    TextButton(onClick = {
                        app.clearAllChatSessions(); historyOpen = false
                    }) { Text("全部删除") }
                }
                if (state.sessions.isEmpty()) {
                    Text("暂无历史会话", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(state.sessions, key = { it.id }) { meta ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        app.switchChatSession(meta.id); historyOpen = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        meta.title.ifBlank { "未命名会话" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${meta.messageCount} 条 · ${formatTime(meta.updatedAt)} · ~${meta.tokens} tokens",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (meta.id == state.activeSessionId) {
                                    Icon(Icons.Default.Check, contentDescription = "当前", modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { app.deleteChatSession(meta.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除会话", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- 功能面板（妙招 / 技能 / 记忆 / 经验）----------
    panel?.let { current ->
        WorkbenchPanelSheet(
            app = app,
            panel = current,
            state = state,
            onDismiss = { panel = null }
        )
    }

    // ---------- 审批闸门：命令执行确认 / 写文件 Diff 审查 ----------
    ApprovalGate(app = app, approval = approval)

    // ---------- AI 提问闸门：ask_user 工具在此挂起，等用户点选/输入后再继续 ----------
    AskGate(app = app, ask = app.askBroker.pending.collectAsStateWithLifecycle().value)
}

/**
 * 工作台内联的任务中心（紧凑形态）。
 *
 * 以前这里只有一行「⏳ assembleDebug · 已 42s」，目标与执行计划在别的页面，用户得来回跳。
 * 现在统一交给 [AgentTaskCenter]：目标、执行计划、未来/当前/历史任务、进度与用时集中在一处；
 * 紧凑形态默认收起成一行（聊天区不被占满），点「展开」看全部分组，历史任务逐条可删。
 */
@Composable
private fun InlineTaskCard(app: NebulaForgeApplication) {
    AgentTaskCenter(app, compact = true)
}

@Composable
private fun EmptyChatHint(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text("AI 工作台 · 任务模式", style = MaterialTheme.typography.titleMedium)
            Text(
                "直接说需求就行：AI 会自己读代码、搜索、跑命令、写文件，每一步改动都要你确认 Diff。\n" +
                    "附件支持图片（看图）、文本（自动抽取）、压缩包/二进制（AI 用命令分析）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 上下文占用：优先显示服务端返回的真实 usage，缺失时用估算值并标注。 */
@Composable
internal fun ContextUsageBar(state: AiChatUiState) {
    val used = maxOf(state.contextTokens, state.lastPromptTokens + state.lastCompletionTokens)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$used/${state.contextLimit} (${if (state.contextLimit > 0) (used * 100 / state.contextLimit) else 0}%)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                buildString {
                    append("max_tokens ${state.maxTokens}")
                    if (state.lastPromptTokens > 0) append(" · ↑${state.lastPromptTokens} ↓${state.lastCompletionTokens}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { if (state.contextLimit <= 0) 0f else (used.toFloat() / state.contextLimit).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp)
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("AI 回复", text))
}

/** 轻提示：给「复制」这类没有可见状态变化的动作一个反馈。 */
private fun toast(context: Context, text: String) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}

/** 一条消息的完整文字：正文 + 思考过程 + 工具输出（「自由复制 / 选择文字范围」用）。 */
private fun fullTextOf(message: AiChatMessage): String = buildString {
    if (message.reasoning.isNotBlank()) append("【思考过程】\n").append(message.reasoning).append("\n\n")
    if (message.text.isNotBlank()) append(message.text)
    if (message.toolOutput.isNotBlank()) append("\n\n【工具输出】\n").append(message.toolOutput)
}.trim()

/**
 * 长按消息弹出的动作菜单。
 *
 * 条目按「用的时候在想什么」分四组，而不是平铺：复制（看内容）→ 上下文（钉住/编辑）
 * → 复用（快捷指令）→ 纠偏与删除（移出上下文/回滚/分支/删）。「删除」永远在最后且是红色，
 * 避免翻长对话时误触。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    context: Context,
    app: NebulaForgeApplication,
    message: AiChatMessage,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSelectText: () -> Unit,
    onNameShortcut: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val excluded = message.excludedFromContext
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (message.role) {
                        AiMessageRole.USER -> "你的消息"
                        AiMessageRole.TOOL -> "工具结果"
                        AiMessageRole.ASSISTANT -> "AI 回复"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (message.pinned) MenuTag("已钉住")
                if (excluded) MenuTag("未发给大模型")
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            MessageActionItem(Icons.Default.ContentCopy, "复制") {
                copyToClipboard(context, message.text.ifBlank { message.toolOutput })
                toast(context, "已复制")
                onDismiss()
            }
            MessageActionItem(Icons.Default.TextFields, "自由复制") {
                copyToClipboard(context, fullTextOf(message))
                toast(context, "已复制整条（含思考过程与工具输出）")
                onDismiss()
            }
            MessageActionItem(Icons.Default.SelectAll, "长按选择文字范围") { onSelectText() }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            MessageActionItem(
                Icons.Default.PushPin,
                if (message.pinned) "取消钉住" else "钉住",
                trailing = if (message.pinned) null else "永远发给大模型"
            ) {
                app.pinChatMessage(message.id)
                onDismiss()
            }
            MessageActionItem(Icons.Default.Edit, "编辑") { onEdit() }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            MessageActionItem(Icons.Default.Bolt, "转为快捷指令") { onNameShortcut() }
            MessageActionItem(Icons.Default.PlaylistAdd, "添加到快捷指令列表") {
                app.addShortcutFromMessage(message.id)
                onDismiss()
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            MessageActionItem(
                if (excluded) Icons.Default.AddCircle else Icons.Default.RemoveCircle,
                if (excluded) "恢复上下文" else "从上下文移除",
                trailing = if (excluded) null else "折叠成一行"
            ) {
                app.excludeChatMessage(message.id, collapsed = true)
                onDismiss()
            }
            MessageActionItem(Icons.Default.Undo, "回滚到此消息", trailing = "内容放回输入框") {
                app.rollbackToChatMessage(message.id)
                onDismiss()
            }
            MessageActionItem(
                Icons.Default.VisibilityOff,
                if (excluded) "重新发给大模型" else "保留显示，不发给大模型"
            ) {
                app.excludeChatMessage(message.id, collapsed = false)
                onDismiss()
            }
            MessageActionItem(Icons.Default.DeleteSweep, "删除之后的所有消息") {
                app.deleteChatMessagesAfter(message.id)
                onDismiss()
            }
            MessageActionItem(Icons.Default.CallSplit, "从此处分支", trailing = "复制成新会话") {
                app.branchChatSession(message.id)
                onDismiss()
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            MessageActionItem(
                Icons.Default.Delete,
                "删除",
                tint = MaterialTheme.colorScheme.error
            ) {
                app.deleteChatMessage(message.id)
                onDismiss()
            }
        }
    }
}

@Composable
private fun MenuTag(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(start = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun MessageActionItem(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    trailing: String? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 「编辑」：直接改这条消息的正文。 */
@Composable
private fun EditMessageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑这条消息") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                minLines = 4,
                maxLines = 14,
                label = { Text("正文") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text); onDismiss() }, enabled = text.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 「长按选择文字范围」：可选中的全文，配合系统复制菜单用。 */
@Composable
private fun SelectableTextDialog(context: Context, text: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 480.dp)) {
                Text("长按选择文字范围", style = MaterialTheme.typography.titleSmall)
                Text(
                    "按住文字拖动即可只选中一段，再用系统菜单里的「复制」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                SelectionContainer(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                ) {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        copyToClipboard(context, text)
                        toast(context, "已复制全文")
                        onDismiss()
                    }) { Text("复制全文") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/** 「转为快捷指令」：给这条消息起个短名字，之后在输入框上方点一下就能复用。 */
@Composable
private fun NameShortcutDialog(
    initialTitle: String,
    body: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转为快捷指令") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("名字（显示在输入框上方）") }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "内容预览",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    body.take(200),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title); onDismiss() }, enabled = title.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** 用户消息：右对齐气泡。长按弹菜单（见 [MessageActionSheet]）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: AiChatMessage,
    onLongPress: () -> Unit = {},
    onRestoreContext: () -> Unit = {}
) {
    // 「从上下文移除」后的弱化形态：折叠成一行，但**不删**——随时能恢复。
    if (message.contextCollapsed) {
        CollapsedContextRow(
            text = message.text,
            onRestore = onRestoreContext,
            onLongPress = onLongPress
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            // 附件必须出现在消息里：否则用户发完图片/压缩包在聊天记录里毫无痕迹，只会认为「上传失败」。
            MessageAttachmentStrip(message.attachments)
            if (message.pinned || message.excludedFromContext) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    if (message.pinned) MenuTag("已钉住")
                    if (message.excludedFromContext) MenuTag("未发给大模型")
                }
            }
            Card(
                Modifier.fillMaxWidth(0.88f).combinedClickable(onLongClick = onLongPress, onClick = {}),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(14.dp)
            ) {
                SelectionContainer {
                    Text(
                        message.text,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/**
 * 消息气泡里的附件展示。
 *
 * ★ 这一块以前**完全缺失**：用户发完图片/压缩包，聊天记录里看不到任何痕迹，
 *   于是很自然地判断「附件上传失败」——实际文件早就复制进私有目录了。
 *   图片按路径解出缩略图（base64 不持久化，但文件在），其它类型给「名称 · 体积」标签。
 */
@Composable
private fun MessageAttachmentStrip(attachments: List<AiAttachment>) {
    if (attachments.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        attachments.forEach { attachment ->
            val bitmap = if (attachment.kind == AiAttachmentKind.IMAGE && attachment.path.isNotBlank()) {
                remember(attachment.path) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(attachment.path) }.getOrNull()
                }
            } else null
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(12.dp))
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (attachment.kind == AiAttachmentKind.IMAGE) Icons.Default.Image else Icons.Default.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            attachment.chipLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 「从上下文移除」后的折叠行：一行摘要 + 一键恢复，视觉噪音归零但信息不丢。 */
@Composable
private fun CollapsedContextRow(text: String, onRestore: () -> Unit, onLongPress: () -> Unit = {}) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.VisibilityOff,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "未发给大模型",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text.replace('\n', ' '),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            TextButton(onClick = onRestore, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("恢复", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * AI 回复块：思考过程（可折叠）+ 正文 + 流式光标 + 截断提示 + 操作行。
 *
 * 思考过程与正文分开：推理模型的 reasoning 往往比答案还长，
 * 混在正文里既干扰阅读，也会让用户以为「回答没写完」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBlock(
    message: AiChatMessage,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onContinue: () -> Unit,
    onLongPress: () -> Unit = {},
    onRestoreContext: () -> Unit = {}
) {
    var reasoningOpen by remember(message.id) { mutableStateOf(false) }
    var actionsOpen by remember(message.id) { mutableStateOf(false) }

    // 被「从上下文移除」的回复折叠成一行；长按依然能操作它。
    if (message.contextCollapsed) {
        CollapsedContextRow(
            text = message.text,
            onRestore = onRestoreContext,
            onLongPress = onLongPress
        )
        return
    }

    Column(
        Modifier.fillMaxWidth().combinedClickable(onLongClick = onLongPress, onClick = {}),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (message.pinned || message.excludedFromContext) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.pinned) MenuTag("已钉住")
                if (message.excludedFromContext) MenuTag("未发给大模型")
            }
        }
        if (message.reasoning.isNotBlank()) {
            Row(
                Modifier.clickable { reasoningOpen = !reasoningOpen },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (reasoningOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (message.streaming) "思考中…（${message.reasoning.length} 字）"
                    else "已思考 ${message.reasoning.length} 字 · 点击${if (reasoningOpen) "收起" else "展开"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (reasoningOpen) {
                SelectionContainer {
                    Text(
                        message.reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                }
            }
        }

        Row {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(message.text)
                        // 流式光标：让「还在生成」这件事可见，而不是看起来卡死。
                        if (message.streaming) append("▍")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        message.error?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "生成失败：$it",
                    Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // 关键修复：截断必须显式告诉用户，并给出「继续」，而不是让半截答案看起来像完整答案。
        if (message.truncated) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "回答已达到单次输出上限被截断（可调大 max_tokens，或直接继续）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    TextButton(onClick = onContinue) { Text("继续生成") }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    if (message.tokens > 0) append("${message.tokens} tokens · ")
                    message.model.takeIf { it.isNotBlank() }?.let { append("$it · ") }
                    append(formatTime(message.createdAt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (message.text.isNotBlank() && !message.streaming) {
                IconButton(onClick = { actionsOpen = !actionsOpen }) {
                    Icon(Icons.Default.Tune, contentDescription = "更多操作", modifier = Modifier.size(16.dp))
                }
            }
        }

        if (actionsOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(16.dp)) }
                IconButton(onClick = { onCopy() }) { Icon(Icons.Default.ThumbUp, contentDescription = "有帮助", modifier = Modifier.size(16.dp)) }
                IconButton(onClick = { onRegenerate() }) { Icon(Icons.Default.Refresh, contentDescription = "重新生成", modifier = Modifier.size(16.dp)) }
                IconButton(onClick = { onDelete() }) { Icon(Icons.Default.Delete, contentDescription = "删除这条", modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

/**
 * 工具卡片：Agent 计划里一次真实工具/命令调用的结果（图 3 中
 * 「shizuku_exec command: cp …」那一类）。默认折叠，只显示工具名与行数。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCard(
    message: AiChatMessage,
    onCopy: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    var open by remember(message.id) { mutableStateOf(false) }
    val failed = message.error != null || (message.toolExitCode != null && message.toolExitCode != 0)
    // 运行中每 500ms 走表；结束后固定为总用时。
    val now = rememberTicker(message.streaming)
    val elapsed = message.toolElapsedMs(now)
    Card(
        Modifier.fillMaxWidth().combinedClickable(onLongClick = onLongPress, onClick = {}),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    message.toolName ?: "tool",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    buildString {
                        // 「执行中 · ⏱ 12.3s · 42 行」——用户一眼能看出跑了多久、有没有卡住。
                        if (message.streaming) append("执行中 · ")
                        if (message.hasToolTiming) append("⏱ " + formatElapsed(elapsed) + " · ")
                        append("${message.toolLineCount} 行")
                        if (failed) append(" · 失败")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { open = !open }) {
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "展开输出",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            // 运行中的卡片给一条不确定进度条：长任务（构建/下载）至少能看到「真的在动」。
            if (message.streaming) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            if (message.text.isNotBlank()) {
                Text(message.text, style = MaterialTheme.typography.bodySmall, maxLines = if (open) 20 else 2, overflow = TextOverflow.Ellipsis)
            }
            if (open) {
                SelectionContainer {
                    Text(
                        message.toolOutput.ifBlank { "（无输出）" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                TextButton(onClick = onCopy) { Text("复制输出") }
            }
        }
    }
}

/** 顶部小图标行占位：保持与标题栏一致的点击区域（当前未使用，保留给后续扩展）。 */
@Composable
private fun IconRowSpacer() { Box(Modifier.size(0.dp)) }

/** 上下文占用条上加一个小箭头，用于「一键跳到底部」。 */
@Composable
private fun ScrollToBottomButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.ArrowDownward, contentDescription = "到底部", modifier = Modifier.size(18.dp))
    }
}

/** 颜色工具：把估算占比映射成提示色（低=中性，高=警告）。 */
internal fun usageColor(percent: Int): Color = when {
    percent >= 90 -> Color(0xFFD32F2F)
    percent >= 70 -> Color(0xFFF57C00)
    else -> Color(0xFF1976D2)
}

// ---------------------------------------------------------------------------
// 任务工作台：权限开关 / 功能面板 / 审批闸门
// ---------------------------------------------------------------------------

/** 工作台底部的功能面板（把妙招、技能、记忆、经验从「看不见的存储」变成可管理的界面）。 */
enum class WorkbenchPanel(val title: String) {
    /** 任务：构建/计划任务管理（运行/停止/删除）+ 本会话 AI 工具调用记录（状态/用时/输出）。 */
    TASKS("任务"),
    TRICKS("妙招"),
    SKILLS("技能"),
    MEMORY("记忆"),
    EXPERIENCE("经验"),
    VIDEO("视频"),
    DEV("开发"),
    WEBAI("网桥"),
    PROJECT("项目");

    /** chip 上的计数：妙招只算启用的（用户关心的是「现在生效几个」）。 */
    fun count(state: AiChatUiState): Int = when (this) {
        TASKS -> state.messages.count { it.role == AiMessageRole.TOOL }
        TRICKS -> state.tricks.count { it.enabled }
        SKILLS -> state.skills.size
        MEMORY -> state.memories.size
        EXPERIENCE -> state.experiences.size
        VIDEO -> 0
        DEV -> 0
        WEBAI -> 0
        PROJECT -> 0
    }
}

/** 权限开关：直接决定系统提示词里给模型开放哪些工具，改了立刻生效（无需重启）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WorkbenchSwitches(
    state: AiChatUiState,
    onOnline: (Boolean) -> Unit,
    onCommand: (Boolean) -> Unit,
    onWrite: (Boolean) -> Unit,
    onProject: () -> Unit,
    autoAll: Boolean = true,
    onAutoAll: ((Boolean) -> Unit)? = null,
    onPanel: ((WorkbenchPanel) -> Unit)? = null
) {
    // 真机实测：原来是单行 + horizontalScroll，屏幕外的 chip（项目/网桥…）用户永远看不到，
    // 报告「有两个项目标签 / 找不到网桥」。改成自动换行，所有入口一眼可见。
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        FilterChip(
            selected = autoAll,
            onClick = { onAutoAll?.invoke(!autoAll) },
            label = { Text("全自动(免审批)", style = MaterialTheme.typography.labelSmall) }
        )
        // 面板入口内联进来（不再各占一个顶栏图标）：点一下就在工作台里展开对应内容。
        listOf(
            WorkbenchPanel.TASKS to "任务",
            WorkbenchPanel.VIDEO to "视频",
            WorkbenchPanel.DEV to "开发",
            WorkbenchPanel.WEBAI to "网桥",
            WorkbenchPanel.TRICKS to "妙招",
            WorkbenchPanel.SKILLS to "技能",
            WorkbenchPanel.MEMORY to "记忆",
            WorkbenchPanel.PROJECT to "项目"
        ).forEach { (p, label) ->
            AssistChip(
                onClick = { onPanel?.invoke(p) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
        FilterChip(
            selected = state.online,
            onClick = { onOnline(!state.online) },
            label = { Text("联网", style = MaterialTheme.typography.labelSmall) }
        )
        FilterChip(
            selected = state.allowCommand,
            onClick = { onCommand(!state.allowCommand) },
            label = { Text("执行命令", style = MaterialTheme.typography.labelSmall) }
        )
        FilterChip(
            selected = state.allowWrite,
            onClick = { onWrite(!state.allowWrite) },
            label = { Text("写文件", style = MaterialTheme.typography.labelSmall) }
        )
        if (state.toolRounds > 0) {
            Text(
                "已调用 ${state.toolRounds} 轮工具",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 妙招 / 技能 / 记忆 / 经验四合一管理面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkbenchPanelSheet(
    app: NebulaForgeApplication,
    panel: WorkbenchPanel,
    state: AiChatUiState,
    onDismiss: () -> Unit
) {
    var showAddItem by remember { mutableStateOf(false) }
    var skillRunOutput by remember { mutableStateOf<String?>(null) }
    val addScope = androidx.compose.runtime.rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            // 「显示不全」修复：底部弹层里长表单（尤其「视频」的在线服务商：名称/Base URL/模型/密钥/路径）
            // 会被裁掉。这里给最大高度 + 内部纵向滚动，字段再多也能滑到、看得全。
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(panel.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    panelTip(panel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
            if (panel == WorkbenchPanel.TASKS) {
                TaskPanelContent(app, state)
                return@Column
            }
            if (panel == WorkbenchPanel.PROJECT) {
                ProjectPanelContent(app, state)
                return@Column
            }
            if (panel == WorkbenchPanel.VIDEO) {
                VideoPanelContent(app)
                return@Column
            }
            if (panel == WorkbenchPanel.DEV) {
                DevPanelContent(app)
                return@Column
            }
            if (panel == WorkbenchPanel.WEBAI) {
                WebAiBridgeContent(app)
                return@Column
            }
            val addPanel = panel?.takeIf { it == WorkbenchPanel.TRICKS || it == WorkbenchPanel.SKILLS }
            if (addPanel != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showAddItem = true }) {
                        Text(
                            if (addPanel == WorkbenchPanel.TRICKS) "＋ 新增妙招（提示词补丁）" else "＋ 新增技能（脚本/流程）",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            if (showAddItem && addPanel != null) {
                AddWorkbenchItemDialog(
                    panel = addPanel,
                    onDismiss = { showAddItem = false },
                    onCreateTrick = { name, summary, patch ->
                        app.createTrick(name, summary, patch)
                        showAddItem = false
                    },
                    onCreateSkill = { name, desc, body, entry, script ->
                        app.createSkill(name, desc, body, entry, script)
                        showAddItem = false
                    }
                )
            }
            skillRunOutput?.let { text ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { skillRunOutput = null },
                    confirmButton = { TextButton(onClick = { skillRunOutput = null }) { Text("关闭") } },
                    title = { Text("技能运行输出") },
                    text = {
                        SelectionContainer {
                            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 40, overflow = TextOverflow.Ellipsis)
                        }
                    }
                )
            }
            val empty = when (panel) {
                WorkbenchPanel.TRICKS -> state.tricks.isEmpty()
                WorkbenchPanel.SKILLS -> state.skills.isEmpty()
                WorkbenchPanel.MEMORY -> state.memories.isEmpty()
                WorkbenchPanel.EXPERIENCE -> state.experiences.isEmpty()
                WorkbenchPanel.VIDEO -> false
                WorkbenchPanel.DEV -> false
                WorkbenchPanel.WEBAI -> false
                WorkbenchPanel.PROJECT -> false
                WorkbenchPanel.TASKS -> false
            }
            if (empty) {
                Text(
                    when (panel) {
                        WorkbenchPanel.TRICKS -> "还没有妙招。妙招是「提示词补丁 + 工具开关」，启用后 AI 会照着要求干活；你也可以直接让 AI 用 save_trick 自建。"
                        WorkbenchPanel.SKILLS -> "还没有技能。技能是可复用的脚本/流程，AI 可以用 create_skill 自己写，之后用 run_skill 调用。"
                        WorkbenchPanel.MEMORY -> "这个项目还没有记忆。AI 完成重要任务后会用 remember 记下约定、路径、坑。"
                        WorkbenchPanel.EXPERIENCE -> "还没有经验记录。任务成功或失败后 AI 会沉淀一条经验，下次遇到同类问题直接复用。"
                        WorkbenchPanel.VIDEO -> "视频面板"
                        WorkbenchPanel.DEV -> "开发面板"
                        WorkbenchPanel.WEBAI -> "网桥面板"
                        WorkbenchPanel.PROJECT -> "项目面板"
                        WorkbenchPanel.TASKS -> "任务面板"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (panel) {
                        WorkbenchPanel.TRICKS -> items(state.tricks, key = { it.id }) { trick ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(trick.name, style = MaterialTheme.typography.bodyMedium)
                                        if (trick.summary.isNotBlank()) {
                                            Text(
                                                trick.summary,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            "${trick.authorLabel} · 用过 ${trick.useCount} 次",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(checked = trick.enabled, onCheckedChange = { app.toggleTrick(trick.id) })
                                    IconButton(onClick = { app.deleteTrick(trick.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除妙招", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        WorkbenchPanel.SKILLS -> items(state.skills, key = { it.id }) { skill ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                                        if (skill.description.isNotBlank()) {
                                            Text(
                                                skill.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            buildString {
                                                append(if (skill.entry.isNotBlank()) "入口 ${skill.entry}" else "只读说明")
                                                append(" · ${skill.author} · 运行 ${skill.runCount} 次")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = {
                                        addScope.launch {
                                            skillRunOutput = "运行中…（脚本最长跑 2 分钟）"
                                            skillRunOutput = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                app.runSkillNow(skill.id)
                                            }
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "运行技能",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { app.deleteSkill(skill.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除技能", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        WorkbenchPanel.MEMORY -> items(state.memories, key = { it.id }) { entry ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${entry.category} · ${entry.key}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            entry.content,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { app.deleteMemory(entry.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除记忆", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        WorkbenchPanel.PROJECT -> Unit
                        WorkbenchPanel.TASKS -> Unit
                        WorkbenchPanel.VIDEO -> Unit
                        WorkbenchPanel.DEV -> Unit
                        WorkbenchPanel.WEBAI -> Unit
                        WorkbenchPanel.EXPERIENCE -> items(state.experiences, key = { it.id }) { exp ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            (if (exp.verified) "✓ " else "") + exp.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "问题：${exp.problem}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "方案：${exp.solution}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { app.deleteExperience(exp.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除经验", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun panelTip(panel: WorkbenchPanel): String = when (panel) {
    WorkbenchPanel.TASKS -> "构建任务 + 工具调用（含用时）"
    WorkbenchPanel.TRICKS -> "启用后作为提示词补丁"
    WorkbenchPanel.SKILLS -> "AI 可自建与调用"
    WorkbenchPanel.MEMORY -> "本项目长期事实"
    WorkbenchPanel.EXPERIENCE -> "可复用的踩坑结论"
    WorkbenchPanel.VIDEO -> "本地硬编 + 在线多服务商"
    WorkbenchPanel.DEV -> "日志 / 识屏 / 文件 / 打包"
    WorkbenchPanel.WEBAI -> "网页 AI 接入 · AI 可自主调用"
    WorkbenchPanel.PROJECT -> "记忆按项目隔离"
}

/**
 * 「新增妙招 / 新增技能」弹窗：让用户（而不是只能让 AI）自己沉淀工作流。
 *
 * 分野：妙招 = 提示词补丁 + 工具开关（改 AI 的行为习惯）；技能 = 可复用脚本/流程（能手动跑，也能被 AI 用 run_skill 调）。
 */
@Composable
private fun AddWorkbenchItemDialog(
    panel: WorkbenchPanel,
    onDismiss: () -> Unit,
    onCreateTrick: (String, String, String) -> Unit,
    onCreateSkill: (String, String, String, String, String) -> Unit
) {
    val isTrick = panel == WorkbenchPanel.TRICKS
    var name by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }   // 妙招=摘要；技能=说明
    var third by remember { mutableStateOf("") }    // 妙招=提示词补丁；技能=正文
    var entry by remember { mutableStateOf("") }    // 技能入口脚本名
    var script by remember { mutableStateOf("") }   // 技能脚本内容

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (isTrick || true),
                onClick = {
                    if (isTrick) {
                        onCreateTrick(name, second, third)
                    } else {
                        onCreateSkill(name, second, third, entry, script)
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (isTrick) "新增妙招" else "新增技能") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (isTrick) "妙招 = 提示词补丁 + 工具开关。启用后每次对话都会带上它，用来固化「你希望 AI 一直遵守的做法」。"
                    else "技能 = 可复用的脚本或流程。有入口脚本就能在工作台点「▶」直接跑；AI 也能用 run_skill 调用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.material3.OutlinedTextField(
                    value = second, onValueChange = { second = it },
                    label = { Text(if (isTrick) "一句话摘要" else "说明（给 AI 看：什么时候用）") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 2
                )
                androidx.compose.material3.OutlinedTextField(
                    value = third, onValueChange = { third = it },
                    label = { Text(if (isTrick) "提示词补丁（必填，会进系统提示）" else "正文/步骤（进系统提示，可留空）") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8
                )
                if (!isTrick) {
                    androidx.compose.material3.OutlinedTextField(
                        value = entry, onValueChange = { entry = it },
                        label = { Text("入口脚本名（如 build.sh，留空=说明型技能）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = script, onValueChange = { script = it },
                        label = { Text("脚本内容（留空=只存正文）") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10
                    )
                }
            }
        }
    )
}

/** 开发面板：抓实时日志 / 识屏看状况 / 操作手机文件 / 打包到手机（与 AI 工具同一套实现，点一下就能用）。 */
@Composable
private fun DevPanelContent(app: NebulaForgeApplication) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pkg by remember { mutableStateOf(app.packageName) }
    var lines by remember { mutableStateOf("200") }
    var seconds by remember { mutableStateOf("0") }
    var op by remember { mutableStateOf("list") }
    var filePath by remember { mutableStateOf("/sdcard/Download") }
    var dest by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("source") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    fun runTool(tool: String, args: org.json.JSONObject) {
        if (running) return
        running = true
        status = "执行中…"
        result = ""
        scope.launch {
            val out = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.nebulaforge.app.ai.AiTaskToolHost(app)
                        .execute(
                            com.nebulaforge.app.ai.AiToolCall(name = tool, arguments = args, rawJson = "{}"),
                            com.nebulaforge.core.agent.AgentToolCatalog.DEVTOOLS
                        )
                        .let { if (it.ok) it.output else "执行失败：" + it.output }
                }
            } catch (t: Throwable) {
                "异常：${t.message ?: t.javaClass.simpleName}"
            }
            running = false
            status = ""
            result = out
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "这四件事与 AI 用的是同一套实现：抓 logcat（也可实时抓一段时间）、识屏（截图 + 界面文本要素 + 前台/进程状态）、" +
                "操作手机文件（/sdcard，写类会先弹确认）、打包源码/产物到手机。需要「允许执行命令」开关已打开。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.OutlinedTextField(
                value = pkg, onValueChange = { pkg = it },
                label = { Text("包名（日志/进程）") }, modifier = Modifier.weight(1f), maxLines = 1
            )
            androidx.compose.material3.OutlinedTextField(
                value = lines, onValueChange = { lines = it },
                label = { Text("行数") }, modifier = Modifier.weight(0.45f), maxLines = 1
            )
            androidx.compose.material3.OutlinedTextField(
                value = seconds, onValueChange = { seconds = it },
                label = { Text("实时秒") }, modifier = Modifier.weight(0.45f), maxLines = 1
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Button(
                enabled = !running,
                onClick = {
                    runTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.CAPTURE_LOGCAT,
                        org.json.JSONObject()
                            .put("package", pkg.trim())
                            .put("lines", lines.trim().toIntOrNull() ?: 200)
                            .put("seconds", seconds.trim().toIntOrNull() ?: 0)
                    )
                }
            ) { Text("抓日志") }
            androidx.compose.material3.OutlinedButton(
                enabled = !running,
                onClick = {
                    runTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.READ_SCREEN,
                        org.json.JSONObject().put("package", pkg.trim()).put("ui", true)
                    )
                }
            ) { Text("识屏") }
            androidx.compose.material3.OutlinedButton(
                enabled = !running,
                onClick = {
                    runTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.CAPTURE_LOGCAT,
                        org.json.JSONObject().put("lines", 80).put("filter", "FATAL|AndroidRuntime|Exception")
                    )
                }
            ) { Text("只看异常") }
        }

        HorizontalDivider()
        Text("文件操作（允许 /sdcard 与应用私有目录）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.OutlinedTextField(
                value = op, onValueChange = { op = it },
                label = { Text("操作 list/read/write/copy/move/mkdir/delete/stat/search") },
                modifier = Modifier.weight(1f), maxLines = 1
            )
        }
        androidx.compose.material3.OutlinedTextField(
            value = filePath, onValueChange = { filePath = it },
            label = { Text("路径（如 /sdcard/Download）") }, modifier = Modifier.fillMaxWidth(), maxLines = 1
        )
        androidx.compose.material3.OutlinedTextField(
            value = dest, onValueChange = { dest = it },
            label = { Text("目标路径（copy/move 用，可空）") }, modifier = Modifier.fillMaxWidth(), maxLines = 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Button(
                enabled = !running,
                onClick = {
                    runTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.DEVICE_FILES,
                        org.json.JSONObject().put("op", op.trim().ifBlank { "list" })
                            .put("path", filePath.trim()).put("dest", dest.trim())
                    )
                }
            ) { Text("执行文件操作") }
            androidx.compose.material3.OutlinedButton(
                enabled = !running,
                onClick = {
                    runTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.PACK_FILES,
                        org.json.JSONObject().put("kind", kind.trim().ifBlank { "source" })
                    )
                }
            ) { Text("打包当前工程") }
        }
        androidx.compose.material3.OutlinedTextField(
            value = kind, onValueChange = { kind = it },
            label = { Text("打包范围 source/build/all") }, modifier = Modifier.fillMaxWidth(), maxLines = 1
        )

        if (running || status.isNotBlank()) {
            Text(status.ifBlank { "执行中…" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (result.isNotBlank()) {
            HorizontalDivider()
            SelectionContainer {
                Text(result, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        HorizontalDivider()
        BackgroundAppsSection(app, running) { tool, args -> runTool(tool, args) }
    }
}

/**
 * 后台软件与目标软件监听。
 *
 * 与 AI 工具 `device_apps` 共用同一套实现：这里点一下看到的结果，和 AI 自己调工具看到的一样，
 * 排障时不会出现「面板说的」与「AI 说的」对不上。
 */
@Composable
private fun BackgroundAppsSection(
    app: NebulaForgeApplication,
    busy: Boolean,
    onTool: (String, org.json.JSONObject) -> Unit
) {
    val project = app.currentProjectPath()
    var pkg by remember { mutableStateOf(com.nebulaforge.app.device.AppWatchCenter.targetFor(app, project)) }
    var includeSystem by remember { mutableStateOf(false) }
    // 监听状态与事件不是 Compose state，用 2s 心跳驱动重绘（面板关掉就自动停，不额外耗电）。
    var beat by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            beat++
        }
    }
    LaunchedEffect(project) {
        val remembered = com.nebulaforge.app.device.AppWatchCenter.targetFor(app, project)
        if (remembered.isNotBlank()) pkg = remembered
    }
    val watching = com.nebulaforge.app.device.AppWatchCenter.isRunning()
    val statusText = if (beat >= 0) com.nebulaforge.app.device.AppWatchCenter.statusText() else ""
    val recent = if (beat >= 0) com.nebulaforge.app.device.AppWatchCenter.events().takeLast(5) else emptyList()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "后台软件：看清谁在前台、谁退到后台、谁被系统缓存待回收（比识屏更底层 —— 识屏只看「此刻屏幕有什么」）。" +
                "「盯住目标软件」会每 4 秒轮询一次：启动 / 转前台 / 转后台 / 被杀 / 崩溃都记成事件，崩溃判定会去日志里找 " +
                "FATAL EXCEPTION / ANR。监听目标按项目记住，换项目自动切回来。需要「允许 AI 使用终端通道」已打开。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Button(
                enabled = !busy,
                onClick = {
                    onTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.DEVICE_APPS,
                        org.json.JSONObject().put("action", "running").put("include_system", includeSystem).put("limit", 80)
                    )
                }
            ) { Text("后台软件") }
            androidx.compose.material3.OutlinedButton(
                enabled = !busy,
                onClick = {
                    onTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.DEVICE_APPS,
                        org.json.JSONObject().put("action", "foreground")
                    )
                }
            ) { Text("前台是谁") }
            androidx.compose.material3.OutlinedButton(
                enabled = !busy,
                onClick = {
                    onTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.DEVICE_APPS,
                        org.json.JSONObject().put("action", "installed").put("limit", 40)
                    )
                }
            ) { Text("已安装应用") }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = includeSystem, onCheckedChange = { includeSystem = it })
            Text("连系统应用一起列", style = MaterialTheme.typography.labelSmall)
        }
        androidx.compose.material3.OutlinedTextField(
            value = pkg, onValueChange = { pkg = it },
            label = { Text("监听目标包名（如 com.example.app）") },
            modifier = Modifier.fillMaxWidth(), maxLines = 1,
            trailingIcon = {
                androidx.compose.material3.TextButton(
                    onClick = { pkg = app.packageName },
                    enabled = !busy
                ) { Text("本应用") }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Button(
                enabled = !busy,
                onClick = {
                    val target = pkg.trim().ifBlank { com.nebulaforge.app.device.AppWatchCenter.targetFor(app, project) }
                    onTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.DEVICE_APPS,
                        org.json.JSONObject().put("action", "watch").put("package", target)
                    )
                }
            ) { Text(if (watching) "重新监听" else "开始监听") }
            androidx.compose.material3.OutlinedButton(
                enabled = !busy && watching,
                onClick = {
                    onTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.DEVICE_APPS,
                        org.json.JSONObject().put("action", "stop")
                    )
                }
            ) { Text("停止") }
            androidx.compose.material3.OutlinedButton(
                enabled = !busy,
                onClick = {
                    onTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.DEVICE_APPS,
                        org.json.JSONObject().put("action", "events").put("limit", 40)
                    )
                }
            ) { Text("看事件") }
            androidx.compose.material3.OutlinedButton(
                enabled = !busy,
                onClick = {
                    onTool(
                        com.nebulaforge.core.agent.AgentToolCatalog.DEVICE_APPS,
                        org.json.JSONObject().put("action", "logs").put("limit", 120)
                    )
                }
            ) { Text("看日志") }
        }
        Text(
            statusText,
            style = MaterialTheme.typography.labelSmall,
            color = if (watching) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (recent.isNotEmpty()) {
            Text("最近事件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(
                    recent.joinToString("\n") { it.line() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 视频面板：本地硬编兜底 + 在线多服务商。 */
@Composable
private fun VideoPanelContent(app: NebulaForgeApplication) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val prefs = app.getSharedPreferences(com.nebulaforge.app.media.VideoGenerationService.PREFS, android.content.Context.MODE_PRIVATE)
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf(com.nebulaforge.app.media.VideoProviderStore.DEFAULT_PATH) }
    var images by remember { mutableStateOf("") }
    var scripts by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("3") }
    var height by remember { mutableStateOf("720") }
    var title by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(prefs.getString(com.nebulaforge.app.media.VideoGenerationService.KEY_BASE, "").orEmpty()) }
    var model by remember { mutableStateOf(prefs.getString(com.nebulaforge.app.media.VideoGenerationService.KEY_MODEL, "").orEmpty()) }
    var secret by remember { mutableStateOf(prefs.getString(com.nebulaforge.app.media.VideoGenerationService.KEY_SECRET, "").orEmpty()) }
    var providers by remember { mutableStateOf(com.nebulaforge.app.media.VideoProviderStore.all(app)) }
    var activeId by remember { mutableStateOf(com.nebulaforge.app.media.VideoProviderStore.activeId(app)) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "本地用系统硬编（H.264）合成 MP4：填图片做「图生视频」（运镜 + 交叉淡入淡出），填文案做「文生视频」（按行切字幕卡）。" +
                "配置了在线视频服务时优先走在线（可用服务商名指定），失败或未配置自动回落本地，结果里会写明实际走了哪条路。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.material3.OutlinedTextField(
            value = images,
            onValueChange = { images = it },
            label = { Text("图片路径（逗号分隔；留空则走文生视频）") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )
        androidx.compose.material3.OutlinedTextField(
            value = scripts,
            onValueChange = { scripts = it },
            label = { Text("文案（每行一张字幕卡）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 6
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.OutlinedTextField(
                value = seconds, onValueChange = { seconds = it },
                label = { Text("每张停留(秒)") }, modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.OutlinedTextField(
                value = height, onValueChange = { height = it },
                label = { Text("高度(宽自动 16:9)") }, modifier = Modifier.weight(1f)
            )
        }
        androidx.compose.material3.OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("角标文字（可选）") }, modifier = Modifier.fillMaxWidth()
        )
        if (running || status.isNotBlank()) {
            Text(status.ifBlank { "合成中…" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.Button(
            enabled = !running,
            onClick = {
                val args = org.json.JSONObject()
                    .put("images", images.trim())
                    .put("text", scripts)
                    .put("seconds_per_image", seconds.trim().toDoubleOrNull() ?: 3.0)
                    .put("height", height.trim().toIntOrNull() ?: 720)
                    .put("width", ((height.trim().toIntOrNull() ?: 720) * 16 / 9))
                    .put("title", title)
                running = true
                result = ""
                status = "准备中…"
                scope.launch {
                    val output = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.nebulaforge.app.media.VideoGenerationService.compose(
                                context = app,
                                projectRoot = app.currentProjectPath(),
                                arguments = args
                            ) { _, note -> handler.post { status = note } }
                        }
                    } catch (t: Throwable) {
                        "生成失败：${t.message ?: t.javaClass.simpleName}"
                    }
                    running = false
                    status = ""
                    result = output
                }
            }
        ) { Text("生成视频") }

        if (result.isNotBlank()) {
            SelectionContainer {
                Text(result, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        HorizontalDivider()
        Text(
            "在线视频服务（可添加多个，任选其一使用）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (providers.isEmpty()) {
            Text("还没有服务商：下面填好 Base URL 与模型名，点「保存并选用」。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        providers.forEach { prov ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (prov.id == activeId) "● " else "○ ") + prov.label + if (prov.usable) "" else "（配置不完整）",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (prov.id == activeId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = {
                    name = prov.name
                    base = prov.base
                    model = prov.model
                    secret = prov.key
                    path = prov.path
                    com.nebulaforge.app.media.VideoProviderStore.setActive(app, prov.id)
                    activeId = com.nebulaforge.app.media.VideoProviderStore.activeId(app)
                    status = "已选用「${prov.label}」"
                }) { Text(if (prov.id == activeId) "已选用" else "选用") }
                TextButton(onClick = {
                    com.nebulaforge.app.media.VideoProviderStore.remove(app, prov.id)
                    providers = com.nebulaforge.app.media.VideoProviderStore.all(app)
                    activeId = com.nebulaforge.app.media.VideoProviderStore.activeId(app)
                    status = "已删除「${prov.label}」"
                }) { Text("删除") }
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("服务商名称（如 可灵 / 通义万相）") }, modifier = Modifier.fillMaxWidth(), maxLines = 1
        )
        androidx.compose.material3.OutlinedTextField(
            value = base, onValueChange = { base = it },
            label = { Text("Base URL（如 https://api.example.com/v1）") }, modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.material3.OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("模型名（如 kling-v1 / wanx2.1-t2v）") }, modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.material3.OutlinedTextField(
            value = secret, onValueChange = { secret = it },
            label = { Text("密钥（只存本机，不回显到日志）") }, modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.material3.OutlinedTextField(
            value = path, onValueChange = { path = it },
            label = { Text("提交路径（默认 /videos/generations）") }, modifier = Modifier.fillMaxWidth(), maxLines = 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val prov = com.nebulaforge.app.media.VideoProviderStore.Provider(
                    id = name.trim().ifBlank { base.trim() },
                    name = name.trim(),
                    base = base.trim(),
                    model = model.trim(),
                    key = secret.trim(),
                    path = path.trim().ifBlank { com.nebulaforge.app.media.VideoProviderStore.DEFAULT_PATH }
                )
                com.nebulaforge.app.media.VideoProviderStore.upsert(app, prov, makeActive = prov.id)
                providers = com.nebulaforge.app.media.VideoProviderStore.all(app)
                activeId = com.nebulaforge.app.media.VideoProviderStore.activeId(app)
                status = if (prov.usable) "已保存并选用「${prov.label}」" else "已保存「${prov.label}」（Base URL / 模型名没填全，仍会走本地硬编）"
            }) { Text("保存并选用") }
            Text(
                if (com.nebulaforge.app.media.VideoGenerationService.onlineConfigured(app))
                    "当前：在线优先（${com.nebulaforge.app.media.VideoGenerationService.providerLabel(app)}）" else "当前：本地硬编",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 项目面板：让用户随时确认「AI 现在作用在哪个项目、沉淀了多少东西」。 */
@Composable
private fun ProjectPanelContent(app: NebulaForgeApplication, state: AiChatUiState) {
    // 用户反馈：「这个项目功能无法手动新增删除和管理项目」。
    // 旧版这个面板只有一行只读路径，于是「建项目」必须跑去项目页的向导、删/改名根本没入口。
    // 现在四个手动动作（新建 / 添加已有目录 / 重命名 / 删除）全在这里，和 AI 的 create_project
    // 工具共用 Application 上的同一份实现。
    var version by remember { mutableStateOf(0) }
    val projects = remember(version) { app.listAllProjects() }
    val currentPath = app.currentProjectPath()
    var notice by remember { mutableStateOf("") }
    var newOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<java.io.File?>(null) }
    var deleteTarget by remember { mutableStateOf<java.io.File?>(null) }
    fun refresh() { version++ }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { newOpen = true }, modifier = Modifier.weight(1f)) {
                Text("新建项目", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = { addOpen = true }, modifier = Modifier.weight(1f)) {
                Text("添加目录", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (notice.isNotBlank()) {
            Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "项目 ${projects.size} 个 · 点击切换 · 右侧按钮改名 / 删除",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (projects.isEmpty()) {
            Text("还没有项目：点「新建项目」建一个，或用「添加目录」把已有工程挂进来。", style = MaterialTheme.typography.labelSmall)
        }
        projects.forEach { dir ->
            val isCurrent = dir.absolutePath == currentPath
            val managed = dir.absolutePath.trimEnd('/')
                .startsWith(app.projectsRootDir().absolutePath.trimEnd('/') + "/")
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable {
                        app.selectProject(dir.absolutePath)
                        app.refreshWorkbenchPanels()
                        notice = "已切换到 ${dir.name}"
                        refresh()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        dir.name + (if (isCurrent) " · 当前" else "") + (if (managed) "" else " · 外部"),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        dir.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    onClick = { renameTarget = dir },
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text("改名", style = MaterialTheme.typography.labelSmall) }
                TextButton(
                    onClick = { deleteTarget = dir },
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text("删除", style = MaterialTheme.typography.labelSmall) }
            }
        }
        HorizontalDivider()
        Text(
            "当前项目：${currentPath ?: "未打开"}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "记忆 ${state.memories.size} 条 · 经验 ${state.experiences.size} 条 · 技能 ${state.skills.size} 个 · 妙招 ${state.tricks.count { it.enabled }} 个启用",
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            "记忆与经验按项目路径隔离；切换项目后 AI 不会把上一个项目的结论串过来。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { app.refreshWorkbenchPanels(); refresh() }) { Text("刷新面板数据") }
    }

    // ── 新建项目：名字 + 模板（模板与项目页向导用的是同一个 ProjectTemplateGenerator）──
    if (newOpen) {
        var name by remember { mutableStateOf("") }
        var tpl by remember { mutableStateOf("android-empty") }
        AlertDialog(
            onDismissRequest = { newOpen = false },
            title = { Text("新建项目") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("项目名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("模板", style = MaterialTheme.typography.labelSmall)
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        ProjectTemplateGenerator.templates.forEach { t ->
                            FilterChip(
                                selected = tpl == t.id,
                                onClick = { tpl = t.id },
                                label = { Text(t.name, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Text(app.projectsRootDir().absolutePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dir = app.createProjectDir(name)
                    val pkg = "com.example." + dir.name.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "app" }
                    val err = runCatching { ProjectTemplateGenerator.create(tpl, dir, pkg) }.exceptionOrNull()
                    app.selectProject(dir.absolutePath)
                    app.refreshWorkbenchPanels()
                    notice = if (err == null) "已创建并切换：${dir.absolutePath}" else "已建目录（模板写入失败：${err.message}）"
                    newOpen = false
                    refresh()
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newOpen = false }) { Text("取消") } }
        )
    }

    // ── 添加已有目录：填绝对路径（只登记路径，不动用户文件）──
    if (addOpen) {
        var path by remember { mutableStateOf("/storage/emulated/0/") }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("添加已有目录") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("填目录绝对路径；只登记路径，不会移动或复制你的文件。", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("目录路径") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("/storage/emulated/0", "/storage/emulated/0/NebulaForgeProjects", "/storage/emulated/0/Download")
                            .forEach { p ->
                                AssistChip(onClick = { path = p }, label = { Text(p.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall) })
                            }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dir = java.io.File(path.trim())
                    if (!dir.isDirectory) {
                        notice = "目录不存在：${dir.absolutePath}"
                    } else {
                        app.registerExternalRoot(dir)
                        app.selectProject(dir.absolutePath)
                        app.refreshWorkbenchPanels()
                        notice = "已添加并切换：${dir.absolutePath}"
                        addOpen = false
                        refresh()
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("取消") } }
        )
    }

    // ── 重命名 ──
    renameTarget?.let { dir ->
        var text by remember(dir) { mutableStateOf(dir.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名项目") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("新名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = app.renameProjectDir(dir, text)
                    notice = if (target != null) "已重命名为 ${target.name}" else "重命名失败（名字为空或已存在）"
                    renameTarget = null
                    app.refreshWorkbenchPanels()
                    refresh()
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    // ── 删除（受管项目真删目录；外部工程只从列表移除）──
    deleteTarget?.let { dir ->
        val managed = dir.absolutePath.trimEnd('/')
            .startsWith(app.projectsRootDir().absolutePath.trimEnd('/') + "/")
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (managed) "删除项目" else "从列表移除") },
            text = {
                Text(
                    if (managed) "会**真删**目录及其全部文件：\n${dir.absolutePath}"
                    else "外部工程只从项目列表移除，磁盘上的文件原样保留：\n${dir.absolutePath}",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = app.deleteProjectDir(dir)
                    notice = if (ok) (if (managed) "已删除 ${dir.name}" else "已从列表移除 ${dir.name}") else "删除失败（可能有文件被占用）"
                    deleteTarget = null
                    app.refreshWorkbenchPanels()
                    refresh()
                }) { Text(if (managed) "删除" else "移除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

/**
 * 审批闸门。
 *
 * 工具循环是挂起等待的：模型提出「执行命令」或「写文件」后，这里弹出确认框，
 * 用户点「允许」协程才继续。写文件走统一 Diff（红色删除/绿色新增），避免 AI 悄悄改坏代码。
 */
@Composable
private fun ApprovalGate(app: NebulaForgeApplication, approval: TaskApprovalRequest?) {
    if (approval == null) return
    var remember by remember(approval.id) { mutableStateOf(false) }
    val patch = approval.patch
    if (patch != null) {
        AlertDialog(
            onDismissRequest = { app.resolveApproval(false) },
            title = { Text(if (patch.exists) "审查文件改动" else "新建文件") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        patch.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "新增 ${patch.addedLines} 行 · 删除 ${patch.removedLines} 行",
                        style = MaterialTheme.typography.labelSmall
                    )
                    SelectionContainer {
                        Text(
                            patch.diff.take(8000),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { app.resolveApproval(true) }) { Text("应用") } },
            dismissButton = { TextButton(onClick = { app.resolveApproval(false) }) { Text("跳过") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = { app.resolveApproval(false) },
        title = { Text(approval.title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (approval.detail.isNotBlank()) {
                    Text(approval.detail, style = MaterialTheme.typography.bodySmall)
                }
                if (approval.command.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            approval.command,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 6.dp).heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("本次任务内不再询问同类操作", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { app.resolveApproval(true, remember) }) { Text("允许") } },
        dismissButton = { TextButton(onClick = { app.resolveApproval(false) }) { Text("拒绝") } }
    )
}

/**
 * AI 工作台底部的 MCP 状态条。
 *
 * 用户反馈「MCP 无法嗅探外部服务器状态」：工作台里既看不到配了哪些服务器，也看不出它们是否活着。
 * 这里把 [com.nebulaforge.core.mcp.McpHost] 的真实探测结果（initialize + tools/list）直接摊在工作台上，
 * 自带「重新检测」与跳转「管理」，状态与 MCP 页共用同一份快照，不会两处说法不一致。
 */
@Composable
private fun McpStatusStrip(app: NebulaForgeApplication, onOpenMcp: () -> Unit) {
    val snapshots by app.mcpHost.snapshots.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val store = remember { McpServerStore(context) }
    val configured = remember { store.load() }
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }

    val ready = snapshots.count { it.state == McpServerState.READY }
    val failed = snapshots.count { it.state == McpServerState.ERROR }
    val idle = configured.count { c ->
        c.enabled && snapshots.firstOrNull { it.id == c.id }?.state !in
            setOf(McpServerState.READY, McpServerState.ERROR)
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.ai_mcp_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (configured.isEmpty()) stringResource(R.string.ai_mcp_none)
            else stringResource(R.string.ai_mcp_summary, ready, failed, idle),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedButton(
            enabled = !probing && configured.any { it.enabled },
            onClick = {
                probing = true
                scope.launch {
                    configured.filter { it.enabled }.forEach { server ->
                        withContext(Dispatchers.IO) {
                            runCatching {
                                if (server.id !in app.mcpHost.remoteServers()) {
                                    app.connectExternalMcp(server)
                                }
                            }
                        }
                        runCatching { app.mcpHost.probe(server.id) }
                    }
                    probing = false
                }
            }
        ) {
            Text(if (probing) stringResource(R.string.mcp_state_probing) else stringResource(R.string.ai_mcp_reprobe))
        }
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = onOpenMcp) { Text(stringResource(R.string.ai_mcp_manage)) }
    }
}

/**
 * 「网桥」面板：把网页 AI 接进工作台（AI 聚合网关）。
 *
 * 为什么要有它：用户明确要「网页 AI 也能在工作台里跑」。这里提供
 * ① 一键加常用预设 ② 内置浏览器登录（登录态与桥接共用同一个 WebView，登录一次即可）
 * ③ 手动新增任意站点（可填输入框/发送按钮选择器，站点改版也能修）④ 试问一句看效果。
 * 接入后 AI 会用工具 `ask_web_ai` 自主调用它们：交叉验证、取某站特有结论。
 */
@androidx.compose.runtime.Composable
private fun WebAiBridgeContent(app: NebulaForgeApplication) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(com.nebulaforge.app.ai.WebAiBridge.all(context)) }
    var askTarget by remember { mutableStateOf<com.nebulaforge.app.ai.WebAiBridge.WebAi?>(null) }
    var askText by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }
    fun reload() { list = com.nebulaforge.app.ai.WebAiBridge.all(context) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssistChip(
                onClick = {
                    com.nebulaforge.app.ai.WebAiBridge.addPresets(context, com.nebulaforge.app.ai.WebAiBridge.presets())
                    reload()
                },
                label = { Text("＋ 常用预设", style = MaterialTheme.typography.labelSmall) }
            )
            AssistChip(
                onClick = { manualOpen = true },
                label = { Text("＋ 手动新增", style = MaterialTheme.typography.labelSmall) }
            )
        }
        Text(
            "接入后：① 你在下面「打开/登录」里登录一次（登录态会被复用）；" +
                "② 给真正能用的站点点「设首选」（跑任务会优先用它，例如 DeepSeek/豆包/Kimi）；" +
                "③ 地区限制或未登录的站点点「停用」（Claude/Gemini 在中国大陆是地区限制页，发不出消息）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
        if (list.isEmpty()) {
            Text(
                "还没有接入网页 AI。点「＋ 常用预设」加入 ChatGPT / Claude / Gemini / DeepSeek / 豆包 / 通义 等。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        list.forEach { ai ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val prefId = com.nebulaforge.app.ai.WebAiBridge.preferred(context)
                val bad = com.nebulaforge.app.ai.WebAiBridge.badReason(context, ai.id)
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (prefId == ai.id) "★ " else "") + ai.label + (if (!ai.enabled) "（已停用）" else ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (!ai.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (bad != null) "⚠ $bad" else ai.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bad != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                TextButton(onClick = {
                    com.nebulaforge.app.ai.WebAiGatewayActivity.open(context, ai.url)
                }) {
                    Text("打开/登录", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { askTarget = ai; askText = ""; answer = "" }) {
                    Text("试问", style = MaterialTheme.typography.labelSmall)
                }
                // 「首选」：明确告诉网关跑任务时优先用它（真机问题：用户登录好的是 DeepSeek，
                // 网关却先去调 Claude 的地区限制页 → 看起来像「消息发不出去」）。
                TextButton(onClick = {
                    val cur = com.nebulaforge.app.ai.WebAiBridge.preferred(context)
                    com.nebulaforge.app.ai.WebAiBridge.setPreferred(
                        context, if (cur == ai.id) null else ai.id
                    ); reload()
                }) {
                    Text(
                        if (com.nebulaforge.app.ai.WebAiBridge.preferred(context) == ai.id) "★首选" else "设首选",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                // 「停用」：地区限制/未登录的站点直接停掉，网关就不会再浪费一轮去试它。
                TextButton(onClick = {
                    com.nebulaforge.app.ai.WebAiBridge.upsert(context, ai.copy(enabled = !ai.enabled))
                    if (!ai.enabled) com.nebulaforge.app.ai.WebAiBridge.clearBad(context, ai.id)
                    reload()
                }) {
                    Text(
                        if (ai.enabled) "停用" else "启用",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                TextButton(onClick = {
                    com.nebulaforge.app.ai.WebAiBridge.remove(context, ai.id); reload()
                }) { Text("删除", style = MaterialTheme.typography.labelSmall) }
            }
        }
        // ── 网关浏览器改由独立 Activity 承载（WebAiGatewayActivity）──────────────────
        // 为什么不再用 Compose 里的区域/Dialog：真机取证见 WebAiGatewayActivity 类注释
        //  ① 挂在 Compose 区域：page load start 后 28ms 被 StopAllLoaders/DetachFromFrame，
        //     url_request 报 -3 (ERR_ABORTED) → 白屏；
        //  ② 挂在 Compose Dialog（浮层 window，内容走 RenderNode 合成）：onPageFinished 已触发
        //     （状态显示「已加载」）但页面内容不合成，只剩 WebView 的白底 → 整屏空白。
        // WebView 的规范宿主是独立 Activity（真实 window + 硬件加速），故改为全屏 Activity。
        // 这里只保留一行**真实**状态：加载中 / 报错 / 「已加载但正文 0 字（站点拒绝内置 WebView）」。
        var gwTick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                gwTick++
            }
        }
        if (com.nebulaforge.app.ai.WebAiBridge.panelUrl != null ||
            com.nebulaforge.app.ai.WebAiBridge.lastError != null
        ) {
            Text(
                remember(gwTick) { com.nebulaforge.app.ai.WebAiBridge.statusText() },
                style = MaterialTheme.typography.labelSmall,
                color = if (com.nebulaforge.app.ai.WebAiBridge.lastError != null)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
        askTarget?.let { ai ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = askText,
                    onValueChange = { askText = it },
                    label = { Text("问 ${ai.label}") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        enabled = !busy && askText.isNotBlank(),
                        onClick = {
                            busy = true
                            answer = "…"
                            scope.launch {
                                answer = com.nebulaforge.app.ai.WebAiBridge.ask(
                                    context, ai, askText, onProgress = { p -> answer = p }
                                )
                                busy = false
                            }
                        }
                    ) { Text(if (busy) "进行中…" else "发送", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { askTarget = null }) { Text("关闭", style = MaterialTheme.typography.labelSmall) }
                }
                if (answer.isNotBlank()) {
                    SelectionContainer {
                        Text(answer, style = MaterialTheme.typography.labelSmall, maxLines = 60)
                    }
                }
            }
        }
    }

    if (manualOpen) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var inputSel by remember { mutableStateOf("") }
        var sendSel by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { manualOpen = false },
            title = { Text("手动接入网页 AI") },
            confirmButton = {
                TextButton(
                    enabled = url.startsWith("http"),
                    onClick = {
                        com.nebulaforge.app.ai.WebAiBridge.upsert(
                            context,
                            com.nebulaforge.app.ai.WebAiBridge.WebAi(
                                name = name, url = url.trim(), inputHint = inputSel.trim(), sendHint = sendSel.trim()
                            )
                        )
                        manualOpen = false
                        reload()
                    }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { manualOpen = false }) { Text("取消") } },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("网址（https://…）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = inputSel, onValueChange = { inputSel = it }, label = { Text("输入框选择器（可空）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = sendSel, onValueChange = { sendSel = it }, label = { Text("发送按钮选择器（可空）") }, modifier = Modifier.fillMaxWidth())
                }
            }
        )
    }
}


/**
 * AI 提问闸门：`ask_user` 工具会挂起任务并在这里弹出问题，用户点选项或直接输入后任务继续。
 *
 * 存在的意义：以前 AI 缺关键信息时只能猜，或者把问题写进正文里然后结束本轮 ——
 * 用户回了话，AI 也接不上那段上下文。现在它能在同一个任务里「问 → 等 → 继续」。
 */
@Composable
private fun AskGate(app: NebulaForgeApplication, ask: AskRequest?) {
    if (ask == null) return
    var answer by remember(ask.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { app.askBroker.answer("") },
        title = { Text("AI 需要你确认") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(ask.question) }
                if (ask.options.isNotEmpty()) {
                    ask.options.forEach { option ->
                        OutlinedButton(
                            onClick = { app.askBroker.answer(option) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(option) }
                    }
                }
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("也可以直接输入回答") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { app.askBroker.answer(answer) }) { Text("发送") } },
        dismissButton = { TextButton(onClick = { app.askBroker.answer("") }) { Text("跳过") } }
    )
}

/* ══════════════════════════════════════════════════════════════════════════
 * 任务面板：计时（用时）+ 任务管理（停止 / 查看 / 复制 / 删除 / 清空）
 *
 * 用户反馈两条：「AI 任务台的构建输出没有记时」「没有任务面板和任务管理」。
 * 前者是工具卡片只有一句「执行中…」、看不出跑了多久；后者是构建/计划任务藏在别的页面，
 * 工具调用则根本没法管理（只有长按消息那套菜单）。这里把两者集中到一个「任务」面板：
 *   ① 构建 / 计划任务 → 复用 AgentTaskCenter（未来/当前/执行计划/历史，可运行/停止/删除）
 *   ② AI 工具调用     → 每次都带状态、用时、退出码，可展开看输出、复制、删除、一键清空已结束
 * ══════════════════════════════════════════════════════════════════════════ */

/** 毫秒 → 人类可读用时（`0.8s` / `12.3s` / `1m23s` / `1h05m`），全站统一格式。 */
internal fun formatElapsed(ms: Long): String {
    if (ms <= 0L) return "0.0s"
    if (ms < 60_000L) return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    val totalSec = ms / 1000
    val m = totalSec / 60
    val sec = totalSec % 60
    return if (m < 60) String.format(java.util.Locale.US, "%dm%02ds", m, sec)
    else String.format(java.util.Locale.US, "%dh%02dm", m / 60, m % 60)
}

/**
 * 走表器：`active = true` 时每 500ms 触发一次重组并返回当前时间戳。
 *
 * 任务停下来的那一刻返回 0 —— 调用方据此改用「结束时间」算总用时；同时循环退出，
 * 界面不再无谓重组（任务面板里可能挂着几十行，一直在重组会明显发热）。
 */
@Composable
internal fun rememberTicker(active: Boolean): Long {
    var now by remember(active) { mutableStateOf(if (active) System.currentTimeMillis() else 0L) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(500)
        }
    }
    return now
}

/** 「任务」面板内容：① 构建/计划任务管理（AgentTaskCenter）② 本会话工具调用记录（含用时）。 */
@Composable
private fun TaskPanelContent(app: NebulaForgeApplication, state: AiChatUiState) {
    val context = LocalContext.current
    val now = rememberTicker(state.taskActive)
    val tools = state.messages.filter { it.role == AiMessageRole.TOOL }
    val running = tools.count { it.streaming }

    // ① 构建 / 计划任务：运行 / 停止 / 删除 都在 AgentTaskCenter 里。
    AgentTaskCenter(app, compact = false, showUsage = false)

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // ② AI 工具调用记录（本次会话）
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("AI 工具调用（本会话 ${tools.size} 次）", style = MaterialTheme.typography.titleSmall)
            Text(
                "每次调用都记用时与退出码；运行中的可停止，结束的可复制或删除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.canStop) {
            TextButton(onClick = { app.stopChatGeneration() }) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("停止任务", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (tools.any { !it.streaming }) {
            TextButton(onClick = { app.clearFinishedToolCards() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("清空已结束", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (running > 0) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))

    if (tools.isEmpty()) {
        Text(
            "本会话还没有工具调用。任务模式下 AI 每次跑命令 / 构建 / 搜索都会在这里留一条记录（含用时）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        return
    }
    tools.asReversed().take(60).forEach { message ->
        ToolTaskRow(
            message = message,
            now = now,
            onCopy = { copyToClipboard(context, message.toolOutput) },
            onDelete = { app.deleteChatMessage(message.id) }
        )
    }
    if (tools.size > 60) {
        Text(
            "只显示最近 60 条（更早的记录仍在会话中）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 任务面板里的一行「工具调用」：状态点 + 工具名 + 状态/用时/行数 + 展开·复制·删除。 */
@Composable
private fun ToolTaskRow(
    message: AiChatMessage,
    now: Long,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var open by remember(message.id) { mutableStateOf(false) }
    val failed = message.error != null || (message.toolExitCode != null && message.toolExitCode != 0)
    val running = message.streaming
    val elapsed = message.toolElapsedMs(now)
    val dotColor = when {
        running -> MaterialTheme.colorScheme.primary
        failed -> MaterialTheme.colorScheme.error
        else -> Color(0xFF2E7D32)
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(6.dp))
                Text(
                    message.toolName ?: "tool",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    buildString {
                        append(if (running) "执行中" else message.toolStatusLabel)
                        if (message.hasToolTiming) append(" · ⏱ " + formatElapsed(elapsed))
                        append(" · ${message.toolLineCount} 行")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (message.text.isNotBlank()) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(if (open) "收起输出" else "查看输出", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("复制输出", style = MaterialTheme.typography.labelSmall)
                }
                if (!running) {
                    TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("删除", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (open) {
                SelectionContainer {
                    Text(
                        message.toolOutput.ifBlank { "（无输出）" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
