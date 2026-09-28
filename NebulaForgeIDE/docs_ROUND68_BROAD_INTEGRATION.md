# Nebula Forge IDE Round 68 — Broad Integration

本轮不是单模块 UI 补丁，重点把项目创建、MCP、工具链状态源、Git 执行链和 Tool Window 自适应工作区继续接成真实链路。

## 已完成

1. Project Wizard
   - 模板按 Android / Flutter / Web / C++ 类型选择。
   - 项目名称校验。
   - Android 包名校验。
   - 创建后立即进入 Workspace。
   - 修正 XML Android 模板 MainActivity 使用生成的 `R.layout.activity_main`。

2. Internal MCP
   - Workspace 当前项目注册真实内部 MCP Server。
   - `run_build` 调用统一 `UnifiedRunController`。
   - 增加 `list_sessions`，读取统一 SessionStateProjection。
   - MCP 管理页面改为读取 Host 中注册的内部 Server，不再创建与宿主脱节的临时 Server。

3. Tool Window
   - Compact：继续使用 ModalBottomSheet。
   - 非 Compact：LEFT/RIGHT 工具窗显示为侧栏面板，BOTTOM 保持底部工具窗。
   - 仍不使用 NavigationDrawer / ModalNavigationDrawer。

4. Git
   - 版本控制工具窗不再通过 Android `ProcessBuilder("git", ...)` 直接执行。
   - 改用 embedded Termux PTY 的 `TermuxCommandExecutor`，与终端/构建环境保持同一 Way-B 执行路径。

5. Toolchain 状态源
   - Editor / Settings 改用 Application 级 `ToolchainManager`，避免每个页面各自创建工具链状态源。

## 验证边界

- ZIP 完整性会在打包后验证。
- 当前执行环境没有 Gradle 8.9 本地 distribution；Wrapper 仍尝试访问 `mirrors.aliyun.com`，DNS 不可用，因此不能宣称 Gradle 编译通过。
