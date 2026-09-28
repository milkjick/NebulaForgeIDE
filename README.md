# NebulaForge IDE — Round56

本轮在 Round55 APK 逆向基础上进行一次“历史功能审计 + 9.2 Web 逆向”推进。

## 本轮完成

- 修复 `core-pty` 与 `core-session` 的 Gradle 循环依赖。
- 修复 Run Tool Window 中会导致 Kotlin 编译失败的变量声明顺序。
- 修复 `sdkmanager --install` 错误命令。
- 修复 Termux bootstrap `SYMLINKS.txt` 新格式解析。
- 修复 bootstrap 断点下载 HTTP 416。
- 应用启动时将上一进程遗留的 Running/Preparing Session 标记为失败，不伪造活跃进程。
- 完成第 9.2 节 Web 逆向：WebView、WebViewClient/WebChromeClient、请求/响应观察、SSE/WebSocket/chunked 分类与响应预览。
- 逆向工作台增加 APK/Web Tab，并共享一次性合规声明。

## 仍需继续

Round55/56 尚未宣称完整实现全部 v2 章节。特别是 9.1 的真实 apktool/JADX 二进制分发、正式签名管理、9.3 HTTPS MITM、C/C++、AI 全项目生成、MCP、插件系统以及全局 strings.xml 清理仍需后续轮次。

## 构建验证限制

当前开发沙箱没有 Gradle 8.9 本地 distribution，wrapper 需要访问镜像下载，而沙箱网络不可用。因此本轮没有伪造“assembleDebug 已成功”的结论。


## Round57
- 9.3 API HTTPS MITM：本机 CONNECT、动态 CA/域名证书、WebView ProxyController、端点去重、OpenAPI 3.0。
- Build Center 自动恢复当前 Workspace 项目默认 Run Configuration。
- 修复代理 chunked/缓冲流问题，增加用户 CA trust 配置。
- 版本：1.40.0-round57-api-reverse-audit。
- 完整 assembleDebug 仍受沙箱无法下载 Gradle 8.9 限制，未虚假宣称构建通过。

## Round F-Fix（本轮，Claude 依据用户提供的真实编译日志修复）

用户在自己设备（Android Code Studio / ACS 环境）上实际运行 `:app:assembleDebug` 并提供了完整报错日志（非本环境跑出）。日志显示 5 个失败任务，本轮逐一核实：

### 确认为真实源码错误并已修复（3 处）

1. **`core-ai-provider/AiProvider.kt` 第16行**：`Result<String>=runCatching{...}` 泛型闭合 `>` 与赋值 `=` 之间缺少空格，被 Kotlin 词法分析器贪婪匹配成 `>=`（大于等于运算符），导致函数体被解析器判定为不存在。补上空格后恢复正常。全局排查确认这是唯一一处同类写法，未发现其他"泛型闭合紧邻等号"的歧义代码。

2. **`core-toolwindow/ToolWindows.kt`**：两个独立问题——
   - 第58行 `calculateWindowSizeClass()` 缺少必需的 `Activity` 参数。改为 `LocalConfiguration.current.screenWidthDp >= 600dp` 判断宽窄，避免这个纯 UI 组件库模块反过来依赖 Activity，也不再需要 Experimental API 标注。
   - 第87行 `Modifier.align(Alignment.CenterEnd)` 报 "Unresolved reference 'align'"：详情面板的 `if (active != null) {...}` 整块代码被写在外层 `Box{}` 的 lambda **之外**，没有 `BoxScope` 可用。已将其挪入同一个 `Box` 作用域内。

3. **`core-environment/BootstrapInstaller.kt`**：两个独立问题——
   - 第180行引用了 `HttpURLConnection.HTTP_REQUESTED_RANGE_NOT_SATISFIABLE`，经查证 Java 标准库的 `HttpURLConnection` 类**没有定义**这个常量（该类只收录常见状态码，416 不在其中），是凭空编造的 API 引用。已改为直接用字面量 `416`。
   - 第393/398行报 `Unresolved reference 'MessageDigest'`/`'it'`：根因是文件顶部缺少 `import java.security.MessageDigest`，导致 `MessageDigest` 类型无法解析，连带使后续 `digest.digest().joinToString{it}` 里的 `it` 也报错——这是一个根因（缺失 import）引发的连锁报错，不是两个独立问题。已补上 import。

   **额外排查**：全局搜索发现 `core-agent/AgentFactVerification.kt` 也用到了 `MessageDigest`，但它用的是完全限定名 `java.security.MessageDigest.getInstance(...)`，不需要 import 也能编译，确认不是同类问题，未改动。

### 判断为非代码问题，未修改源码（2 处）

4. **`:app:mergeDebugResources` FAILED**：报错是 `Could not pack tree 'incrementalFolder': ... Request to write '8997' bytes exceeds size in header of '8192' bytes`，这是 Gradle 本地构建缓存写入 tar 格式条目时的缓存层错误，与项目源代码内容无关，是典型的**本地缓存目录已损坏**的特征。

5. **`core-editor-kernel:compileDebugKotlin` FAILED**：报错是 `Could not pack tree 'classpathSnapshotProperties.classpathSnapshotDir': ... exceeds size in header of '0' bytes`，同样是构建缓存序列化问题，不是这个模块的 Kotlin 源码本身有语法错误（日志里这个任务甚至没有输出任何 `e:` 开头的编译器错误行）。

**这两处的建议处理方式是清理本地 Gradle 缓存后重新构建**（在 ACS 环境的设置里找"清除缓存"或手动删除 `~/.gradle/caches` 对应目录），不是改代码能解决的问题——本轮没有为此改动任何源文件。

### 本轮验证边界（如实说明）

- 依然没有网络访问权限，无法下载 Gradle 8.9 分发包，**这三处代码修复是基于人工读码 + 对照 Kotlin 语言规范/Java 标准库文档做出的判断，没有经过真实编译器验证**。
- `core-agent` 模块（`BuildFixCoordinator.kt` 409行、`AgentFinalF.kt`、`AgentLearning.kt`、`AgentPlan.kt`、`AgentVectorRag.kt` 等，对应 FINAL_D/E/F 几轮"Agent Learning"特性）**在用户提供的报错日志里完全没有跑到编译阶段**（日志中只看到它的资源处理任务，没有 `compileDebugKotlin` 输出），也就是说这个模块是否还有其他编译错误，本轮**没有验证能力**——前面几个模块编译失败导致 Gradle 提前中止，还没轮到它。用户下次重新构建（在清理缓存、应用本轮3处修复后）如果这个模块暴露出新错误，属于预期之内，不是本轮修复引入的新问题。
