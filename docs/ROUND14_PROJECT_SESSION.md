# NebulaForge IDE Round 14 — Project Toolchain Resolver + Build Session

本轮严格对照 `NebulaForge_IDE_开发方案_v2.md` 的第 2、3 章以及第 20 章阶段路线，同时保留项目此前已经明确选择的 **Way B：IDE 内嵌 Termux bootstrap + terminal-emulator/terminal-view**，没有重新切回外部 Termux。

## 本轮落地

### 1. ProjectType / BuildSystem 真正进入项目解析链

新增 `ProjectResolver`：

- 从真实项目目录识别 `ProjectType`；
- Android 类型要求 settings Gradle 文件以及 Android Gradle Plugin 声明；
- 读取 Gradle 文件中的 `compileSdk`、`minSdk`、`targetSdk`、`buildToolsVersion`、`ndkVersion`、`namespace`、`applicationId`；
- 生成 `ProjectDescriptor`，把项目类型、BuildSystem、Wrapper、构建需求交给后续层。

### 2. Project Toolchain Resolver

新增 `ProjectToolchainResolver`：

- 读取 `.nebulaforge/project.json`；
- 项目级 JDK 版本；
- 项目级 Android SDK；
- 项目级 Build Tools；
- 项目级 NDK；
- compileSdk 对应 `platforms/android-<api>/android.jar` 真实检查；
- ADB / Gradle 真实检查；
- 输出唯一 `ProjectEnvironment`；
- 自动同步 `local.properties`。

这样 Build / Run 不再只调用一个全局 `Environment.buildGradleEnv()`。

### 3. Build Session Center

新增持久化 `BuildSessionManager`：

- QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED；
- 实时 BuildEvent 流；
- 最近日志尾部持久化；
- Activity 重建后恢复历史状态；
- Android 进程重启后不会把已经死亡的 Gradle 进程伪装成 RUNNING；
- 支持真正取消 Gradle 任务。

数据：

```text
files/sessions/build.json
```

### 4. Gradle Wrapper 执行规则

严格采用：

```text
真实 Wrapper：gradlew + gradle-wrapper.jar + gradle-wrapper.properties
        ↓
优先执行
        ↓
没有完整 Wrapper
        ↓
使用 IDE 已验证的 Gradle fallback
```

不再把一个 `exec gradle "$@"` 的脚本当成 Wrapper。

### 5. Build / Run 闭环

Android Build/Run 现在走：

```text
ProjectResolver
  ↓
ProjectToolchainResolver
  ↓
ProjectEnvironment
  ↓
BuildSessionManager
  ↓
GradleBuildSystem
  ↓
assembleDebug
  ↓
APK
  ↓
ADB install
  ↓
applicationId
  ↓
启动
  ↓
Logcat
```

构建错误继续转成 `BuildError` / `IdeEvent.Diagnostic`。

## 与开发方案的对应关系

- 第 2.1/2.2：`ProjectType`、`BuildSystem` 抽象真正进入项目解析链。
- 第 2.3.1：AndroidProjectType + GradleBuildSystem。
- 第 3.1：统一环境与 BuildSystem 进程生命周期；Way B 下由 IDE 自身 runtime 执行。
- 第 3.2：Wrapper 优先，并保留后续 Tooling API bridge 的扩展点。
- 第 20 阶段 1：Android 单技术栈最小闭环继续实质化。

## 尚未声称完成

本轮没有把 Gradle Tooling API 客户端伪装成已经完成。开发方案要求 Tooling API 客户端运行在 Termux 用户态并连接 Gradle Daemon；在当前 Way B 架构下，下一轮应继续实现这个 runtime bridge，而不是在 Android UI 进程里直接假装调用 Tooling API。

同样没有声称真实 Android 手机已经成功 `assembleDebug → adb install → launch`；最终仍需在目标设备上验证。
