# Round 35 — Real Device Manager / Capability Detection

本轮沿 NebulaForge IDE v2 的统一 RunConfiguration / RunSession 路径继续，不引入假的在线状态。

## 实现

- Android 使用嵌入式 Way-B PTY 执行 `adb devices -l`。
- 仅当 ADB 明确报告 `device` 时标记 ONLINE；offline / unauthorized / bootloader 单独保留状态。
- ONLINE Android 设备进一步通过 `adb -s <serial> shell getprop` 查询 ABI、API、厂商、型号。
- Flutter 使用 `flutter devices --machine`，解析 JSON，而不是正则猜测设备列表。
- Flutter 设备保留 targetPlatform/category/sdk 等运行时信息，并根据实际平台判断 Web 能力。
- Run 配置设备选择会根据项目类型、运行模式和设备状态过滤不兼容设备。
- 切换/刷新设备后，如果原选择已经不再兼容，会清除旧 deviceId，避免把失效设备继续传给 RunSession。
- UI 展示设备状态、API、ABI/平台信息。

## 非目标

- 不把 unauthorized/offline 伪装成 ONLINE。
- 不声称设备已授权、已安装 APK 或已启动应用；这些仍由真实 ADB/Flutter RunSession 结果决定。
- Web frontend/backend 当前不需要 Android/Flutter device。
