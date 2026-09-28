package com.nebulaforge.core.pty

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable

/**
 * 纯 PTY 会话抽象。核心 PTY 模块不依赖 session 模块，避免核心执行层形成循环依赖。
 * 上层可通过 onState/onOutput 回调把事件接入统一 Session Bus。
 */
class PtySession(
    private val shell: String,
    private val environment: Map<String, String>,
    private val onState: ((String) -> Unit)? = null
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handle = 0L
    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val output: SharedFlow<String> = _output.asSharedFlow()

    fun start(rows: Int = 24, cols: Int = 80) {
        val envArray = environment.map { "${it.key}=${it.value}" }.toTypedArray()
        handle = NativePty.nativeOpen(shell, rows, cols, PtyShellArgs.forShell(shell), envArray)
        if (handle == 0L) {
            onState?.invoke("FAILED")
            return
        }
        onState?.invoke("RUNNING")
        scope.launch {
            val buffer = ByteArray(16 * 1024)
            while (isActive && handle != 0L) {
                val n = NativePty.nativeRead(handle, buffer)
                if (n <= 0) break
                val text = buffer.copyOf(n).toString(Charsets.UTF_8)
                _output.emit(text)
            }
            if (handle != 0L) onState?.invoke("FINISHED")
        }
    }

    fun write(text: String) {
        if (handle != 0L) NativePty.nativeWrite(handle, text.toByteArray(Charsets.UTF_8))
    }
    fun resize(rows: Int, cols: Int) {
        if (handle != 0L) NativePty.nativeResize(handle, rows, cols)
    }
    fun interrupt() { if (handle != 0L) NativePty.nativeSignal(handle, 2) }
    fun suspendForeground() { if (handle != 0L) NativePty.nativeSignal(handle, 20) }
    override fun close() {
        scope.cancel()
        if (handle != 0L) {
            NativePty.nativeSignal(handle, 15)
            NativePty.nativeClose(handle)
            handle = 0L
        }
    }
}
