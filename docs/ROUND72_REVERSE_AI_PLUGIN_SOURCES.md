# Round72：逆向 AI + 真实外部生态源

## 逆向 AI

APK、Web、API 共用 `ReverseAiAssistant` 和现有 `AiProviderSettingsStore`。AI 只接收已经由 IDE 捕获/解析的数据；修改建议只生成建议文本，不自动写入逆向工作区。

- APK：Manifest、权限、组件、DEX、URL、native libraries 摘要分析。
- Web：DOCUMENT/API/SSE/WEBSOCKET/CHUNKED 流量分类分析。
- API：端点分组、认证线索、请求/响应结构、OpenAPI/MCP 整理建议。
- 修改：可针对指定工作区文本请求 unified diff/修改建议，用户审查后再应用。

## 真实生态源

### 1. MCP 官方 Registry

`https://registry.modelcontextprotocol.io/v0.1/servers`

这是官方 MCP Registry 的真实 API，可用于发现公开 MCP Server。Nebula Forge 将其作为“AI/MCP 扩展源”，而不是伪装成 Nebula Plugin ZIP 源。

### 2. JetBrains Marketplace

`https://plugins.jetbrains.com`

这是 JetBrains 官方插件市场，适合发现 IntelliJ Platform 插件。但 IntelliJ 插件并不等于 Nebula Forge `plugin.xml`/SDK 插件，Round72 不允许直接把它们下载后当作 Nebula 插件加载。

### 3. GitHub 官方 MCP Server

`https://github.com/github/github-mcp-server`

可作为真实 MCP 服务参考/安装来源；它不是 Nebula 插件，因此通过 MCP Host 接入，而不是 DexClassLoader 插件运行时。

## 重要兼容性结论

目前公开搜索没有发现真正公开托管、声明兼容 `NebulaSdkPlugin` 的 Nebula Forge 第三方插件仓库。因此本轮没有伪造一个所谓“官方 Nebula 插件市场地址”。

Nebula 插件市场的可安装源必须提供：

1. HTTPS catalog JSON；
2. HTTPS 插件包；
3. `plugin.xml`；
4. API version 匹配；
5. SHA-256；
6. 依赖声明；
7. 权限声明；
8. 扩展点声明。

插件安装器会再次验证 `plugin.xml` ID/version 和 SHA-256。
