# Round 16 — Stage 0 Environment Validation + Gradle Tooling Bridge Runtime

本轮继续沿 `NebulaForge_IDE_开发方案_v2.md` 的 Stage 0 / 第三章推进，重点不是增加空壳 UI，而是让“Gradle Tooling API 可用”成为一个可验证的运行时能力。

## 已实现

1. **Gradle Tooling API Bridge 进入真实 Toolchain 状态源**
   - 新增 `GRADLE_TOOLING_BRIDGE`。
   - 不再用“JAR 存在”判定 READY。
   - 必须同时满足：JAR + SHA-256 sidecar + JDK17 下真实 `java -jar --self-test`。

2. **Bridge Runtime 事务安装**
   - `GradleBridgeRuntimeManager.installFromFile()`：缓存、SHA-256、临时文件、原子提交、提交后 self-test。
   - `installFromUrl()`：HTTP Range 断点续传、SHA-256、staging、原子提交、失败清理。
   - URL 安装必须由上层提供可信 URL 和 64 位 SHA-256；源码不伪造下载地址。

3. **Settings 真正安装入口**
   - Toolchain 页面新增 Bridge 状态。
   - 可从 Android 文件选择器选择已经构建的 `gradle-tooling-bridge.jar`。
   - 选择后实际复制、校验并通过 embedded PTY 执行 self-test。

4. **Tooling API Task Model**
   - Bridge 支持 `--tasks`。
   - 使用 Gradle Tooling API `GradleProject` 模型枚举 root/subproject tasks。
   - Android `GradleAndroidBuildSystem.listAvailableTasks()` 不再返回空列表；Bridge 不可用时返回空列表，而构建仍保留真实 PTY fallback。

5. **Stage 0 Spike**
   - `tools/spike/android-mvp` 提供最小 Android validation project。
   - `tools/spike/validate_stage0.sh` 验证 spike 结构，并在提供 `NEBULAFORGE_BRIDGE_JAR` + `JAVA_HOME` 时运行真实 bridge self-test。

## 未宣称完成

- 当前沙箱没有完整 Android SDK/Gradle Wrapper JAR，因此没有在这里宣称 APK `assembleDebug` 成功。
- 当前 bridge worker 仍需在可运行 Gradle/JDK 环境中构建成 JAR；本源码不伪造预编译产物。
- 没有宣称真实 Android 设备安装/启动成功。
- Tooling API 的完整 Project Model（source sets/dependencies）尚未全部映射到 IDE 数据模型。

## 下一阶段候选

在 Stage 0 validation 通过后，继续做统一 `ProjectEnvironment` / BuildSession / RunSession 事件源，以及 ADB/Logcat 的 Way-B executor 化；再进入 Flutter/Web/C++ 技术栈，而不是先堆 UI。
