package com.nebulaforge.core.projectmodel

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 统一运行配置：技术栈决定启动命令，核心 RunSession 只负责生命周期/事件。
 * Android 由 APK+ADB 专用控制器处理；Flutter/Web 使用本接口。
 */
interface RunConfiguration {
    val id: String
    fun command(projectRoot: File, mode: String = "run"): String
    fun environment(base: Map<String, String>): Map<String, String> = base
    fun supports(mode: String): Boolean = true
}
