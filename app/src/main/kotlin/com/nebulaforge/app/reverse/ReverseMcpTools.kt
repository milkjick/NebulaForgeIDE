package com.nebulaforge.app.reverse

import com.nebulaforge.core.mcp.McpToolDefinition
import org.json.JSONArray
import org.json.JSONObject

private typealias McpToolHandler = suspend (JSONObject) -> JSONObject

/**
 * 逆向能力 → 内部 MCP 聚合网关工具表。
 *
 * ## 为什么要有这一层
 * AI 工作台里 Agent 已经能直接调 `apk_reverse` / `web_reverse` / `api_reverse`（[com.nebulaforge.core.agent.AgentToolCatalog]），
 * 但那只有「本应用内的 Agent」能用。**内部 MCP Server 是另一条通道**：MCP 面板、
 * 聚合网关、以及通过 `mcp_call` 触发的模型都会走 `tools/list` + `tools/call`。
 * 逆向能力此前没注册进去，于是外部/网关视角下「这个 IDE 没有逆向能力」。
 *
 * ## 设计取舍
 *  - **不复制业务逻辑**：这里只把参数翻译成既有的运行时方法（APK/Web/API/CA），
 *    真正的实现留在 `NebulaForgeApplication` 与逆向引擎里，避免两份行为逐渐漂移。
 *  - **返回结构化 JSON**：每个工具都回 `{ok, text}`。`text` 是给人/模型读的自然语言结果
 *    （与 AI 工具的输出一致），`ok` 便于网关程序化判断成败，不用去猜字符串前缀。
 *  - **工具名用 `reverse_` 前缀**：与 Agent 的直接工具名区分开，避免在模型提示词里出现
 *    两个同名工具却语义不同；`reverse_proxy` / `reverse_ca` 这类名字也一眼能看出属于逆向域。
 */
object ReverseMcpTools {

