# Round 63 — Session Recovery / Event Journal

本轮继续沿着“状态源唯一化 + 增量事件驱动”推进：

- `SessionGraphStore` 支持从 `SessionEventJournal` 重放最近事件，应用重启后可以恢复 Build/Run/Logcat 的工作流拓扑与输出尾部。
- `FileChanged` 事件进入 SessionGraph，并明确归类为 `EDITOR`，不再错误地按 `RUN` 会话处理。
- `SessionEventJournal` 从“每次事件读取整个文件再写回”改为追加写入；达到大小阈值后再压缩到最近事件，降低 Logcat/Terminal 高频事件的 I/O 开销。
- 恢复仍然是 UI/状态恢复：不会尝试恢复已经死亡的 Gradle、ADB、PTY 进程。
- Application 使用同一个 `SessionEventJournal` 实例初始化 Bus 和 Graph，避免两套事件源。

验证：完成源码结构检查、Kotlin 分隔符检查与 ZIP 完整性检查。当前沙箱仍无法下载 Gradle 8.9，因此不宣称完整 Android Gradle 编译成功。
