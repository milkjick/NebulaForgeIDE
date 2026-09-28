# Round 52 — Editor Diff Gutter / Inline Change Markers

本轮继续基于 Round 51，实现 BuildFix Diff 与真实 Sora Editor 的可视联动。

## 已实现

- `DiffGutterOverlay`：在真实 Sora `CodeEditor` 上方提供独立、轻量的 Diff gutter marker 层。
- marker 数据直接来自 `BuildFixCoordinator.reviewHunksForAbsolutePath()`，没有第二套 Diff 数据源。
- ACCEPTED / REJECTED / PENDING Hunk 使用不同状态标记。
- marker 根据编辑器 `scrollY` 同步纵向位置。
- 点击 marker 会选择对应 Hunk 并把 Sora Editor 光标定位到 `newStart`。
- 保留 Round 51 的 Edit-and-Accept / Accept / Reject / Previous / Next 流程。
- marker 层只负责审查交互，不直接写磁盘；真实提交仍通过 `GenerationTransaction`。

## 边界

本轮没有依赖 Sora 0.23.6 的未公开内部 Painter/Gutter API。这样可以避免因为内部 API 变化导致工程无法编译。marker 是覆盖在真实 Sora Editor 上的独立 Android View，并观察 Editor 的滚动状态。

由于当前工程缺少 `gradle/wrapper/gradle-wrapper.jar`，没有声称完整 APK Gradle 编译或真机运行验证通过。
