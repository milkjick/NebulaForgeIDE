# Round 69 · Broad Runtime Integration

本轮继续采用跨模块整合，而不是只增加单一页面。

## 1. Toolchain
- 保持 Way-B embedded Termux 作为工具链唯一执行路径。
- JDK/Gradle/Git/ADB/sdkmanager/aapt2 等状态继续通过真实二进制探测确认。
- Terminal、Build/Run 与 ToolchainManager 使用同一 Environment 环境源。

## 2. Git / Version Control
- 版本控制工具窗通过 TermuxCommandExecutor 执行 Git，不直接调用 Android ProcessBuilder。

## 3. MCP
- McpHost 增加服务器状态快照 StateFlow。
- 内部 MCP 工具可从管理页面直接调用，并使用 JSON 参数。
- Workspace 内部 Server 的 run_build 已进入 UnifiedRunController 链路。

## 4. AI Provider
- Application 持有统一 ProviderRegistry。
- core-agent 增加 AiProviderSettings -> ProviderConfig 的统一转换，避免 Agent 自己拼装协议配置。

## 5. Plugin Runtime
- 增加 PluginExtensionRegistry。
- 插件加载时记录 ProjectType 和通用扩展实例。
- 插件卸载时移除扩展注册，避免继续保留已卸载插件的扩展实例。
- Application 暴露插件加载入口并将 ProjectType 接入核心 ProjectTypeRegistry。

## 6. 验证边界
- ZIP 完整性与源码静态检查可以在当前环境执行。
- 当前环境无法下载 Gradle 8.9，因此不能把静态检查描述为完整 Gradle 编译验证。
