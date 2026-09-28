# Round 62：状态源唯一化与增量事件驱动

## 本轮实际修改

- 新增 `IdeEvent.FileChanged`，统一表示 Terminal/Gradle/Git 等外部进程导致的文件变化。
- 新增 `FileChangeMonitor`，500ms 轮询文件 mtime/size，并通过 `IdeSessionBus` 增量发布变化。
- `FileEditorScreen` 接入统一文件变化事件：未保存时自动重新载入，存在 dirty 内容时只提示，不覆盖编辑内容。
- `DocumentModel.markReloaded()` 把外部内容设为新的磁盘基线。
- `ToolWindowRegistry` 增加 StateFlow，解决运行时注册后宿主不重组的问题。
- Tool Window 当前选择写入 `WorkspaceStateStore.selectedToolWindow`，重新进入工作区可以恢复。
- 修复 WorkspaceScreen 中项目标题的 Kotlin 调用语法。

## 验证边界

本轮进行了源码结构/括号检查与 ZIP 完整性检查；没有把“Gradle 8.9 无法从当前构建环境下载”包装成编译成功。
