package com.nebulaforge.app.reverse

import com.nebulaforge.app.reverse.api.ApiReverseRegistry

/**
 * 逆向能力之间的「证据打通」桥接。
 *
 * Web 拦截层（[WebTrafficRecorder]）记的是「页面实际发了什么」，API 端点库
 * （[ApiReverseRegistry]）记的是「有哪些接口、参数长什么样」，两者本来各存各的：
 * 用户在 Web 页看到一堆接口，却还得手动抄到 API 页才能导出 OpenAPI。
 *
 * 这里把 Web 记录**归一化**后并入端点库，于是「浏览页面 → 得到接口清单 → 导出 OpenAPI →
 * 交给 AI/网关当工具」成为一条链路。AI 工具（`web_reverse(action=to_api)`）、
 * MCP 工具与界面按钮共用这一份实现，避免三处各写一遍转换逻辑而出现口径不一致。
 */
object ReverseBridge {

    /**
     * 把 Web 记录并入接口库，返回新增/更新的条数。
     *
     * 静态资源（js/css/图片）会被跳过：它们不是「接口」，混进端点库只会污染 OpenAPI 与工具清单。
     * [filter] 命中（不区分大小写，匹配 URL）时才并入，便于只沉淀关心的域名/前缀。
     */
    fun mergeWebRecordsIntoApi(
        records: List<WebTrafficRecorder.Record>,
        registry: ApiReverseRegistry,
        filter: String? = null
    ): Int {
        var merged = 0
        records.forEach { record ->
            if (record.kind == WebTrafficRecorder.Kind.STATIC) return@forEach
            if (filter != null && filter.isNotBlank() && !record.url.contains(filter, ignoreCase = true)) return@forEach
            registry.record(
                ApiReverseRegistry.Endpoint(
                    method = record.method.lowercase(),
                    url = record.url,
                    requestHeaders = record.requestHeaders,
                    requestBody = null,
                    responseHeaders = record.responseHeaders,
                    status = record.statusCode ?: 0,
                    mimeType = record.mimeType,
                    responsePreview = record.responsePreview
                )
            )
            merged++
        }
        return merged
    }

    /**
     * 「怎么用」的分步指引。
     *
     * 逆向页最容易让人卡住的地方不是按钮多，而是**不知道顺序**：先启代理 → 再装 CA →
     * 再打开信任开关 → 最后才可能有 HTTPS 流量。这里把顺序固定成文本，
     * 让界面（和 AI 工具结果）都能直接告诉用户下一步做什么。
     */
    fun usageSteps(proxyRunning: Boolean, caInstalled: Boolean, trustedInWebView: Boolean, recordCount: Int): String = buildString {
        append("Web 逆向 4 步：\n")
        append(if (proxyRunning) "1. ✓ 代理已运行\n" else "1. ⬜ 启动本机代理（抓 HTTPS 需要它）\n")
        append(if (caInstalled) "2. ✓ CA 已装到系统证书库\n" else "2. ⬜ 安装本机 CA（系统级；仅用户级证书 Android 7+ 不信任）\n")
        append(if (trustedInWebView) "3. ✓ 已打开「信任本地 MITM 证书」\n" else "3. ⬜ 打开「信任本地 MITM 证书」（否则 HTTPS 一条都抓不到）\n")
        append(
            if (recordCount > 0) "4. ✓ 已捕获 $recordCount 条记录，可在「记录」里筛看、导出、并入接口库\n"
            else "4. ⬜ 在内置浏览器访问目标站点，记录会自动出现在下方\n"
        )
    }
}
