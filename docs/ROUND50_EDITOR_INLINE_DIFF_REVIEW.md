# Round 50 — Editor Inline Diff Review

本轮在 Round 49 的 BuildFix Diff Navigation 基础上继续，把 Diff 审查状态接入真实 Sora Editor 工作区。

## 目标

- BuildFix Hunk 点击“跳到代码”后进入真实 FileEditorScreen。
- Editor 接收 reviewHunk 路由参数，并定位到该 Hunk 的新代码起始行。
- Editor 顶部显示当前 Hunk 的 old/new 范围、变更摘要以及上一个/下一个 Hunk。
- Editor 内可直接接受/拒绝当前 Hunk；操作仍然只修改审查状态，不直接写入项目文件。
- Hunk 接受时自动使对应文件进入 ACCEPTED；全部 Hunk 拒绝时文件进入 REJECTED。
- BuildFix Review 页面继续作为最终“应用并重新构建”的提交闸门。

## 安全边界

Editor 内 Accept/Reject 不写真实项目文件。
真实写入仍然只能通过 GenerationTransaction，并经过 SHA-256 乐观并发检查。

## 当前实现边界

本轮没有声称 Sora Editor 内部原生自定义 gutter painter 已接入；使用的是 Sora 真编辑器 + review overlay/定位栏，以避免依赖未验证的编辑器内部绘制 API。

## 验证

- 修改后的 Kotlin 源文件进行结构检查。
- DiffReviewEngine 保持独立可测试的纯 Kotlin 逻辑。
- 完整 Android Gradle 编译仍受项目缺失 `gradle/wrapper/gradle-wrapper.jar` 限制，因此没有伪造 APK 编译或真机结果。
