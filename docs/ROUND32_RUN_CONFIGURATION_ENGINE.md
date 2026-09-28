# Round 32 — Unified Run Configuration Engine

依据 `NebulaForge_IDE_开发方案_v2.md` 第二章统一抽象层：新增技术栈通过 ProjectType / BuildSystem / LanguageService / RunConfiguration 接入，不修改 Editor、ToolWindow 与导航核心。

## 本轮实现

- `RunConfigurationSpec`：名称、项目类型、模式、参数、环境变量、工作目录、设备 ID、端口。
- `RunConfigurationDefaults`：Android Debug、Flutter run/profile/release/web、Web dev/start。
- `RunConfigurationStore`：项目级 `.nebulaforge/run-configurations.json`。
- `RunConfigurationEngine`：将持久化配置解析成真实 stack command，并进行 shell quoting。
- `UnifiedRunController`：Android 进入既有 Gradle→APK→ADB；Flutter/Web 进入 embedded Way-B PTY。
- `RunDeviceCatalog`：Android `adb devices`、Flutter `flutter devices --machine`，均通过 NativePty。
- `PortAllocator`：本地端口检查与分配。
- Run Tool Window：选择配置、编辑模式/参数/环境变量/工作目录/设备/端口、保存、运行、构建。
- Flutter web 配置会把持久化端口转成 `--web-port`；Flutter 设备转成 `-d`；Web 项目注入 `PORT` 环境变量。
- Android 增加正式 `AndroidRunConfiguration`，但真实安装/启动仍由 `RealAndroidBuildRunController` 负责，避免把 APK/ADB 生命周期错误地模拟成 shell 命令。

## 真实性边界

- 没有伪造设备列表；刷新失败返回空列表。
- 没有声称 Flutter/Node/ADB 已安装。
- Android 当前真实统一运行模式是 debug/run；未虚构 release/profile Android 执行器。
- 所有非 Android 常驻运行均走 embedded Way-B PTY，不使用 ProcessBuilder。
- 配置持久化不会在重启后伪造恢复不存在的进程。

## 验证

当前环境仍缺少 `gradle/wrapper/gradle-wrapper.jar`，且没有系统 Gradle，因此未宣称 APK 编译或真机验证成功。ZIP 完整性通过 `unzip -t`。
