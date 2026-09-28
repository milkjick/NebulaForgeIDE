# Round 29 — Flutter/Dart/Web 技术栈接入

## 实际实现
- 新增 `stack-flutter`：FlutterProjectType + FlutterBuildSystem。
- Flutter 项目由 `pubspec.yaml` + `flutter:` 自动识别。
- Flutter 构建统一通过 Way-B `TermuxCommandExecutor`，支持 analyze/test/build-apk/build-web。
- Flutter 环境注入 `FLUTTER_ROOT`、`PUB_CACHE`、PATH；不会把 Android ProcessBuilder 当作 Flutter 执行通道。
- 新增 `stack-web`：WebFrontendProjectType、WebBackendProjectType、NpmBuildSystem。
- package.json 项目通过 scripts/src/vite/next 或 Node backend 特征识别。
- npm 构建统一通过 embedded PTY。
- LanguageServiceRegistry 已有 Dart / TypeScript descriptors，因此 Flutter/Web 项目可自动复用项目语言服务生命周期。

## 真实状态边界
- 本轮没有内置假的 Flutter SDK、Node.js、Dart SDK 或 Language Server 二进制。
- Flutter/npm 命令只有在运行时真实探测到可执行文件后才执行。
- 当前环境仍缺少 Gradle Wrapper JAR，因此无法在此沙箱完成整个 Android Gradle 编译链验证。
