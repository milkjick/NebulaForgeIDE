# Round 61 — 编辑器状态源统一 + 实际 IDE Tool Window 接入

本轮基于 Round 60 源码继续实现，不把未接通功能标记为完成。

## 本轮实际修改

### 1. 编辑器 DocumentModel 接入 Sora
- `FileEditorScreen` 为每个打开文件建立 `DocumentModel` + `CommandStack`。
- Sora 文本变化进入统一 DocumentModel 状态源，并继续驱动 Workspace dirty 状态。
- 保存后调用 `DocumentModel.markSaved()`，dirty 状态不再只依赖 UI 局部布尔值。
- `撤销/重做` 通过 CommandStack 修改 DocumentModel，再由状态流同步回 Sora Editor。
- 从 DocumentModel 同步到 Sora 时使用保护标记，避免同步触发重复 dirty/command 记录。

### 2. Tool Window 从“核心原语”进入真实 Workspace
Workspace 注册并通过 ModalBottomSheet 打开以下真实内容：
- 项目：实际 ProjectPane / 文件树
- 终端：现有 TermuxTerminalScreen
- 构建：现有 BuildCenterScreen
- 问题：现有 ProblemsScreen
- 运行：现有 RunToolWindowScreen

这些 Tool Window 不再只是标题或占位文字；内容直接复用现有功能屏幕，并通过导航回调打开编辑器、问题、运行和 AI 修复流程。

### 3. AI Provider 单一 HTTP 实现
- `core-agent` 现在依赖 `core-ai-provider`。
- `OpenAiCompatibleCompletionClient` 改为适配 `core-ai-provider`。
- OpenAI-compatible HTTP 请求不再在 `core-agent` 与 `core-ai-provider` 各维护一份。
- 现有 `AiProviderSettingsStore` 仍保留 IDE 的持久化配置和自动修复开关。

### 4. ToolWindow Registry 生命周期
- 增加 `unregister()`。
- Workspace 使用 `DisposableEffect` 注册/注销工具窗口，避免离开 Workspace 后旧内容继续残留在全局 Registry。

## 验证

- 修改文件括号/方括号数量检查通过。
- Round 61 源码 ZIP 完整性检查通过。
- 当前沙箱无法完成 Gradle 编译验证：Gradle Wrapper 仍需要从 `mirrors.aliyun.com` 获取 Gradle 8.9，环境 DNS/网络不可用，`--offline` 也无法继续。
- 因此本轮不声称“已完整编译通过”；需要在有 Gradle 8.9 缓存或网络的 Android 构建环境再次执行 `:app:compileDebugKotlin` / `:app:assembleDebug`。
