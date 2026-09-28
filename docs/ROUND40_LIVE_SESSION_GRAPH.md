# Round 40 — Live Session Graph

本轮把 Build/Run/Logcat 的关系从持久化 `RunCenterStore` 进一步提升为实时派生状态。

## 变更

- `SessionGraphStore` 只订阅 `IdeSessionBus`，不启动或停止任何进程。
- `SessionGraphSnapshot` 提供 Build/Run/Logcat 节点、关系、状态、产物、设备和输出尾部。
- Application 持有单例 `sessionGraph`。
- Run Tool Window 订阅实时 graph，在持久化 RunCenter 数据之外显示最近事件输出。
- 不改变 Way-B PTY、BuildSession、RunSession、LogcatSession 的执行职责。

## 状态源

`IdeSessionBus` 仍是唯一事件源；`RunCenterStore` 负责持久化展示状态，`SessionGraphStore` 负责实时派生图，两者均不拥有进程生命周期。
