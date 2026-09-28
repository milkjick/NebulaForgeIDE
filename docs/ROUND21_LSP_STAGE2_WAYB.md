# Round 21 — Stage 2 LSP / Way-B

本轮开始进入开发方案 Stage 2 的 LSP 能力，但只实现真实可验证的基础链路，不伪造语言服务器。

## 已实现

- LspClient 从 Android ProcessBuilder 迁移到 embedded NativePty。
- 启动阶段使用 `stty -echo`、`cd project`、`exec <language-server>`，语言服务器实际运行在嵌入式用户态执行链中。
- JSON-RPC Content-Length framing。
- initialize / initialized。
- textDocument/didOpen。
- textDocument/didChange。
- textDocument/publishDiagnostics。
- textDocument/completion。
- textDocument/definition。
- 请求超时和 pending request 清理。
- GenericLspLanguageService 适配开发方案的 LanguageService 接口。
- LSP Diagnostics 进入 IdeSessionBus，因此可继续镜像到 WorkspaceStateStore 的 Problems 状态源。

## 明确未声称

本轮没有内置 Kotlin/Dart/TypeScript/C++ 语言服务器二进制，也没有伪造补全结果。
实际补全/定义/诊断只有在对应语言服务器安装并能在 embedded runtime 中启动后才会产生。

## 下一步

- LanguageServiceRegistry：按 ProjectType/扩展名选择语言服务。
- Editor 生命周期绑定：open/change/close 与 LSP document lifecycle 对齐。
- Problems 导航：诊断点击后定位文件/行/列。
- LSP session 生命周期持久化。
- BuildFixAgent：从真实 BuildError + LSP diagnostics 构造上下文并进入事务式修复。
