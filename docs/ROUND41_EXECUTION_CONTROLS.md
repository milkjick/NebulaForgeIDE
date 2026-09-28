# Round 41 — Execution Timeline & Control Surface

本轮在 Round 40 Live Session Graph 上继续推进，不新增第二套执行状态源。

## 完成内容

- 新增 `SessionExecutionTimeline` / `SessionExecutionTimelineBuilder`。
- Timeline 从 `SessionGraphSnapshot` 派生，不直接控制进程。
- Run Tool Window 展示 Build → Run → Logcat 的实时执行链路。
- 当前 Run 会话提供停止与重新运行控制。
- 控制统一进入 `UnifiedRunController`，没有在 UI 中直接启动 adb/gradle/terminal 进程。
- 修正 SessionGraphStore 的节点/关系窗口，保留最近更新的 64 个节点与 128 个关系。

## 状态责任

```text
Build/Run/Logcat execution
        ↓
   IdeSessionBus
        ↓
 SessionGraphStore
        ↓
ExecutionTimeline
        ↓
 Run Tool Window
```

控制方向反过来：

```text
Run Tool Window
      ↓
UnifiedRunController
      ↓
真实 Build/Run/Logcat Session
      ↓
IdeSessionBus
```

因此 UI 既不会成为第二状态源，也不会直接操作底层进程。

## 验证边界

本轮完成源码结构与 ZIP 完整性检查；当前沙箱仍缺少 `gradle/wrapper/gradle-wrapper.jar`，因此没有声称 Gradle APK 编译通过，也没有声称真机安装/运行通过。
