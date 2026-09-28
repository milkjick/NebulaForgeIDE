package com.nebulaforge.app.build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 「构建方式」弹窗 —— 点「▶ 构建」时先弹这个，让用户自己决定**编什么、怎么编**。
 *
 * 覆盖用户反馈的两件事：
 *  1. 「编译 apk 时无法自由选择要编译什么」→ 产物在这里选（测试包/正式包/AAB/装到设备/单测/Lint/清理）；
 *  2. 「希望能选择编译的部分和方式」→ 编译范围可选模块（`:app`、`:feature-x`…），
 *     方式可选「先 clean / 离线 / 跳过测试」，并实时预览将要执行的完整命令。
 *
 * 之所以做成弹窗而不是再塞几个 switch 到工具条：工具条已经因为任务名过长被挤爆过一次，
 * 构建方式是低频但高信息量的选择，独立弹窗既能容纳说明文字，也不会挤压工具条。
 */
@Composable
fun AndroidBuildDialog(
    projectName: String,
    projectRoot: File,
    initial: AndroidBuildPlan,
    onDismiss: () -> Unit,
    onStart: (AndroidBuildPlan) -> Unit,
    selectedTaskLabel: String? = null,
    onRunSelectedTask: (() -> Unit)? = null
) {
    var plan by remember(initial) { mutableStateOf(initial) }
    val modules = remember(projectRoot) { AndroidBuildPlan.moduleInfos(projectRoot) }
    val preview = remember(plan, projectRoot) { plan.commandLine(projectRoot) }
    val artifactHint = plan.artifactHint()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("构建方式", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(projectName, fontSize = 12.sp, color = TermDim, maxLines = 1)
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ---------------------------------------------------------- 编什么
                // 按分组渲染（打包 / 只编译 / 其它），而不是把所有目标排成一长条：
                // 用户开口就要「全量包、测试包，或者只编译一部分」，分组让这三件事一眼可辨。
                AndroidBuildPlan.Section.entries.forEachIndexed { i, section ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    SectionTitle(section.title)
                    Text(section.hint, fontSize = 11.sp, color = TermDim)
                    Spacer(Modifier.height(2.dp))
                    AndroidBuildPlan.Goal.entries.filter { it.section == section }.forEach { goal ->
                        ChoiceRow(
                            selected = plan.goal == goal,
                            title = goal.label,
                            detail = goal.detail,
                            onClick = {
                                plan = plan.copy(
                                    goal = goal,
                                    // 「只做清理」与附加项互斥：切换时复位，避免出现 “clean -x test”
                                    // 这种对用户毫无意义、只会让人怀疑工具的组合。
                                    cleanFirst = if (goal == AndroidBuildPlan.Goal.CLEAN) false else plan.cleanFirst,
                                    skipTests = if (goal.runsTests) plan.skipTests else false
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---------------------------------------------------------- 编哪一部分
                SectionTitle("④ 编译范围（只编译一部分）")
                Text(
                    text = "选「整个工程」是最稳的默认；只想快速验证某个模块时，选具体模块即可只跑它的任务。",
                    fontSize = 11.sp,
                    color = TermDim
                )
                Spacer(Modifier.height(2.dp))
                ChoiceRow(
                    selected = plan.module.isBlank(),
                    title = "整个工程",
                    detail = "所有模块一起编（默认，最稳）",
                    onClick = { plan = plan.copy(module = "") }
                )
                modules.forEach { m ->
                    // 该模块下这一步真正会执行的任务：模块类型不同任务名不同（AGP 才有 assembleDebug），
                    // 这里直接算给用户看，避免“选了 :core 却执行 assembleDebug 报 Task not found”。
                    val resolved = plan.copy(module = m.path).taskName(projectRoot)
                    ChoiceRow(
                        selected = plan.module == m.path,
                        title = "只编 " + m.path,
                        detail = (if (m.android) "Android 模块" else "JVM 模块") + "，要执行的任务：" + resolved,
                        onClick = { plan = plan.copy(module = m.path) }
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ---------------------------------------------------------- 怎么编
                SectionTitle("⑤ 构建附加项")
                val keepable = plan.goal != AndroidBuildPlan.Goal.CLEAN
                ToggleRow("先执行 clean 再构建", "删掉 build/ 缓存，等价 clean + 目标任务", plan.cleanFirst && keepable, enabled = keepable) {
                    plan = plan.copy(cleanFirst = it)
                }
                ToggleRow("离线构建（--offline）", "只用本地缓存依赖，断网/省流量时用", plan.offline) {
                    plan = plan.copy(offline = it)
                }
                ToggleRow(
                    "跳过测试任务（-x test）",
                    "只出安装包，不跑 test 任务",
                    plan.skipTests && plan.goal.runsTests,
                    enabled = plan.goal.runsTests
                ) { plan = plan.copy(skipTests = it) }
                ToggleRow(
                    "单线程构建（--max-workers=1）",
                    "真机内存紧时更稳：并发 worker 容易被系统 OOM 杀掉，表现为构建莫名中断（137）",
                    plan.limitedWorkers
                ) { plan = plan.copy(limitedWorkers = it) }

                Spacer(Modifier.height(14.dp))

                SectionTitle("将要执行")
                Text(
                    text = preview,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TermDim,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TermBarBg, RoundedCornerShape(6.dp))
                        .padding(8.dp)
                )
                artifactHint?.let { hint ->
                    Spacer(Modifier.height(6.dp))
                    Text("产物：$hint", fontSize = 11.sp, color = ArtifactOk)
                }
                if (plan.goal.compileOnly) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "「只编译」不会生成 APK；Gradle 会自动增量，只重编改动过的部分。",
                        fontSize = 11.sp,
                        color = TermDim
                    )
                }

                if (onRunSelectedTask != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "或直接用面板里选中的任务：${selectedTaskLabel ?: "—"}",
                            fontSize = 12.sp,
                            color = TermAccent,
                            modifier = Modifier
                                .clickable { onRunSelectedTask() }
                                .padding(vertical = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStart(plan) },
                colors = ButtonDefaults.buttonColors(containerColor = TermAccent)
            ) { Text("▶ 开始构建", fontSize = 13.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", fontSize = 13.sp, color = TermDim) }
        }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = TermAccent,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
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
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    val color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else TermDim
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked && enabled, enabled = enabled, onCheckedChange = { onChange(it) })
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = color)
            Text(detail, fontSize = 11.sp, color = TermDim)
        }
    }
}

/** 产物提示用的绿色。与构建面板的 TermOk 同色，但那边是 private，这里自带一份。 */
private val ArtifactOk = androidx.compose.ui.graphics.Color(0xFF89D185)

/** 弹窗里的命令预览底色：比工具条再浅一点，避免和终端输出混淆。 */
private val TermBarBg = androidx.compose.ui.graphics.Color(0xFF2D2D30)
