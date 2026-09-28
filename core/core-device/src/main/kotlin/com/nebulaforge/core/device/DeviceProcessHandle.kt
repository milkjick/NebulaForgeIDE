package com.nebulaforge.core.device

import java.io.InputStream
import java.io.OutputStream

/**
 * 长驻设备进程句柄（stdin/stdout 全双工）。
 *
 * 为什么需要它：一次性命令（[DeviceShellGate.exec]）拿的是「跑完了的整体输出」，而 MCP stdio 这类
 * 协议要求**保持连接**——边写请求边读响应。Shizuku 的 `newProcess` 正好提供全双工管道，
 * 无 Root 设备（Shizuku 即 shell 身份）也能跑本地 stdio MCP 服务器。
 */
class DeviceProcessHandle(
    val output: OutputStream,
    val input: InputStream,
    val error: InputStream,
    private val stop: () -> Unit
) {
    fun kill() {
        runCatching { stop() }
    }
}