    /**
     * 组装工具表。
     *
     * 用函数入参而不是直接依赖 Application：这样单元/编译期就能验证装配，
     * 也避免 core 层反向依赖 app 层。
     */
    fun toolsFor(
        apkReverse: suspend (action: String, path: String?, file: String?, content: String?, out: String?) -> String,
        webReverse: suspend (action: String, url: String?, limit: Int) -> String,
        apiReverse: suspend (action: String, filter: String?, port: Int?) -> String,
        caCertificate: suspend (action: String) -> String
    ): Map<String, Pair<McpToolDefinition, McpToolHandler>> = linkedMapOf(
        "reverse_apk" to (
            define(
                "reverse_apk",
                "APK 逆向：inspect=读结构（包名/权限/组件/DEX/签名/内置 URL）；unpack=反编译资源与 smali 并生成 Java 源码；" +
                    "files/read/write=查看与修改反编译产物；rebuild=回编并签名；evidence=最近一次证据摘要。",
                listOf(
                    Triple("action", "string", "inspect | unpack | files | read | write | rebuild | evidence"),
                    Triple("path", "string", "APK 路径或反编译工作区目录"),
                    Triple("file", "string", "read/write 的工作区文件路径"),
                    Triple("content", "string", "write 的新内容"),
                    Triple("out", "string", "rebuild 输出 APK 路径（留空自动命名）")
                ),
                listOf("action")
            ) to { args ->
                invoke("reverse_apk") {
                    apkReverse(
                        args.optString("action").ifBlank { "inspect" },
                        args.optString("path").takeIf { it.isNotBlank() },
                        args.optString("file").takeIf { it.isNotBlank() },
                        args.optString("content").takeIf { it.isNotBlank() },
                        args.optString("out").takeIf { it.isNotBlank() }
                    )
                }
            }
            ),

        "reverse_web_records" to (
            define(
                "reverse_web_records",
                "Web 逆向：查看内置浏览器捕获的网络记录（文档/接口/SSE/WebSocket/静态资源）。" +
                    "action=records 列表 / analyze 交 AI 归纳 / to_api 并入接口库 / clear 清空。",
                listOf(
                    Triple("action", "string", "records | analyze | to_api | clear"),
                    Triple("url", "string", "按 URL 关键字过滤（可选）"),
                    Triple("limit", "number", "最多返回多少条，默认 40")
                ),
                listOf("action")
            ) to { args ->
                invoke("reverse_web_records") {
                    webReverse(
                        args.optString("action").ifBlank { "records" },
                        args.optString("url").takeIf { it.isNotBlank() },
                        args.optInt("limit", 40).coerceIn(1, 200)
                    )
                }
            }
            ),

        "reverse_api_endpoints" to (
            define(
                "reverse_api_endpoints",
                "API 逆向：管理已捕获的真实接口。action=endpoints 清单 / analyze AI 归纳 / openapi 导出 OpenAPI 3.0 / clear 清空。",
                listOf(
                    Triple("action", "string", "endpoints | analyze | openapi | clear"),
                    Triple("filter", "string", "按 URL 关键字过滤（可选）")
                ),
                listOf("action")
            ) to { args ->
                invoke("reverse_api_endpoints") {
                    apiReverse(args.optString("action").ifBlank { "endpoints" }, args.optString("filter").takeIf { it.isNotBlank() }, null)
                }
            }
            ),

        "reverse_proxy" to (
            define(
                "reverse_proxy",
                "本机 MITM 代理开关（仅监听 127.0.0.1）。action=start 启动 / stop 停止 / status 查询；start 可用 port 指定端口。",
                listOf(
                    Triple("action", "string", "start | stop | status"),
                    Triple("port", "number", "start 的端口，留空自动分配")
                ),
                listOf("action")
            ) to { args ->
                val action = args.optString("action").ifBlank { "status" }
                val port = if (args.has("port")) args.optInt("port").takeIf { it in 1..65535 } else null
                invoke("reverse_proxy") {
                    when (action) {
                        "start" -> apiReverse("proxy_start", null, port)
                        "stop" -> apiReverse("proxy_stop", null, null)
                        else -> apiReverse("proxy_status", null, null)
                    }
                }
            }
            ),

        "reverse_ca" to (
            define(
                "reverse_ca",
                "本机 MITM CA 证书：抓 HTTPS 的门槛。action=status 查状态 / export 导出到公共目录 / " +
                    "install_system 写入系统证书库（需要 Root 或已授权的 Shizuku）/ install_user 打开用户证书安装界面。",
                listOf(Triple("action", "string", "status | export | install_system | install_user")),
                listOf("action")
            ) to { args ->
                invoke("reverse_ca") { caCertificate(args.optString("action").ifBlank { "status" }) }
            }
            ),

        "reverse_toolchain" to (
            define(
                "reverse_toolchain",
                "逆向工具链自检：JDK / apktool / JADX / apksigner 是否就绪，缺什么、去哪儿装。",
                emptyList(),
                emptyList()
            ) to {
                invoke("reverse_toolchain") { apkReverse("toolchain", null, null, null, null) }
            }
            ),

        "reverse_evidence" to (
            define(
                "reverse_evidence",
                "逆向证据汇总：最近一次分析过的 APK、Web 抓包条数、接口条数与报告摘要。",
                emptyList(),
                emptyList()
            ) to {
                invoke("reverse_evidence") { apkReverse("evidence", null, null, null, null) }
            }
            )
    )

    /** 统一把「可能失败/可能抛异常」的运行时调用收敛成 `{ok, text}`，网关侧不必解析字符串前缀。 */
    private suspend fun invoke(tool: String, block: suspend () -> String): JSONObject = try {
        val text = block()
        JSONObject().put("tool", tool).put("ok", !text.startsWith("✗")).put("text", text)
    } catch (t: Throwable) {
        JSONObject().put("tool", tool).put("ok", false)
            .put("text", "✗ $tool 执行失败：${t.message ?: t.javaClass.simpleName}")
    }

    private fun define(
        name: String,
        description: String,
        properties: List<Triple<String, String, String>>,
        required: List<String>
    ): McpToolDefinition = McpToolDefinition(
        name,
        description,
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                properties.forEach { (propName, type, desc) ->
                    put(propName, JSONObject().put("type", type).put("description", desc))
                }
            })
            put("required", JSONArray().apply { required.forEach { put(it) } })
        }
    )
}
