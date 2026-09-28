# Round 38 — Unified Build / Run / Logcat Session State

本轮继续沿 NebulaForge IDE v2 的统一 Session/Event 架构推进。

## 目标

Build、Run、Logcat 不再只是共享 UI，而是通过 `IdeSessionBus` 的事件和 Relation 明确关联。

链路：

`BuildSession -> APK Artifact -> RunSession -> LogcatSession`

## 实现

- `IdeEvent.Relation`：记录 build/run/logcat 的父子关系。
- `RunCenterRecord` 持久化 `buildSessionId` 与 `logcatSessionId`。
- Android BuildRunController 将 Gradle Progress/LogLine 转发到统一事件总线。
- APK Artifact 继续通过 `IdeEvent.Artifact` 发布。
- 创建 RunSession 后发布 `run -> build` Relation。
- 创建 LogcatSession 后发布 `run -> logcat` Relation。
- RunCenterStore 从事件总线恢复这些关联，并持久化。
- 没有新增伪造设备或进程状态。

## 验证边界

本轮仅进行源码结构与 ZIP 完整性验证；当前工程仍缺少 `gradle/wrapper/gradle-wrapper.jar`，因此没有声称 APK 编译或真机运行验证。
