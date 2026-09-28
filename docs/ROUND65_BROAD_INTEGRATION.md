# Round 65：跨模块连续工作区整合

本轮不是单模块增量，而是同时推进以下链路：

- Build / Run / Logcat 统一事件投影继续扩展：产物、设备、诊断、关联会话进入 `SessionStateProjection`。
- Workspace 工具窗补齐开发方案要求的 10 个稳定 ID，并继续复用真实项目/会话数据。
- Build Center 恢复为真实入口，直接使用当前项目的持久化 Run Configuration。
- Terminal 创建会话后接入统一 Session Bus（在 app 层完成，避免 core-session/core-terminal 循环依赖）。
- MCP 远程客户端由 Host 持有，initialize 后复用会话 ID，不再每个按钮重新建立客户端。
- MCP Host 增加初始化、工具列表和关闭生命周期。
- 插件运行时从“只解析 plugin.xml”推进到 API 兼容性、权限校验、DexClassLoader、扩展实例化、加载/卸载生命周期。
- AI Provider 增加 OpenAI Compatible / Anthropic / Gemini 三种协议实现，并提供 ProviderRegistry。
- 修复上一轮遗留的 PluginRuntime Kotlin 语法残留和缺失 BuildCenterScreen。

## 验证

已完成源码结构/括号检查和 ZIP 完整性检查。
当前执行环境没有可用的 Gradle 8.9 本地发行版，因此没有把静态检查结果冒充为 Android Gradle 编译成功。
