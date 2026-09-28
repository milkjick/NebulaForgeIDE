# Round 26 — Language Service Registry + LSP 生命周期

本轮把 Round 21/24 的 LSP Client 与编辑器真正连接成项目级 Language Service 生命周期，而不是在每个文件页面里临时创建 LSP 客户端。

## 实现内容

1. `LanguageServiceRegistry`
   - 按 `projectRoot + languageId` 复用一个 Language Server 实例。
   - Java / Kotlin / XML / Dart / TypeScript / C/C++ 建立真实服务描述。
   - 只在真实可执行文件被探测到时启动；不存在时返回 `UNAVAILABLE`。
   - 语言服务状态：STOPPED / STARTING / RUNNING / UNAVAILABLE / FAILED。

2. Way-B 环境
   - Language Server 仍通过 `NativePty` → embedded shell → fork/exec 启动。
   - 不使用 Android `ProcessBuilder` 启动用户态 LSP ELF。
   - JDK、PATH、LD_LIBRARY_PATH 通过 `Environment` 注入。

3. 文档生命周期
   - Editor 打开文件：`start → initialize → initialized → didOpen`。
   - 编辑：递增版本并发送 `textDocument/didChange`。
   - 页面离开：发送 `textDocument/didClose`。
   - 项目关闭：停止该项目全部语言服务器。
   - 应用退出/重置：可以 `stopAll()`。

4. LSP 状态可见性
   - 编辑器显示当前 Language Service 状态。
   - Settings 增加 Language Service Registry 状态列表。
   - 不再把“文件存在”当作“语言服务器可用”。

5. 共享诊断
   - Language Server 仍写入全局 `DiagnosticStore`。
   - Editor / Problems / Build 使用同一个诊断源。

## 真实边界

本轮没有下载或伪造任何 JDT LS、Kotlin Language Server、LemMinX、Dart Analysis Server、TypeScript Server 或 clangd 二进制。
如果运行环境没有对应可执行文件，状态明确显示 `UNAVAILABLE`。

本环境没有可用 Gradle Wrapper JAR，因此不能宣称 Android APK 已在本轮完成 Gradle 编译；ZIP 完整性可独立验证。
