# Round 20 — Workspace Recovery + Way-B Execution Consistency

## 目标

在 Round 19 的统一 WorkspaceState 基础上，把“持久化状态”真正恢复到工作区 UI，而不是只保存 JSON：

- 重启后恢复已打开文件标签页；
- 恢复 activeFile；
- 恢复 dirty 元数据；
- 明确区分“磁盘恢复”和“未保存缓冲区恢复”，不伪造恢复结果；
- 标签页支持关闭，并同步 WorkspaceStateStore；
- 保存后清除 dirty / recoveredDirty；
- 构建 Wrapper 生成不再通过 Android ProcessBuilder，统一进入嵌入式 Termux PTY（Way B）。

## 数据安全边界

WorkspaceStateStore 仍然不持久化源代码内容。若进程在未保存状态下被系统杀死，Round 20 会从磁盘重新读取文件并显示“上次有未保存修改”的恢复提示，而不会声称恢复了内存中的未保存文本。

## Way-B 一致性

`GradleWrapperProvisioner` 改用 `TermuxCommandExecutor`。Gradle `:wrapper` 命令现在通过 embedded PTY fork/exec，避免重新引入 Android ProcessBuilder 作为构建路径。

## 验收边界

- 已执行源码结构/脚本检查与 ZIP 完整性检查；
- 当前沙箱仍没有完整 Gradle Wrapper JAR、Android SDK 和真实 Android 设备，因此未宣称 APK 编译或真机端到端通过；
- Sora 编辑器的实际编译兼容性仍需用户本机 Android 构建环境验证。
