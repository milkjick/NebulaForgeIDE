package com.nebulaforge.app.build

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nebulaforge.app.device.ApkInstaller
import com.nebulaforge.app.device.InstallOutcome
import com.nebulaforge.core.device.DeviceChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor = Color(0xFF1E1E1E)
private val BarColor = Color(0xFF252526)
private val TextColor = Color(0xFFCCCCCC)
private val DimColor = Color(0xFF858585)
private val OkColor = Color(0xFF89D185)
private val ErrColor = Color(0xFFF48771)
private val WarnColor = Color(0xFFD7BA7D)
private val AccentColor = Color(0xFF4EC9B0)
private val Mono = FontFamily.Monospace

/**
 * 「安装到本机」弹窗。
 *
 * 用户需求原话是「编译出 apk 之后可以选择直接安装到系统」，所以这里把**通道**显式摆出来给用户选：
 *  * 静默安装（Shizuku / Root）—— 走 `pm install`，全程没有系统弹窗；
 *  * 系统安装器 —— 交给系统 UI，用户手动确认（没有 Shizuku / Root 时的唯一出路）。
 *
 * 两条路的体验差别很大，所以结果区必须如实回报**实际**使用的通道与原始输出，
 * 不能只说一句「完成」。
 */
@Composable
fun InstallApkDialog(apk: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = remember(apk) { ApkInstaller.describe(context, apk) }
    val silentChannel = remember(apk) { ApkInstaller.silentChannel(context) }
    val hasSilent = silentChannel != DeviceChannel.NONE
    val unknownBlocked = remember(apk) { ApkInstaller.unknownSourcesBlocked(context) }

    var silentMode by remember(apk) { mutableStateOf(hasSilent) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<InstallOutcome?>(null) }
    // 「构建成功之后要能自动安装，或只打包成本地文件」——两个出口都摆在这个弹窗里。
    var autoInstall by remember { mutableStateOf(ApkPackager.autoInstallEnabled(context)) }
    var exportPath by remember { mutableStateOf<String?>(null) }
    var exportBusy by remember { mutableStateOf(false) }

    val started = remember(info.lastModified) {
        if (info.lastModified > 0) SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(info.lastModified)) else "未知"
    }

    fun start() {
        busy = true
        result = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (silentMode) ApkInstaller.installSilently(context, apk)
                else ApkInstaller.openSystemInstaller(context, apk)
            }
            result = outcome
            busy = false
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(color = BgColor, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("安装到本机", color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "把刚构建出的 APK 装到这台设备上",
                    color = DimColor, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                )

                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .background(BarColor, RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    InfoRow("文件", info.file.name)
                    InfoRow("包名", info.packageName ?: "（读不到，可能不是完整 APK）")
                    InfoRow("版本", info.versionName?.let { "$it (${info.versionCode})" } ?: "未知")
                    InfoRow("大小", info.sizeText)
                    InfoRow("产出时间", started)
                }

                Text(
                    "安装方式",
                    color = DimColor, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                )
                ChannelOption(
                    selected = silentMode,
                    enabled = hasSilent,
                    title = "静默安装（直接装入系统）",
                    subtitle = if (hasSilent) {
                        "${silentChannel.label} 通道 · 以 ${if (silentChannel == DeviceChannel.ROOT) "uid=0" else "shell"} 执行 pm install，全程无系统弹窗"
                    } else {
                        "不可用：需要先授权 Shizuku（设备与权限 页）或设备已 Root"
                    },
                    accent = AccentColor,
                    onClick = { if (!busy) silentMode = true }
                )
                ChannelOption(
                    selected = !silentMode,
                    enabled = true,
                    title = "系统安装器（手动确认）",
                    subtitle = "交给系统安装界面，需要你点一下「安装」",
                    accent = WarnColor,
                    onClick = { if (!busy) silentMode = false }
                )

                if (!silentMode && unknownBlocked) {
                    Row(
                        Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .background(Color(0xFF2A2318), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("系统未允许本应用安装未知应用", color = WarnColor, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { ApkInstaller.openUnknownSourcesSettings(context) }) {
                            Text("去授权", color = AccentColor, fontSize = 12.sp)
                        }
                    }
                }

                result?.let { r ->
                    Column(
                        Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .background(BarColor, RoundedCornerShape(6.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            (if (r.ok) "✓ " else "✗ ") + r.message +
                                if (r.channel != DeviceChannel.NONE) "（通道：${r.channel.label}）" else "",
                            color = if (r.ok) OkColor else ErrColor, fontSize = 12.sp, fontWeight = FontWeight.Medium
                        )
                        if (r.detail.isNotBlank()) {
                            Text(
                                r.detail,
                                color = DimColor, fontSize = 10.sp, fontFamily = Mono,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        if (r.ok && info.packageName != null) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { ApkInstaller.launchApp(context, info.packageName!!) }
                                    }
                                },
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text("启动 ${info.packageName}", color = AccentColor, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // ── 出口 2：只打包到本机文件（不安装）───────────────────────────────
                TextButton(
                    onClick = {
                        exportBusy = true
                        exportPath = null
                        scope.launch {
                            val out = withContext(Dispatchers.IO) {
                                ApkPackager.export(context, apk, projectRoot = null)
                            }
                            exportPath = out?.absolutePath ?: "导出失败：存储不可写"
                            exportBusy = false
                        }
                    },
                    enabled = !busy && !exportBusy
                ) {
                    Text(if (exportBusy) "正在导出…" else "📦 只打包到本机（不安装）", color = AccentColor, fontSize = 12.sp)
                }
                exportPath?.let { p ->
                    Text(
                        if (p.startsWith("导出失败")) p else "已导出：$p",
                        color = if (p.startsWith("导出失败")) ErrColor else OkColor,
                        fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = autoInstall,
                        onClick = {
                            autoInstall = !autoInstall
                            ApkPackager.setAutoInstall(context, autoInstall)
                        }
                    )
                    Text("以后构建成功自动安装（不再弹这个窗口）", color = DimColor, fontSize = 11.sp)
                }
                Row(
                    Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = AccentColor, strokeWidth = 2.dp)
                        Text("正在安装…", color = DimColor, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) {
                        Text(if (result?.ok == true) "完成" else "取消", color = DimColor, fontSize = 13.sp)
                    }
                    TextButton(onClick = { start() }, enabled = !busy && !(silentMode && !hasSilent)) {
                        Text("开始安装", color = if (busy) DimColor else OkColor, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(key, color = DimColor, fontSize = 11.sp, modifier = Modifier.padding(end = 10.dp))
        Text(value, color = TextColor, fontSize = 11.sp, fontFamily = Mono)
    }
}

@Composable
private fun ChannelOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .border(1.dp, if (selected) accent else Color(0xFF3C3C3C), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = { onClick() }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) TextColor else DimColor, fontSize = 12.sp)
            Text(subtitle, color = DimColor, fontSize = 10.sp)
        }
    }
}
