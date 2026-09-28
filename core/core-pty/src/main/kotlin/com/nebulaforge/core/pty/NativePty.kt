package com.nebulaforge.core.pty

object NativePty {
    init { System.loadLibrary("nebulapty") }
    external fun nativeOpen(shell: String, rows: Int, cols: Int, argv: Array<String>?, env: Array<String>?): Long
    external fun nativeRead(handle: Long, out: ByteArray): Int
    external fun nativeWrite(handle: Long, data: ByteArray): Int
    external fun nativeResize(handle: Long, rows: Int, cols: Int): Int
    external fun nativeSignal(handle: Long, signal: Int): Int
    external fun nativeWait(handle: Long): Int
    external fun nativeClose(handle: Long)
}
