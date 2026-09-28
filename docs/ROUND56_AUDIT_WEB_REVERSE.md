# Round56：既有功能审计 + Web 逆向 9.2

## 本轮基线

基于 Round55 APK 逆向源码继续开发。用户要求优先检查历史功能缺陷，并对照《NebulaForge IDE 完整开发方案 v2》补缺。

## 已修复的历史问题

### 1. 修复核心模块循环依赖

Round55 存在 `core-pty -> core-session -> core-pty` 的 Gradle 模块循环依赖。
本轮将 `PtySession` 的 SessionBus 耦合移出 `core-pty`，改为可选状态回调；`core-pty` 不再依赖 `core-session`。这样依赖图恢复为有向无环结构。

### 2. 修复 Android SDK 安装命令

原实现调用 `sdkmanager --install <package>`，该参数形式不正确。现在使用：

```text
sdkmanager --sdk_root=<sdk-root> <package-id>
```

同时不再静默吞掉许可证步骤；后续 UI 应提供明确的许可证确认入口。

### 3. 修复 Termux bootstrap 符号链接解析

新版 Termux bootstrap 的 `SYMLINKS.txt` 使用 `target←link` 格式；旧实现只接受 TAB。现在同时兼容 Unicode 左箭头和 TAB 格式。

### 4. 修复 bootstrap 断点下载 416

如果已有的 `.part` 文件在服务端已经超出资源范围，收到 HTTP 416 后现在会删除部分文件并重新下载，避免安装永久失败。

### 5. 修复 RunToolWindowScreen 的变量初始化顺序

Round55 中 `timeline` 在 `selectedSession` 声明前被引用，会直接导致 Kotlin 编译失败。本轮已调整声明顺序。

### 6. 进程重启状态恢复

Application 启动时现在主动将持久化 registry 中仍处于 Preparing/Running 的旧 Session 标记为“IDE 重启，原进程已不存在”，避免 UI 误显示为正在运行。

## 本轮完成：9.2 Web 逆向

新增：

```text
app/src/main/kotlin/com/nebulaforge/app/reverse/WebTrafficRecorder.kt
app/src/main/kotlin/com/nebulaforge/app/reverse/ReverseWebViewClient.kt
app/src/main/kotlin/com/nebulaforge/app/reverse/WebReverseScreen.kt
```

功能：

- 内嵌 WebView。
- 自定义 WebViewClient + WebChromeClient。
- `shouldInterceptRequest` 观察 GET/HEAD 请求并代理响应。
- 记录请求 URL、方法、请求头、状态码、响应头、MIME、响应字节数。
- 保留最多 16KB 的响应文本预览。
- `text/event-stream` 分类为 SSE。
- `Upgrade: websocket` 分类为 WebSocket。
- `Transfer-Encoding: chunked` 分类为 chunked。
- JSON 响应可进一步分类为 API。
- POST/上传请求不强行重放，因为 `WebResourceRequest` 不提供可安全复用的请求体；此类请求只记录元数据并让 WebView 原生网络栈继续执行。
- WebSocket 仅识别升级请求，不读取 WebSocket 帧。
- 逆向页面现在提供“APK / Web”两个 Tab，共享 9.4 合规确认。

## 与 9.2 文档的边界

文档要求 WebView 拦截网络请求并识别 SSE/WebSocket/chunked，同时明确 WebView 拦截存在覆盖限制，完整覆盖需要 9.3 MITM。

因此本轮没有把 WebView 拦截伪装成完整 HTTPS MITM。9.3 将单独实现 API 逆向代理。

## 尚未完全关闭的缺口

- APK 逆向：apktool/JADX 实际 JAR 尚未随源码包伪造或打包；需要后续引入经过许可证审核的真实二进制。
- APK 正式签名：目前只有本地调试签名，正式 keystore 导入/alias/密码管理尚未完成。
- 全局 strings.xml：历史 UI 中仍存在较多 Kotlin 直接写入的用户可见字符串，需要单独进行一次资源清理。
- v2 文档中的独立 `feature-reverse-apk` / `feature-reverse-web` Gradle 模块尚未从 `app` 中完全拆分。
- 第四章 C/C++、第六章 AI 全项目生成、第八章 MCP、第十四至十七章插件系统等仍有明显未完成项，不能宣称已完成。
- 9.3 API MITM 尚未实现。

## 构建验证

本轮继续尝试 `./gradlew :app:tasks --offline`。当前沙箱没有本地 Gradle 8.9 distribution，wrapper 仍尝试访问 `mirrors.aliyun.com`，因网络不可用而失败。因此本轮不能声称 `assembleDebug` 已通过。

已进行静态检查：

- Gradle 模块依赖图无循环。
- 本轮修改 Kotlin 文件未发现 Kotlin parser `expecting` 类语法错误。
- XML 资源保持可解析。
