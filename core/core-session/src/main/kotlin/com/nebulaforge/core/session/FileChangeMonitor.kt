package com.nebulaforge.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 监视项目文件的外部修改。它不执行任何构建/写入操作，只把变化转换成统一 IDE 事件。
 * 采用轻量轮询以兼容 Android 应用沙箱、SAF 导入目录以及 Terminal 子进程。
 */
class FileChangeMonitor(
    private val bus: IdeSessionBus,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val jobs = mutableMapOf<String, Job>()

    @Synchronized
    fun watch(file: File): String {
        val normalized = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
        val key = normalized.absolutePath
        jobs[key]?.cancel()
        val sessionId = "editor:${Integer.toHexString(key.hashCode())}"
        val job = scope.launch {
            var last = snapshot(normalized)
            while (isActive) {
                delay(500)
                val now = snapshot(normalized)
                if (now != last) {
                    last = now
                    bus.emit(IdeEvent.FileChanged(sessionId, key, now.modified, now.length))
                }
            }
        }
        jobs[key] = job
        return sessionId
    }

    @Synchronized
    fun unwatch(file: File) {
        val key = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile).absolutePath
        jobs.remove(key)?.cancel()
    }

    @Synchronized
    fun close() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }

    private fun snapshot(file: File): Snapshot = Snapshot(
        modified = if (file.exists()) file.lastModified() else -1L,
        length = if (file.exists()) file.length() else -1L
    )

    private data class Snapshot(val modified: Long, val length: Long)
}
