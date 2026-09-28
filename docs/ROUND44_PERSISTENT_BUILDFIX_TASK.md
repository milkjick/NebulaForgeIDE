# Round 44 — Persistent BuildFix Task / Recovery

本轮把 BuildFixAgent 从一次性内存流程提升为可恢复任务。

## 状态持久化

`BuildFixTaskStore` 保存：

- phase
- projectPath
- errorCount
- message
- proposal/sessionId
- 每个文件的 originalSha256 / proposedContent / review status

状态文件：`files/sessions/build-fix-task.json`。

## 重启恢复

- `REVIEW`：恢复 Proposal 和 `.nebulaforge/tmp/<sessionId>` 暂存目录，可继续审查。
- `ANALYZING`：标记为中断，不假装 AI 请求仍在运行。
- `APPLYING/REBUILDING`：检查事务 manifest；若提交过程中进程死亡，使用 backup 自动回滚，再标记任务失败。

## 提交事务

`GenerationTransaction` 在实际覆盖项目文件前生成 backup + commit manifest。全部文件写入成功后删除事务目录；中途异常会立即恢复。

## 安全边界

AI 仍然不能执行 shell/Gradle/ADB 命令；只有用户审查接受的文件内容可以进入 commit。
