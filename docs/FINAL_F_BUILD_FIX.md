# Final F Build Fix

本轮针对“Project is being reinitialized / Failed to initialize project / 无法构建项目”处理工程初始化链路。

## 已修复

1. Gradle Wrapper 不再指向 `mirrors.aliyun.com`，改为官方 `services.gradle.org`。
2. 删除 `settings.gradle.kts` 中对阿里云 Maven 公共镜像的硬编码，恢复 `google()`、`mavenCentral()`、`gradlePluginPortal()`。
3. 保留 JitPack 作为明确依赖仓库。
4. 版本提升到 `1.62.1-final-f-buildfix` / versionCode `73`。
5. 保留 Final F 自定义 Nebula Forge Launcher Icon，不使用 Android 默认图标。

## 验证说明

本环境没有 Gradle 8.9 本地 distribution，且无法解析外部网络域名，因此只能验证工程结构和 Wrapper 配置，不能把网络下载失败当作源码编译失败，也不能宣称 assembleDebug 已通过。

Wrapper 当前目标：
`https://services.gradle.org/distributions/gradle-8.9-bin.zip`
