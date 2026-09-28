# Round 42 — Problems → Editor Source Navigation

## 目标

把 Build/LSP 产生的结构化诊断继续收敛到同一个 `DiagnosticStore`，并提供真实的 Problems → Editor 定位链路。

## 实现

- `ProblemNavigationStore`：只保存一次性的文件/行/列定位请求，不执行导航、不修改源码。
- `ProblemsScreen`：直接读取 `DiagnosticStore`，点击诊断后产生定位请求并进入编辑器。
- `FileEditorScreen`：接受 `initialLine` / `initialColumn`，使用 Sora Editor 的 selection API 定位到诊断位置。
- `BuildCenterScreen`：补齐真实构建入口，调用 `UnifiedRunController.build()`，显示 DiagnosticStore 中的最近构建问题。
- 导航层新增 `problems` 路由，并支持 `editor?path=&line=&column=`。
- 修复 Round 41 `ConfigurationEditor` 中重复的 `mode` 参数。

## 数据流

`Gradle/LSP -> IdeSessionBus -> DiagnosticStore -> ProblemsScreen -> ProblemNavigationStore -> Editor`

Problems 层不再解析 Gradle 输出，也不直接启动构建命令。

## 验证边界

本轮只做源码结构、ZIP 完整性和静态检查。工程当前仍缺少 `gradle/wrapper/gradle-wrapper.jar`，因此没有宣称 Gradle 编译或真机运行成功。
