# NebulaForge IDE：基于源码骨架的实质化修改记录

基线：用户上传的 `NebulaForgeIDE_源码骨架-1.zip`
依据：`NebulaForge_IDE_开发方案_v2.md`
路线：方案 B（IDE 内嵌 Termux 用户态/终端核心）

## 本轮完成

1. 新增 `core:core-session`
   - PTY / Toolchain / Build / Device / Run / Logcat 统一 Session
   - `IdeEvent` 统一事件
   - `SessionState` 统一状态

2. 新增 `core:core-pty`
   - NDK Native PTY
   - master/slave PTY
   - controlling terminal
   - process group
   - TIOCSWINSZ
   - stdin/stdout/stderr
   - Ctrl-C / Ctrl-Z 字节入口
   - resize
   - native wait/close

3. 新增 `stack:stack-android`
   - `AndroidProjectType`
   - `GradleAndroidBuildSystem`
   - Gradle Wrapper `assembleDebug`
   - build error parser

4. 新增真实 Android Build/Run Controller
   - JDK/SDK 环境注入
   - `./gradlew assembleDebug`
   - debug APK 查找
   - `adb devices`
   - `adb install -r`
   - Activity 启动采用 `monkey -p <package> 1`
   - `adb logcat`

5. 环境检查从“文件存在”提升为“实际执行验证”
   - `java -version`
   - `adb version`
   - executable bit
   - Android SDK platform-tools/build-tools

6. Terminal 导航不再使用 TerminalPlaceholderScreen，而是连接 Native PTY Session。

7. 编辑器首页增加“构建 / 运行”入口，Build Center 可输入 Android 项目目录并启动真实 Build/Run Session。

## 未宣称完成

- 当前构建环境没有 Android 真机，因此没有伪造 ADB 实机 PASS。
- 本 ZIP 没有伪造 Termux bootstrap 二进制。
- Sora Editor/LSP/完整 Project Wizard/AI/MCP/逆向等按文档后续阶段推进。
- Gradle Tooling API 尚未接入；当前 Android MVP 构建采用 Gradle Wrapper shell 方式，便于先验证真实设备闭环。
