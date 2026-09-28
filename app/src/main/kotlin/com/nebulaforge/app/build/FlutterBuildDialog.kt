package com.nebulaforge.app.build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Flutter 工程的「构建方式」弹窗 —— 交互与 [AndroidBuildDialog] 保持一致，
 * 让「顶栏 ▶ 构建 → 先选编什么」在两种工程上是同一套肌肉记忆。
 *
 * 差别只有一处：Flutter 没有 Gradle 模块概念，所以「编哪一部分」这一档换成 ABI
 * （真机上只装一种 ABI，编全 ABI 要 3 倍时间），并且额外给出 `flutter analyze`
 * 作为最快的「只验证能不能过编译」。
 */
@Composable
fun FlutterBuildDialog(
    projectName: String,
    initial: FlutterBuildPlan,
    onDismiss: () -> Unit,
    onStart: (FlutterBuildPlan) -> Unit
) {
    var plan by remember(initial) { mutableStateOf(initial) }
    val preview = remember(plan) { plan.command() }
    val artifactHint = plan.artifactHint()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("构建方式 · Flutter", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(projectName, fontSize = 12.sp, color = TermDim, maxLines = 1)
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FlutterBuildPlan.Section.entries.forEachIndexed { i, section ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    FSectionTitle(section.title)
                    Text(section.hint, fontSize = 11.sp, color = TermDim)
                    Spacer(Modifier.height(2.dp))
                    FlutterBuildPlan.Goal.entries.filter { it.section == section }.forEach { goal ->
                        FChoiceRow(
                            selected = plan.goal == goal,
                            title = goal.label,
                            detail = goal.detail,
                            onClick = {
                                plan = plan.copy(
                                    goal = goal,
                                    // 「只做清理」与「先 clean」互斥：否则会出现 `flutter clean` 前再 clean 一次
                                    // 这种对用户毫无意义、只会让人怀疑工具的组合。
                                    cleanFirst = if (goal == FlutterBuildPlan.Goal.CLEAN) false else plan.cleanFirst
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                FSectionTitle("④ 附加方式")
                FToggleRow(
                    title = "先执行 flutter clean",
                    detail = "依赖缓存/产物损坏时的救命选项；代价是下一次要重新编译",
                    checked = plan.cleanFirst,
                    enabled = plan.goal != FlutterBuildPlan.Goal.CLEAN
                ) { plan = plan.copy(cleanFirst = it) }
                FToggleRow(
                    title = "详细输出（-v）",
                    detail = "打印完整命令行与各阶段耗时，排查构建失败时用",
                    checked = plan.verbose
                ) { plan = plan.copy(verbose = it) }

                Spacer(Modifier.height(14.dp))
                FSectionTitle("将要执行")
                Text(
                    text = preview,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TermFgLocal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D2D30), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                )
                artifactHint?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("预期产物：$it", fontSize = 11.sp, color = ArtifactOkLocal)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "说明：模板工程只有 Dart 层（pubspec.yaml + lib/main.dart），没有 android/ 骨架，" +
                        "构建时命令会自动先跑一次 `flutter create --platforms=android .` 补齐 —— 首次多花十几秒，之后零开销。",
                    fontSize = 10.sp,
                    color = TermDim
                )
            }
        },
        confirmButton = {
            Button(onClick = { onStart(plan) }) { Text("开始构建", fontSize = 13.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", fontSize = 13.sp, color = TermDim) }
        }
    )
}

// 注：以下小部件与 AndroidBuildDialog 中的同名 private 部件是有意的两份 ——
// 它们都是 file-private（Kotlin 顶层 private = 本文件可见），不会互相影响，
// 也避免为了共用而把两个弹窗的布局耦合在一起。

@Composable
private fun FSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = TermAccent,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun FChoiceRow(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(detail, fontSize = 11.sp, color = TermDim)
        }
    }
}

@Composable
private fun FToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked && enabled, enabled = enabled, onCheckedChange = { onChange(it) })
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = if (enabled) Color.Unspecified else TermDim)
            Text(detail, fontSize = 11.sp, color = TermDim)
        }
    }
}

private val TermFgLocal = Color(0xFFD4D4D4)
private val ArtifactOkLocal = Color(0xFF89D185)
