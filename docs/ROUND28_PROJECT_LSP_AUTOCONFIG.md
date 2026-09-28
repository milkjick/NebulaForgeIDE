# Round 28 — Project Language Profile + Runtime LSP self-test boundary

本轮沿用开发方案中的 LanguageService/Environment/Way-B 分层。重点是项目级语言发现与实际运行状态分离：

- `ProjectLanguageProfile` 扫描项目文件，排除 `.git/.gradle/build/node_modules/.dart_tool/.idea`，输出项目实际使用的语言以及示例文件。
- `LanguageServiceRegistry.isCommandAvailable()` 只通过真实可执行文件探测，不把 artifact 元数据当作运行状态。
- 打开文件仍由 `LanguageServiceRegistry.openFile()` 启动真实 LSP；`LspClient.initialize` 成功才进入 RUNNING，因此这是本轮的实际 runtime self-test。
- Editor 对 UNAVAILABLE 状态提供真实安装入口，调用 `ToolchainManager.enqueueLspInstallForLanguage()`；不会自动偷偷修改用户环境。
- Java/Kotlin/XML 映射到 Round 27 的 LSP artifact；Dart/TypeScript/C++ 保留给各自 SDK/toolchain。

验证边界：源码结构与 ZIP 完整性可验证；当前构建环境缺少 Gradle Wrapper JAR，因此不宣称 APK 编译/真机验证。
