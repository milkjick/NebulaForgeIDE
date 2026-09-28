# Round 18 — Persistent Session Workspace

## 目标

把 Round 17 的 Build/Run/Logcat 事件链从“进程内统一”进一步升级为“可恢复的工作区状态”。

## 实际实现

- `SessionRegistry`
  - 持久化 Session ID、类型、状态、项目路径、创建/更新时间。
  - IDE 重启后不会伪造恢复已经死亡的 PTY/Gradle/ADB 进程。
  - 原来处于 Preparing/Running 的 Session 会被标记为“IDE 重启，原进程已不存在”。
- `SessionEventJournal`
  - 持久化最近 1000 条 Build/Run/Logcat/Device/Diagnostic/Artifact 事件。
  - IDE 重新进入工作区时可以读取最近事件。
- `IdeSessionBus`
  - replay 32
  - 自动写 Event Journal
  - 自动同步 SessionRegistry。
- `RunSessionManager`
  - Run/ADB 状态持久化。
  - APK、设备序列号、Logcat Session ID、结束时间均记录。
  - 重启恢复为历史 FAILED，而不是假装进程仍在运行。
- `RealAndroidBuildRunController`
  - 默认使用持久化 Registry + Journal。
  - Build → Run → Logcat 继续共享同一个事件总线。

## Way-B 保持

ADB、Build、Logcat 仍然通过 embedded PTY/Termux userland 执行；没有恢复 Android `ProcessBuilder` 作为主执行路径。

## 未声称

当前沙箱没有完整 Android SDK、Gradle Wrapper JAR 和真实 Android 设备，因此没有声称真实设备端到端构建/安装/启动已经验证。
