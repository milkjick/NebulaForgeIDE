package com.nebulaforge.app.reverse

import com.nebulaforge.app.reverse.api.ApiReverseRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 统一保存 APK/Web/API 逆向证据，使逆向工作台、AI Agent 与 MCP 使用同一状态源。 */
class ReverseEvidenceStore {
    data class Snapshot(
        val apkPath: String?,
        val apkReport: String?,
        val webCount: Int,
        val apiCount: Int,
        val capturedAt: Long = System.currentTimeMillis()
    )

    val webRecorder = WebTrafficRecorder()
    val apiRegistry = ApiReverseRegistry()

    @Volatile var lastApkPath: String? = null
        private set
    @Volatile var lastApkReport: String? = null
        private set

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun setApkEvidence(path: String, report: String) {
        lastApkPath = path
        lastApkReport = report
        _version.value++
    }

    fun snapshot(): Snapshot = Snapshot(
        apkPath = lastApkPath,
        apkReport = lastApkReport,
        webCount = webRecorder.records.value.size,
        apiCount = apiRegistry.all().size
    )

    fun clear() {
        webRecorder.clear()
        apiRegistry.clear()
        lastApkPath = null
        lastApkReport = null
        _version.value++
    }
}
