# Round 7 — Workspace / Project Explorer / File Editor

本轮在 Round 6 的真实 PTY + Session + Android Gradle Build/Run 基础上继续推进，目标是把“项目目录 → 文件树 → 编辑 → 保存 → 构建诊断”串成一条真实工作流。

## 已实现

- 工作区目录：使用应用私有 `home/projects`。
- Android 项目模板：
  - Android Empty
  - Android Compose
- 模板生成真实 `settings.gradle.kts`、根 `build.gradle.kts`、app 模块、Manifest、源码、资源和 `.nebulaforge/project.json`。
- SAF 目录导入：通过系统目录选择器把用户选择的项目复制到应用工作区。
- 项目文件树：显示工作区项目内源码/配置文件。
- 文件编辑器：真实读取文件、修改、保存；离开编辑器时自动保存未保存内容。
- Build Problems：构建解析出的文件/行/列/消息可以点击，直接进入文件编辑器。
- Android 构建控制器的 ADB 探测不再依赖 JDK 17，单独使用 SDK 环境。
- 环境路径继续保持 Scheme B：不重新引入外部 Termux App / RunCommandService。

## 明确限制

- 当前编辑器是功能性基础文本编辑器，还没有接入开发方案要求的 Sora Editor + TreeSitter + LSP。
- 当前项目模板没有随源码包伪造 Gradle Wrapper JAR；模板中的 `gradlew` 会调用嵌入环境 PATH 中的 `gradle`，因此真正构建仍要求工具链已经安装。
- 当前 Build/Run 仍优先验证真实设备闭环，设备选择、Activity 精确解析、取消/持久化历史仍需下一轮完善。
- 当前没有声称“已在真实 Android 设备成功构建安装”，因为本开发环境没有连接用户设备。
