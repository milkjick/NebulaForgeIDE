# Round 12 — Toolchain Runtime Manager

本轮把“工具链文件存在”升级为“真实执行探测 + 安装/修复 + 统一环境会话”。

## 1. 新增 core-toolchain

`core/core-toolchain` 提供 `ToolchainManager`，统一管理：

- Embedded Termux runtime
- JDK 17
- Git
- Python
- Node.js
- Gradle
- Android SDK CLI
- Platform Tools / ADB
- Android Build Tools
- Dart
- Flutter

每项状态为 `MISSING / INSTALLING / READY / FAILED / BLOCKED`。`READY` 必须执行真实二进制并得到成功退出码；不能因为文件存在就显示 OK。

## 2. 安装与修复

- Termux 用户态：调用已有 `BootstrapRuntime.ensureReady()`，继续使用事务式 bootstrap 安装与自检。
- Termux 包：JDK/Git/Python/Node/Gradle/Dart/Flutter 使用内嵌环境的 `pkg update && pkg install -y ...`。
- Android SDK：只有在 `sdkmanager` 本身能够在设备上执行时才调用 `sdkmanager --install`；如果命令行工具不可执行，状态明确为 `BLOCKED`，不伪造成功。
- ADB：实际运行 `adb version`。
- Build Tools：实际运行 `aapt2 version`。

Android 官方文档说明 SDK Command-Line Tools 提供 SDK 管理命令；因此本轮把 `sdkmanager` 本身的可执行性作为前置条件，而不是仅检查目录。citeturn0search1

## 3. Unified Environment Session

`EnvironmentSession` 暴露三套由同一来源生成的环境：

- `terminal`
- `gradle`
- `sdk`

Build/Run 与 Terminal 开始消费这套统一环境，避免出现“终端能找到 Java、Gradle 子进程却没有 JAVA_HOME”的状态分裂。

## 4. Termux Bootstrap 边界

本项目仍坚持方式 B：不依赖外部 Termux App。Termux 官方维护文档明确指出 bootstrap 是运行 Termux 所需的最小用户态，并且 fork 后修改 package/data 路径时需要按新的身份重新构建包，不能混用原 `com.termux` 包。citeturn0search3turn0search12

因此 Round 12 没有把普通 `com.termux` APT 二进制包当作可以无条件复用的 Android IDE 运行时；只有在当前 bootstrap 的 package identity 与仓库/包来源兼容时才允许通过 `pkg` 安装。

## 5. 下一阶段

下一阶段重点应为：

1. Android SDK/Build Tools 的设备可执行安装器与 ABI 适配；
2. 项目级 JDK/Gradle/SDK 选择持久化；
3. Gradle Wrapper/installed Gradle resolver；
4. Toolchain Task Center 的持久任务、取消、失败回滚；
5. Build/Run/Terminal/LSP/Logcat 全部消费同一个 EnvironmentSession；
6. 实机端到端：修复工具链 → Sync → assembleDebug → adb install → launch → logcat → 错误回写编辑器。

Termux 官方当前文档也指出 bootstrap 可由本地包源构建，并且 package/data 路径变化必须与构建配置一致；Round 12 因此没有把“下载一个普通 bootstrap ZIP”视作完整工具链安装完成。citeturn0search0turn0search3
