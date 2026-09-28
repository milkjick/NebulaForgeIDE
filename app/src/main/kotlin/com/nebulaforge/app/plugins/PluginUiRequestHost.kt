package com.nebulaforge.app.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件的界面请求落点。
 *
 * 为什么必须有它：扩展激活时经常要跟用户确认一件事（"要不要装 Go 工具链"、"选哪个解释器"），
 * VS Code 里那是个会一直等下去的对话框。宿主以前**没有任何界面消费者** —— 请求发出去
 * 60 秒后超时，被当成「用户取消」，扩展随即抛出 undefined 并宣告激活失败
 * （golang 插件就是这么"失败"的：真正原因不是插件坏了，而是它没地方问人）。
 *
 * 这里把 [JsExtensionHost.uiRequests] 接到真实的对话框上：
 *  - `message`（含按钮）：点按钮回选项标签；点「取消」回 null（VS Code 的合法语义是 undefined）；
 *  - `input`：带预填值的输入框，确定回 `{"text": ...}`；
 *  - `quickPick`：单选列表，多选时给复选框。
 *
 * 放在工作区各处都能挂载（同一时刻只会有一个待答请求，槽位是单值的）。
 */
@Composable
fun PluginUiRequestHost(host: JsExtensionHost) {
    val request by host.uiRequests.collectAsState()
    val current = request ?: return
    val requestId = current.id

    var textValue by remember(requestId) { mutableStateOf(current.prefill) }
    val checked = remember(requestId) { mutableStateMapOf<String, Boolean>() }

    fun cancel() = host.answerUi(requestId, null)
    fun pick(labels: List<String>) {
        val arr = JSONArray()
        labels.forEach { arr.put(it) }
        host.answerUi(requestId, JSONObject().put("selected", arr))
    }

    val title = when (current.kind) {
        "input" -> "插件需要输入"
        "quickPick" -> "插件需要选择"
        else -> when {
            current.pluginId == "nebulaforge" -> "插件申请权限"
            else -> "插件请求"
        }
    }

    AlertDialog(
        onDismissRequest = { cancel() },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    current.pluginId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        Text(current.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                when (current.kind) {
                    "input" -> {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = textValue,
                            onValueChange = { textValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            label = { Text("输入") }
                        )
                    }

                    "quickPick" -> {
                        Spacer(Modifier.height(8.dp))
                        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            current.options.forEach { option ->
                                if (current.multi) {
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            checked[option] = !(checked[option] ?: false)
                                        },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = checked[option] ?: false,
                                            onCheckedChange = { checked[option] = it }
                                        )
                                        Text(
                                            option,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                } else {
                                    Text(
                                        option,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { pick(listOf(option)) }
                                            .padding(vertical = 10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 选项就是按钮：点中哪个就把哪个标签回给扩展。
            // （VS Code 语义：扩展拿到的 `selected` 是用户点的那个 label，正是它拿去匹配 payload 的键，
            //   所以这里绝不能自己改写标签文案。）
            when {
                current.kind == "input" -> TextButton(onClick = {
                    host.answerUi(requestId, JSONObject().put("text", textValue))
                }) { Text("确定") }

                current.kind == "quickPick" && current.multi -> TextButton(onClick = {
                    pick(checked.filterValues { it }.keys.toList())
                }) { Text("确定") }

                current.options.isEmpty() -> TextButton(onClick = { cancel() }) { Text("知道了") }

                else -> Row {
                    current.options.forEach { option ->
                        TextButton(onClick = { pick(listOf(option)) }) { Text(option) }
                    }
                }
            }
        },
        dismissButton = {
            // 有选项的提示（含能力授权）允许「取消」= 回 null；输入/多选也留取消。
            // 只有「没有选项的纯提示」不给取消（它上面已经有「知道了」，重复按钮反而让人糊涂）。
            if (current.options.isNotEmpty() || current.kind == "input" || current.kind == "quickPick") {
                TextButton(onClick = { cancel() }) { Text("取消") }
            }
        }
    )
}
