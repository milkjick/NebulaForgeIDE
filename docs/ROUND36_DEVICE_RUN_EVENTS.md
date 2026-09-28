# Round 36 — Device Run Event Chain

## 目标
把真实 Android 设备连接状态接入 RunSession，而不是在 Run Tool Window 中通过定时轮询模拟状态。

## 实现
- `RunDeviceEventMonitor` 使用 ADB `track-devices` 事件流。
- 运行成功后，为该 RunSession 建立独立 device monitor。
- 设备状态通过 `IdeEvent.Device` 写入统一 `IdeSessionBus`。
- ONLINE 以外的状态（offline / unauthorized / bootloader / unknown）会结束当前运行会话并停止 Logcat。
- `stopLogcat()` 改为真正取消 logcat Job，而不是取消一个不存在的复合任务键。
- 仍然保持 Way-B：设备监控通过 embedded PTY 执行，不使用 Android ProcessBuilder。

## 验证边界
本轮进行了源码结构检查和 ZIP 完整性检查；当前工程仍缺少 Gradle Wrapper JAR，因此没有宣称 APK 编译或真机运行成功。
