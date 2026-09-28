# Round 25 — Editor Diagnostics + BuildFix Rebuild Loop

## 本轮目标

把 Round 24 的 DiagnosticStore 与 BuildFixLoop 基础设施真正接到应用层：

- Application 级共享 DiagnosticStore / IdeSessionBus；
- LSP/Build/Problems/Editor 使用同一诊断源；
- Sora Editor 页面显示当前文件的实时诊断；
- Build Center 的 Build→AI Review→Apply→Rebuild 链路真实执行；
- buildAndRun 在构建完成后结束 Flow，Run/Logcat 继续由共享事件总线驱动；
- Build only 用于 AI 修复后的验证构建；
- 重复 Diagnostic 去重，避免 LSP replace 后再次 emit 造成重复问题项。

## 真实边界

本轮没有声称已安装 Java/Kotlin LSP Server，也没有声称 APK/真机验证成功。当前沙箱仍可能缺少 Gradle Wrapper JAR、Android SDK 和设备。
