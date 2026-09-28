# Round58 — MCP / 插件运行时 / C++ 技术栈 / 缺口审计

## 本轮目标

以 Round57 为基线，对照开发方案第 8、14、15、17、19 章继续补全，同时处理此前“只有页面、没有真实能力”的缺口。

## 已完成

### 1. core-mcp

新增 `core/core-mcp`：

- JSON-RPC 2.0 `initialize` / `ping` / `tools/list` / `tools/call`
- `McpRequest` / `McpResponse` / `McpError` / `McpToolDefinition`
- `InternalMcpServer`
- 项目目录沙箱文件工具：`read_file`、`write_file`、`list_files`
- `run_build` 真实回调接口（未接入时明确返回未接入，而不是伪造成功）
- `McpHttpClient`：Streamable HTTP JSON POST、`Mcp-Session-Id`、SSE data 响应兼容
- `McpHost`：内部 Server / 外部 HTTP Client 生命周期注册

### 2. MCP 管理页面

新增 MCP 管理路由：

- 查看当前项目内部 MCP Tools
- 外部 MCP Server URL 初始化
- `tools/list` 请求
- 连接失败明确展示错误，不伪造连接成功

### 3. 插件运行时

新增 `core/core-plugin-runtime`：

- `plugin.xml` 描述符读取
- API min/max 校验字段
- dependency / permission / extension 解析
- `ExtensionPointType`
- 权限策略检查器
- `DexClassLoader` 工厂

插件安装页支持从本地 ZIP/APK 读取描述符并进行 schema 基本校验。

> 当前仍不宣称“完整插件沙箱”。DexClassLoader 只是类加载隔离基础；文件、Shell、网络等实际权限仍需宿主能力层进一步强制执行。

### 4. C/C++ 技术栈缺口

新增 `stack/stack-cpp`：

- CMake 项目检测
- CMake BuildSystem 抽象
- configure/build/clean 任务
- GCC/Clang 风格 `file:line:column:error|warning` 解析

真正的 CMake/NDK 命令仍由统一执行管道注入，不直接绕过 Way-B 运行时。

### 5. 之前功能缺口

- Build Center 已按当前 Workspace 项目调用 `RunConfigurationStore.forProject()`，避免空配置状态。
- MCP / 插件 / C++ 模块加入 settings.gradle 与 app 依赖。
- 首页插件入口不再是空按钮，已进入插件管理页；新增 MCP 管理入口。

## 尚未冒充完成的项目

1. 完整 ToolWindowRegistry 与所有工具窗统一 BottomSheet/BottomSheetScaffold。
2. 完整 Sora Editor + DocumentModel + CommandStack + LSP 深度统一。
3. Kotlin/Java LSP 全生命周期的完整 JSON-RPC transport。
4. 插件市场远程索引、签名校验、依赖解析、真正的权限运行时沙箱。
5. AI Provider 第十章完整实现。
6. AI 全项目生成事务链第六章完整实现。
7. MCP 外部 Server 的完整长连接/通知/取消/重连状态机。
8. API 逆向的 WebSocket frame 级捕获与复杂 TLS/HTTP2 场景。

这些项目保持明确为“未完成”，不会通过 UI 状态冒充完成。

## 验证

- strings.xml XML 解析通过。
- 本轮新增/修改 Kotlin 文件括号结构检查通过。
- 模块依赖无预期循环。
- 源码 ZIP 完整性检查通过。
- 完整 Gradle assembleDebug 仍受当前沙箱缺少 Gradle 8.9 本地发行版、且无法访问 Wrapper 镜像限制，不能声称完整构建成功。
