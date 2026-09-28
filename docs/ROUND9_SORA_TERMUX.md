# Round 9 — Sora + Tree-sitter + Real Termux Terminal

本轮把编辑器从 Compose TextField 切换到 Sora Editor，并加入 Tree-sitter/LSP 所需的依赖基础；终端前端改用 Termux `terminal-view` + `terminal-emulator`，PTY/VT/xterm 不再由简单 Text 输出模拟。

## Termux bootstrap

当前官方下载版本锁定为 `2026.09.20-r1+apt.android-7`。官方 Termux 文档说明 bootstrap 是最小用户态 rootfs，并支持 `aarch64/arm/i686/x86_64`；Termux 应用在构建时会下载并校验 bootstrap。

本源码不把 32MB+ 二进制直接塞进源码 ZIP，而是在首次启动时按 ABI 下载、续传、解压、权限设置并验证。原因是当前代码生成环境无法访问 GitHub 二进制下载端点，不能诚实地声称已经把二进制嵌入 ZIP。

## 终端

`TerminalView` 使用 Termux 的 VT/ANSI/xterm 终端模拟器；shell 由内嵌 bootstrap 提供，环境变量由 `Environment.buildTerminalEnv()` 统一生成。

## 编辑器

Sora Editor 0.23.6 + Tree-sitter 4.3.1 依赖已接入。Java 使用 Sora 原生语言；Kotlin/XML grammar 已进入依赖图，为下一步 TreeSitterLanguage 工厂和 LSP 生命周期接线准备。

## 限制

官方 Termux 项目明确指出，fork Termux 时需要重新构建 bootstrap 以匹配新的应用包名。因此当前官方下载 bootstrap 的方案仍需在后续 Round 10 完成“NebulaForge 自有包名 bootstrap”构建链，才能把它称为完全等价的内嵌 Termux 用户态。
