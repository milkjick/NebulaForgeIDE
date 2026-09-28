# Round 11 — Real Terminal Runtime

本轮基于 Round 10 继续开发，目标是让内嵌终端成为可持续使用的 IDE Terminal，而不是一次性的 TerminalView 示例。

## 已实现

- 新增 `core:core-terminal`，集中管理 Termux `TerminalSession`。
- PTY 会话使用 Termux `terminal-emulator`/`terminal-view`，VT/ANSI/scrollback/selection 不由 Compose 自己模拟。
- 多 Session：新建、切换、关闭。
- Session 元数据持久化；Android 进程死亡后不会错误地复用旧 PID/PTY，旧记录会自动清理。
- Ctrl / Alt / Shift / Fn 虚拟修饰键。
- Ctrl-C、Ctrl-Z、Ctrl-D、ESC、TAB、方向键。
- Fn + WASD / PageUp / PageDown / F1-F4/F10 等移动端终端快捷输入。
- Copy / Paste。
- Terminal 字体缩放。
- 点击终端自动请求软键盘。
- Terminal runtime 和 BootstrapRuntime 解耦：只有 bootstrap 真正通过 `sh -c` smoke test 后才启动会话。
- Termux bootstrap 自定义构建脚本改为按官方维护者文档的 `build-bootstraps.sh` 路线，并针对 NebulaForge 包名做可控 patch。

## 官方依据

Termux 官方维护文档说明 bootstrap 是启动 Termux 所需的最小用户态，并支持 `build-bootstraps.sh` 从本地 package sources 为 fork 构建 bootstrap；如果修改 `TERMUX_APP_PACKAGE`，需要清理/强制重新构建。官方文档还说明官方仓库中的 bootstrap 是针对 `com.termux` 发布的，不能直接把它当作自有包名的 custom repository bootstrap。 

Termux 官方 `TerminalView` 是与 `TerminalSession` 交互的 View，输入处理包括 Ctrl/Alt/Shift/Fn、键码转换、code point 输入以及终端 emulator 的滚动/选择等。

## 尚未声称完成

- 本源码包没有声称已在真实 Android 设备上完成 bootstrap 下载、`apt/pkg`、PTY、Git、Python、JDK、Gradle 全链路测试。
- 当前没有把几十 MB 的 bootstrap 二进制偷偷塞入源码 ZIP；运行时下载和 SHA-256 校验仍是正式路径。
- Sora/Tree-sitter/LSP 的真实 API 兼容性仍需在实际 Gradle/Android SDK 环境中编译验证。
- 完整 Termux extra-keys 配置文件解析、鼠标协议配置、终端主题/字体持久化、后台前台服务以及 session 生命周期与 Android Service 的完全绑定属于后续阶段。
