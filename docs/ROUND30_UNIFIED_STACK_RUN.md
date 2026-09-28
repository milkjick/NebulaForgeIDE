# Round 30 — Unified Flutter/Web Run Session

本轮继续开发方案的技术栈化方向，把 Flutter/Web 从“可识别、可构建”推进到真实驻留运行流程。

## 实现

- 新增 `RunConfiguration` 核心接口；ProjectType 可选提供运行配置。
- Flutter：`FlutterRunConfiguration`，支持 `flutter run`、web-server、profile、release。
- Web：`NpmRunConfiguration`，读取真实 `package.json/scripts` 后执行 npm run。
- 新增 `StackRunController`：通过现有 NativePty/TermuxCommandExecutor 执行，不使用 Android ProcessBuilder。
- Flutter 使用 `$HOME/flutter/bin` + Dart SDK 环境。
- Web 使用 embedded Node/npm 环境。
- BuildCenter 根据 ProjectType 自动显示 Flutter/Web 运行入口，并提供停止按钮。
- 运行输出进入统一 `IdeEvent.Output` / `IdeEvent.State`。

## 生命周期

ProjectType → RunConfiguration → StackRunController → NativePty → IdeSessionBus → Build/Run 输出。

运行进程是驻留 PTY：Flutter `flutter run`、Web `npm run dev/start` 不会被当成一次性构建任务。
停止通过协程取消触发 PTY close/signal 路径。

## 验证边界

当前沙箱没有系统 Gradle，项目也仍缺少 `gradle/wrapper/gradle-wrapper.jar`，因此本轮只做源码级结构检查和 ZIP 完整性检查，没有声称 APK 编译或真机运行通过。
