# Round 53 — Inline Hunk Controller / Real Editor Overlay Attachment

本轮继续 Round 52 的真实 Sora Editor Diff Gutter，但修复了 Round 52 的核心生命周期问题：DiffGutterOverlay 不再可能绑定到占位 View。

## 实现

- CodeEditor 与 DiffGutterOverlay 由同一个 AndroidView 工厂创建并放入 FrameLayout。
- Overlay 永远绑定真实 Sora CodeEditor；更新阶段也会重新校正 editor 引用。
- Overlay 支持 `setEditor()`，在编辑器实例变化时解除旧 ViewTreeObserver 回调并重新绑定。
- 当前 Hunk 与 gutter marker 双向同步：点击 marker 会定位真实 Sora 光标；Compose 当前 Hunk 改变会更新 marker 高亮。
- Hunk 状态仍来自 BuildFixCoordinator 的持久状态，不直接写项目文件。
- 保留“接受 / 拒绝 / 保留编辑 / 编辑并接受”的事务式审查流程。
- 无假编辑器、无直接磁盘写入、无绕过 GenerationTransaction 的提交。

## 验证边界

- ZIP 完整性和 Kotlin 文件结构检查执行。
- 当前沙箱没有可用的 Gradle wrapper JAR，因此本轮不能声称完整 Gradle 编译通过。
- 未进行真实 Android 设备运行验证。
