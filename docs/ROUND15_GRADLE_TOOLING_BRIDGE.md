# Round 15 — Gradle Tooling API Bridge

对应《NebulaForge IDE 开发方案 v2》第二章、第三章及阶段 0/1 的核心技术约束。

## 已落地

1. Android 进程不直接加载 `org.gradle.tooling.*`。
2. 新增 embedded PTY command executor；Gradle 命令从同一个 Way-B 用户态 shell fork/exec。
3. 新增 Termux-userland Gradle Tooling API worker：`tools/gradle-tooling-bridge`。
4. Worker 使用 `GradleConnector` / `ProjectConnection` / `BuildLauncher`，设置项目目录、Gradle user home、JDK，并转发 stdout/stderr 与 progress event。
5. Android 侧通过 request properties 启动 bridge JAR，并解析 `NEBULA_EVENT` 流为统一 `BuildEvent`。
6. Bridge JAR 缺失时，Gradle BuildSystem 自动降级到 embedded PTY + 真实 Wrapper/Gradle executable；不会把“缺少 bridge”显示成构建成功。
7. 构建取消路径最终销毁 PTY 进程组。
8. Bridge JAR 安装采用临时文件 + 原子替换 + SHA-256 记录，避免中断留下半个 JAR。

## 运行时安装

先在有完整 JDK/Gradle 的环境中：

```sh
gradle jar
```

然后把 `build/libs/gradle-tooling-bridge.jar` 安装到 IDE 私有运行时：

```sh
install_to_runtime.sh build/libs/gradle-tooling-bridge.jar <app-files>/runtime
```

目标：

```text
<app-files>/runtime/tools/gradle-tooling-bridge.jar
```

## 尚未宣称完成的部分

- Android 真机上实际拉起 Gradle Daemon 并完成 `assembleDebug` 尚未在当前构建环境验证。
- Tooling API worker 的 APK 内自动下载/升级器尚未实现；当前提供安全的运行时安装入口。
- Tooling API task/model 导入（项目层级、依赖、source sets）尚未接入 Project Explorer。
- Flutter/Web/C++ 按开发文档阶段 2/3 保持未提前实现。
