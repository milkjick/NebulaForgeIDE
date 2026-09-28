# Round 17 — ProjectEnvironment → BuildSession → RunSession → ADB/Logcat 统一事件链

## 本轮目标

按照开发方案 v2 的统一环境与会话架构要求，把 Build、Run、ADB、Logcat 从互相独立的进程/轮询逻辑收敛到同一个 ProjectEnvironment + IdeSessionBus 事件源。

## 实际实现

- `BuildSessionManager` 增加 `ProjectEnvironment` 入口，构建会话持久化 JDK/SDK/环境问题摘要。
- `RealAndroidBuildRunController` 不再重新拼接 ADB 环境变量，Build/Run 均消费同一个 `ProjectEnvironment`。
- 新增 `RunSessionManager`：负责设备发现、APK 安装、应用启动、Run 状态持久化。
- ADB 命令统一经过 `TermuxCommandExecutor` 的 embedded PTY Way-B 路径，不再使用 `ProcessBuilder`。
- Logcat 使用同一 PTY 执行器持续输出，并通过 `IdeSessionBus` 发布事件。
- `buildAndRun()` 通过统一事件总线转发 Build/Run/Logcat 事件，避免 UI 依赖多套轮询状态。
- 支持 Build/Run/Logcat 独立取消入口。

## 仍未虚构完成的部分

- 本沙箱没有完整 Android SDK/真实设备，因此没有宣称真实 `assembleDebug → adb install → am start → logcat` 已在实机跑通。
- `TermuxCommandExecutor` 本轮仍是现有 native PTY 实现；真实设备上的 bootstrap/SELinux/noexec 条件仍需在用户环境验证。
- RunSession 的持久化目前是内存状态；BuildSession 已持久化。下一轮可将 Run/Logcat 任务持久化并恢复 UI 摘要。
- ADB shell/streaming 的更细粒度 stderr、退出原因和设备状态事件仍可继续细化。

## 架构结果

`ProjectResolver → ProjectToolchainResolver → ProjectEnvironment`

`ProjectEnvironment → BuildSessionManager → BuildEvent → IdeSessionBus`

`APK → RunSessionManager → ADB → IdeSessionBus`

`RunSessionManager → Logcat PTY → IdeSessionBus`

UI 只需要订阅 `IdeSessionBus.events` 即可获得连续工作区事件。
