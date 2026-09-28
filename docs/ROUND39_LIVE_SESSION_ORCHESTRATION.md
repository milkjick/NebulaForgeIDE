# Round 39 — Live Session Orchestration

本轮继续沿用 NebulaForge IDE v2 的统一 Session/Event 架构，将 Build、Run、Logcat 的关系进一步做成可持久化、可实时驱动 UI 的单一事件源。

## 本轮变化

- `IdeEvent.SessionRegistered`：Session 创建时发布类型与项目路径，RunCenter 不再把所有未知事件默认解释为 RUN。
- `SessionEventJournal` 持久化 Session 注册事件。
- `RunCenterRecord` 增加 `runSessionId`，并在 Build↔Run、Run↔Logcat 关系事件到达时维护关联。
- RunSession 建立 Build 关系时，如果 Build 已产生 APK Artifact，RunCenter 会同步该 APK 路径。
- Android `BuildRunController` 同时发布 Build→Run 与 Run→Build 双向关系事件。
- Run Tool Window 显示实时 Session 关联信息。
- 版本：1.23.0-round39-live-session-orchestration / versionCode 32。

## 验证边界

当前工程没有 `gradle/wrapper/gradle-wrapper.jar`，因此本轮不宣称 APK 编译或真机端到端验证成功。ZIP 完整性与源码结构检查单独执行。
