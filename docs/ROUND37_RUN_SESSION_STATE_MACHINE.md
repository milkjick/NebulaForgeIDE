# Round 37 — Android Run Session State Machine

本轮把 Android 实机运行生命周期收敛到单一 RunSession 状态源。

## 生命周期

`QUEUED → RESOLVING_DEVICE → INSTALLING → INSTALLED → LAUNCHING → RUNNING`

运行期间：

- `RUNNING → PROCESS_EXITED → SUCCEEDED`
- `RUNNING → DEVICE_DISCONNECTED → FAILED`
- `RUNNING → STOPPING → STOPPED`
- 任一可中断阶段可进入 `FAILED/CANCELLED`。

## 真实运行链

1. 使用 Way-B PTY 执行 adb。
2. `adb install -r` 安装 APK。
3. `adb shell monkey` 启动 applicationId。
4. `adb shell pidof <package>` 获取真实 PID。
5. 运行期间监控 PID 是否仍存在，以识别进程退出。
6. `adb track-devices` 作为设备连接状态事件源。
7. Logcat 作为独立 LOGCAT session，但关联到同一 RunSession。
8. Stop 时执行 `adb shell am force-stop <package>`。

## 持久化

RunSession 现在保存：

- packageName
- pid
- exitCode
- deviceSerial
- lifecycle status
- logcatSessionId
- message / timestamps

IDE 重启后不会假装恢复已经不存在的进程；所有非终态运行状态会被标记为 FAILED。

## 验证边界

本轮未声称 APK/真机编译验证。当前源码包仍需完整 Gradle Wrapper JAR、Android SDK 和真实设备环境才能进行端到端验证。
