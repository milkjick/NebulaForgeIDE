# Round 23 — AI Provider + BuildFix 审查闭环

本轮沿《NebulaForge IDE 完整开发方案 v2》的第七章继续推进：把 Round 22 的 `BuildFixAgent` 从“只有核心接口”推进到可由 UI 配置并真实调用 OpenAI-compatible HTTP Provider，再把构建 Problems 接到逐文件审查和事务提交。

## 已落地

- `AiProviderSettings` + `AiProviderSettingsStore`
  - Base URL
  - Model
  - API Key
  - Provider enable
  - build failure auto-fix 开关（默认关闭）
- `OpenAiCompatibleCompletionClient`
  - Android `HttpURLConnection`
  - `/chat/completions`
  - Authorization Bearer
  - HTTP 错误正文回传
  - JSON content/数组 content 解析
- Android INTERNET 权限
- Build Center 的 `Problems -> AI 修复`
  - 使用真实构建诊断作为上下文
  - 调用 `BuildFixAgent`
  - 不直接把模型输出写入项目
- AI 审查 Bottom Sheet
  - 文件列表
  - 单文件选择
  - 接受/拒绝
  - 编辑后接受
  - 最终只把选中的文件标记为 ACCEPTED
- `GenerationTransaction` 继续执行 SHA-256 乐观并发检查；文件在审查期间发生变化时拒绝提交。
- AI 导航页不再是纯占位文案，而是 Provider 状态说明和真实工作流入口。
- Parser 支持常见 ```json 代码围栏，同时仍要求最终内容是 JSON。

## 安全边界

- 模型不能直接执行 shell、chmod、删除文件或写绝对路径。
- proposal 只允许项目根目录内的相对文件路径。
- 未经用户逐文件接受，不会提交变更。
- 提交前检查原文件 SHA-256，防止覆盖用户在 AI 审查期间的新修改。
- 本轮没有声称 API Key 加密存储；当前为应用私有 SharedPreferences，后续安全存储可单独升级。

## 尚未宣称完成

- 未实现多 Provider 原生 SDK（Anthropic/Gemini 等）。
- 未实现 Build 失败后的自动重新构建循环。
- `autoFixOnBuildFailure` 目前是持久化设置位，默认关闭；自动执行策略应在下一阶段接入 BuildSession 状态机和人工确认策略。
- 当前 sandbox 没有完整 Android SDK/Gradle Wrapper JAR/实体设备，因此没有宣称 APK 编译或真机端到端验证。
