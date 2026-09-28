# Round 64 — Unified Session Events

本轮继续落实“状态源唯一化 + 增量事件驱动”。

## 变更

- BuildSessionManager 接入共享 IdeSessionBus，Build 生命周期、进度、输出、取消、失败全部进入统一事件源。
- RealAndroidBuildRunController 不再重复发布 Build Running/Succeeded，避免同一生命周期出现两个状态写入点。
- 新增 SessionStateProjection：把 SessionRegistered/State/Output 投影为 StateFlow，UI 可以直接订阅而无需轮询 Build/Run/Logcat Manager。
- Application 暴露 sessionStateProjection。
- 保留 SessionEventJournal/SessionGraphStore 作为重启恢复和工作流关系投影。

## 边界

SessionStateProjection 不负责恢复真实 Gradle/ADB/PTY 进程；进程重启后的存活性仍必须由各执行管理器重新探测。
