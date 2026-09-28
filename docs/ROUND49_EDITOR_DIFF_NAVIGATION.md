# Round 49 — BuildFix Editor Diff Navigation

## Goal

将 Round 48 的 Diff/Hunk 审查继续接入真正的 Sora Editor 工作流：用户可以从具体 Hunk 直接跳到对应的代码行，并查看标准 Unified Diff，而不是停留在独立的审查卡片。

## Changes

1. `DiffReviewEngine`
   - 增加 `DiffLine`，提供 old/new 行号映射。
   - 增加 `unifiedDiff()`，输出 `---/+++` 与 `@@ -old +new @@` 标记以及 ` ` / `-` / `+` 行。
   - 保留 20,000 行上限，避免手机端 LCS 造成不可控内存开销。
2. `BuildFixReviewScreen`
   - 文件级审查继续保留。
   - 每个 Hunk 增加“跳到代码”。
   - 跳转目标使用 Hunk 的 `newStart`，传入 Editor 的 0-based 行号。
   - 增加 Unified Diff 对话框。
3. Navigation
   - AI BuildFix 路由现在传递 `path + line + column`。
   - 与 Problems → Editor 的定位协议保持一致。
4. `FileEditorScreen`
   - 接收已有的行/列参数。
   - AI Diff 定位时显示当前审查行提示。
   - 继续使用 Sora Editor，不创建假的 Compose 文本编辑器。
5. Persistence
   - Hunk Accept/Reject 仍通过 `BuildFixCoordinator.publish()` 写入 `BuildFixTaskStore`。
   - IDE 被杀/重新打开后，待审查 Hunk 状态可以恢复。

## Review safety

本轮没有改变“人工审查后才能写回”的安全边界：

`AI Proposal -> Hunk Review -> Effective Proposal -> GenerationTransaction -> Rebuild`

点击“跳到代码”或查看 Unified Diff 不会修改真实项目文件。

## Verification

- `DiffReviewEngine.kt` 使用本机 `kotlinc` 单独编译通过。
- 对多 Hunk Accept/Reject 与 Unified Diff 输出进行了 JVM smoke test。
- 关键修改文件进行了括号/花括号结构检查。
- 工程级 Gradle APK 编译仍未执行：项目当前没有 `gradle/wrapper/gradle-wrapper.jar`，因此不声称 APK/真机验证通过。
