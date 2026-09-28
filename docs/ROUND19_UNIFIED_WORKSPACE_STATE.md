# Round 19 — Unified Workspace State

本轮继续沿 NebulaForge IDE v2 的 Android MVP 连续工作区方向推进。

## 实际实现

- 新增 `WorkspaceStateStore`，持久化 IDE 工作区元数据，而不是源代码内容。
- 保存当前项目、打开文件、dirty 状态、光标位置、最后 Build/Run Session、最后 APK Artifact、Problems/Diagnostics、当前工具窗。
- Editor dirty/save 与持久化 Workspace State 连接。
- Project Explorer 的项目/文件打开动作写入 Workspace State。
- Build/Run/Artifact/Diagnostic 事件开始自动反映到 Workspace State。
- Workspace 状态采用临时文件 + 原子 rename 提交，避免直接覆盖造成半写文件。
- 仍不恢复已经死亡的 PTY/Gradle/ADB 进程；SessionRegistry 的重启语义保持不变。

## 下一步

- 用 Workspace State 恢复实际 Editor tab 内容/光标/滚动位置。
- Build Problems 与文件定位联动。
- Terminal Session 与 Editor dirty 状态联动。
- Template 创建后进行真实 Toolchain/Wrapper/assembleDebug 验证。
- 在具备完整 Android SDK/JDK/Gradle 与设备的环境中进行端到端验证。
