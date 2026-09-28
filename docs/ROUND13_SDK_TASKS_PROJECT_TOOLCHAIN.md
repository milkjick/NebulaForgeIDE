# Round 13 — SDK Installer / Task Center / Project Toolchain

本轮目标：把“安装按钮”进一步变成真实的、可持久化、可取消的运行时任务，并把 Android SDK 组件与项目级工具链选择纳入同一状态模型。

## 1. Android SDK 实际安装

`AndroidSdkInstaller` 使用 IDE 自己的 `sdkmanager`，安装后重新检查：

- `platform-tools` → `adb`
- `cmdline-tools;latest` → `sdkmanager`
- `build-tools;35.0.0` → `aapt2`
- `platforms;android-35` → platform directory
- NDK package 可按同一机制扩展

不会因为目录创建成功就标记 READY。Android 官方文档说明 `sdkmanager` 用于查看、安装、更新和卸载 SDK package，并可用 `sdkmanager --licenses` 处理许可证。

## 2. Toolchain Task Center

新增持久任务状态：

`QUEUED → RUNNING → SUCCEEDED / FAILED / CANCELLED`

任务记录保存在：

`$HOME/.nebulaforge/toolchain/tasks.json`

Activity 重建后，之前真正运行中的进程不会被伪装成 RUNNING，而会标记为“IDE 进程重启，任务未继续执行”。

## 3. Project-scoped toolchain

`.nebulaforge/project.json` 支持：

- `jdkMajor`
- `gradleVersion`
- `androidSdk`
- `buildToolsVersion`
- `ndkVersion`

## 4. Gradle Wrapper 安全边界

之前模板生成的 `gradlew` 只是把参数转给系统 Gradle，这不是真正的 Gradle Wrapper。本轮删除该伪 Wrapper。

`GradleWrapperProvisioner` 会调用实际安装的 Gradle `:wrapper` task，并要求最终同时存在：

- `gradlew`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`

构建系统也只在这些文件齐全时才把项目视为真实 Wrapper 项目，否则使用已验证的 IDE Gradle fallback。

Gradle 官方文档明确建议使用 Wrapper，并说明 Wrapper 由 `gradle :wrapper` 生成且包含 `gradle-wrapper.jar` 与 `gradle-wrapper.properties`。
