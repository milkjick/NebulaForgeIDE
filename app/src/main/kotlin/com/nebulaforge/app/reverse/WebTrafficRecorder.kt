package com.nebulaforge.app.reverse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** WebView 可观察网络事件的线程安全记录器。 */
class WebTrafficRecorder {
    enum class Kind { DOCUMENT, API, SSE, WEBSOCKET, CHUNKED, STATIC, OTHER }

    data class Record(
        val id: Long,
        val timeMs: Long,
        val method: String,
        val url: String,
        val kind: Kind,
        val requestHeaders: Map<String, String>,
        val statusCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val mimeType: String? = null,
        val responseBytes: Long = 0,
        val responsePreview: String? = null,
        val responseCaptured: Boolean = false,
        val note: String? = null
    )

    private val nextId = AtomicLong(1)
    private val _records = MutableStateFlow<List<Record>>(emptyList())
    val records: StateFlow<List<Record>> = _records.asStateFlow()

    @Synchronized
    fun add(record: Record) {
        _records.value = (_records.value + record).takeLast(500)
    }

    fun newId(): Long = nextId.getAndIncrement()

    @Synchronized
    fun updateCapture(id: Long, bytes: Long, previewBytes: ByteArray = ByteArray(0)) {
        if (bytes <= 0 && previewBytes.isEmpty()) return
        _records.value = _records.value.map { record ->
            if (record.id != id) return@map record
            val preview = if (previewBytes.isEmpty()) record.responsePreview else {
                val old = record.responsePreview.orEmpty()
                val remaining = (16_384 - old.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
                if (remaining == 0) old else old + previewBytes.copyOf(previewBytes.size.coerceAtMost(remaining)).toString(Charsets.UTF_8)
            }
            record.copy(responseBytes = (record.responseBytes ?: 0L) + bytes, responsePreview = preview?.take(16_384))
        }
    }

    @Synchronized
    fun clear() {
        _records.value = emptyList()
    }
}
