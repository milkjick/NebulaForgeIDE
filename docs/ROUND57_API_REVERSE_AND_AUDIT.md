# Round 57 — 9.3 API 逆向 + 前轮遗漏修复

## 本轮完成

1. **第 9.3 API 逆向**
   - 新增本机 HTTPS MITM 代理。
   - 代理仅监听 `127.0.0.1`，支持 HTTP `CONNECT`。
   - 为 CA 与目标域名动态生成 RSA 证书。
   - CA 证书持久化到应用私有目录，并提供 Android 系统证书安装 Intent。
   - 新增 AndroidX WebKit `ProxyController` 接入，使应用内 WebView 可以切换到本机代理。
   - 捕获 HTTP/HTTPS 方法、URL、请求头、响应头、状态码、MIME 与最多 16KB 响应预览。
   - 支持 `Content-Length` 与 chunked 响应的本地解码后转发。
   - 对重复端点按 method + scheme + host + path 去重。
   - 新增 OpenAPI 3.0.3 导出。
   - 新增 MCP-compatible 工具描述导出源；当前不是完整 MCP Server，避免伪造完成度。

2. **第 9.2 与 9.3 联动**
   - Reverse 工作台增加 `API / MITM` 标签。
   - API 代理启动后通过 `ProxyController` 作用于应用内 WebView。
   - 退出 API 页面会清除 WebView 代理覆盖，避免污染后续普通 WebView。

3. **前轮关键 bug 修复**
   - Build Center 不再只读取全局 `runConfigurations.all`；现在从持久 Workspace 的 `projectPath` 自动调用 `forProject()` 生成/恢复当前项目默认运行配置。
   - `NebulaForgeApplication` 暴露统一 `workspaceState`，供 Build Center 使用。
   - 为本地 MITM 增加 `network_security_config`，允许应用在用户明确安装 CA 后信任用户证书。
   - 修复代理响应经过 chunked 解码后仍发送旧 `Transfer-Encoding` 导致响应格式不一致的问题。
   - 修复上游响应流重复创建 BufferedInputStream 造成已缓冲数据丢失的问题。
   - 代理请求现在支持 `Content-Length` 与 chunked 请求体。
   - Build Center 本轮新增字符串资源，减少直接硬编码的 UI 文本。

## 诚实的功能边界

- 当前无法在本沙箱完成完整 `assembleDebug`：Gradle Wrapper 仍需要下载 Gradle 8.9，而沙箱网络无法访问配置的镜像域名。因此本轮没有宣称 APK 构建通过。
- AndroidX WebKit `ProxyController` 需要运行设备上的 WebView provider 支持相应 feature；代码会检查 `PROXY_OVERRIDE`，不支持时显示启动失败信息。
- HTTPS MITM 需要用户主动安装本地 CA；没有 CA 时不能声称已经解密目标 HTTPS 流量。
- 证书只用于本地授权调试；代理不监听外部网络接口。
- 当前 MCP 部分提供捕获端点的工具描述源，还没有接入完整 MCP JSON-RPC Host/Server 生命周期；第八章 MCP 主体仍需后续独立阶段完成。
- 当前代理对响应体做有限内存捕获（最多约 1 MiB 用于转发/分析），超大响应、长连接和真正的流式 SSE/WS 帧级分析仍需要专门的流式会话实现。

## 文档对照仍未完成的主要项目

- 第 9.1：apktool/JADX 实际 JAR 尚未随源码提供，当前为工具路径探测与真实命令执行框架。
- 第 10：AI Provider / 自定义反代设置尚未完整落地。
- 第 11/12：Sora Editor 全链路、LSP、命令栈与 Compose 编辑器仍需继续统一。
- 第 13：工具窗注册表与所有页面向底部抽屉/锚点工具窗的完整统一仍需继续。
- 第 14–17：插件描述符、ExtensionPoint、Runtime 隔离、Marketplace/本地安装尚未完整实现。
- 第 8：MCP Host/Server 管理、Streamable HTTP 等尚未完整实现。
- 第 6：AI 全项目生成事务、审查、回滚尚未完整落地。
- 全局 UI 文本仍有历史模块未全部迁移到 `strings.xml`。
- `feature-*` 独立模块拆分与文档目录结构尚未完全对齐当前历史源码布局。
