# Round60 — 编辑器内核、ToolWindow、AI Provider 核心补全

本轮以 Round59 源码为基线，继续对照《NebulaForge IDE 开发方案 v2》补齐真正的核心能力，不以 UI 占位代替实现。

## 已落地
- `core/core-editor-kernel`: DocumentModel、dirty/version、保存/重载、CommandStack、LSP JSON-RPC transport/client。
- `core/core-toolwindow`: ToolWindowDescriptor、Registry、移动端 ModalBottomSheet Host。
- `core/core-ai-provider`: ProviderConfig、AiProvider、OpenAI-compatible HTTP provider、连接测试。
- `plugins-sdk`: AI Tool / MCP Contributor / SDK Plugin 公开接口。
- settings.gradle/app 已正式接入上述模块。

## 与现有实现的关系
- FileEditorScreen 继续使用真实 Sora CodeEditor；本轮内核模块为后续 Sora↔DocumentModel 双向绑定提供统一状态源。
- core-agent 当前仍保留已有 AiCompletionClient 边界；下一轮将把它迁移到 core-ai-provider，避免 AI Provider 与 Agent 两套配置源。
- ToolWindow Host 已建立统一呈现入口；现有旧页面尚未全部迁移，不能标记为章节 13 完成。

## 验证
- ZIP 完整性：通过。
- 新增 Kotlin/Gradle 文件结构检查：通过。
- 完整 Gradle 编译：未能执行，沙箱缺少 Gradle 8.9 且网络不可用；因此不宣称 assembleDebug 成功。
