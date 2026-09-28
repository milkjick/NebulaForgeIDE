# Round 70 — 移动端构建稳定性修复

## 本轮针对的实际错误

构建已经进入资源合并和 Kotlin 编译阶段，但失败点不是源码资源解析错误，而是 Gradle 在写入本地 Build Cache 时无法归档 tree：

- `:app:mergeDebugResources`：`Failed to store cache entry ... Could not pack tree 'blameLogOutputFolder'`
- `:core:core-editor-kernel:compileDebugKotlin`：`Failed to store cache entry ... Could not pack tree 'destinationDirectory'`
- 同时出现 Kotlin compile daemon `terminated unexpectedly on startup attempt #1`。

## 修复

`gradle.properties` 做了以下移动端构建稳定性调整：

1. `org.gradle.caching=false`
   - 关闭 Gradle Build Cache，避免失败任务在本地文件系统上继续执行 tree cache 打包。
   - 不影响正常的 task output 生成；只是放弃这类本地缓存归档。
2. `org.gradle.parallel=false`
   - 降低多个 Android module 同时构建时的内存和文件系统压力。
3. `org.gradle.workers.max=2`
   - 限制 worker 并发，适配手机端内存和 I/O 条件。
4. `org.gradle.jvmargs=-Xmx1536m`
   - 避免移动端为 Gradle daemon 预留过大的堆造成系统回收。
5. `kotlin.compiler.execution.strategy=in-process`
   - 避免 Kotlin compiler daemon 单独启动后被 Android 系统回收。

## 重要说明

本环境当前无法访问 `services.gradle.org`，因此不能在沙箱中重新下载 Gradle 8.9 wrapper 并完成一次真实的完整 assembleDebug。此次源码包已针对日志中明确的 cache 写入失败和 Kotlin daemon 启动不稳定问题进行修复；在已有 Gradle 8.9 wrapper/cache 的开发环境中应使用新的 `gradle.properties` 直接重新构建。
