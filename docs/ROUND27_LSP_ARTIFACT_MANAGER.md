# Round 27 — Language Server Manager + verified artifacts

本轮继续沿《NebulaForge IDE 开发方案 v2》的 LanguageService / Environment / Toolchain 设计，把 Round 26 的“检测并启动”扩展为真实的语言服务器安装生命周期。

## 1. 真实 release 元数据

`LspArtifactManager` 不硬编码语言服务器版本，而是读取 GitHub Releases API 的 latest release metadata，并根据设备 ABI / Linux archive 名称选择候选 asset。

当前内置描述：

- Eclipse JDT Language Server：`eclipse-jdtls/eclipse.jdt.ls`
- Kotlin Language Server：`fwcd/kotlin-language-server`（社区实现，兼容性不稳定）
- Eclipse LemMinX：`redhat-developer/vscode-xml`（只有找到 LemMinX 二进制/JAR 才允许激活）

C/C++ 的 clangd 仍优先走嵌入式 Termux/工具链包，不把 glibc Linux release 错装进 Android Bionic 用户态。

## 2. 下载安全

下载到 `.cache/*.part`，支持 HTTP Range 断点续传。

安装前必须：

1. release asset 存在；
2. GitHub API 提供 `sha256:` digest；
3. 本地 SHA-256 与 digest 完全一致；
4. 安全解压，拒绝 `../` archive path traversal；
5. 解压到 `.staging-*`；
6. 找到实际 launcher；
7. 激活前写入 `.nebulaforge-lsp.json`；
8. 激活后再次检查 launcher。

如果上游没有可验证 digest，任务直接失败，不会把“下载成功”当成“安装成功”。

## 3. 原子激活/回滚边界

旧版本不会在下载阶段被覆盖。新版本只在 archive、SHA-256、解压和 launcher 检查通过后替换目标目录。

失败时 staging 会被删除，旧目录保留。

## 4. JDT LS 的 JDK 要求

当前上游 JDT LS 文档要求 Java 21+，因此 Android 构建用的 JDK 17 与 JDT LS 运行时不再混用。

新增 `JDK21_LSP` 工具链组件，并在 LanguageServiceRegistry 启动 Java 服务时注入 JDK 21 环境；如果没有 JDK 21，Java LSP 明确显示 `UNAVAILABLE`。

## 5. Way-B 执行路线

本轮顺便修复 ToolchainManager 中的命令探测路径：不再使用 Android `ProcessBuilder` 执行嵌入用户态 ELF，而是通过 `TermuxCommandExecutor -> NativePty`。

因此 Toolchain probe 与 Terminal / Build / LSP 保持同一执行通道。

## 6. UI

Settings 增加 Language Server Manager：

- 检查更新
- 最新 release
- 已安装版本
- asset 名称
- SHA-256 验证状态
- 安装任务进入持久化 Task Center
- 安装失败原因可见

## 7. 真实边界

本轮没有把任何语言服务器二进制打进 APK，也没有声称当前环境已经下载成功。

由于构建环境缺少 `gradle-wrapper.jar`，仍不能声称 APK 已经完成 Gradle 编译或真机运行。
