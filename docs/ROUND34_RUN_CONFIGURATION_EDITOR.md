# Round 34 — Run Configuration Editor

本轮将 Round 33 的运行状态机继续向 IDE 配置界面推进。

## 完成
- UI-independent `RunConfigurationEditorModel`
- Android/Flutter/Web 模式由 ProjectType 决定，不再允许任意自由文本模式
- 参数字段支持简单 shell-style quoting
- 环境变量解析与名称校验
- 配置复制/删除
- 配置保存前的模式、参数、环境变量、端口校验
- Android/Flutter 设备继续通过统一 RunDeviceCatalog 探测
- 配置继续持久化到项目 `.nebulaforge/run-configurations.json`

## 验证边界
本轮不声称 APK/真机编译运行成功；源码包只在当前环境进行结构检查与 ZIP 完整性检查。若 `gradle/wrapper/gradle-wrapper.jar` 缺失，不能声称 Gradle 编译验证通过。
