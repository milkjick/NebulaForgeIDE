# Final B：工具链、真机闭环、插件市场与收尾

本轮以实际运行链路为验收对象，而不是以文件存在作为成功条件。

## 工具链
- ToolchainDoctor 对 Embedded Runtime、JDK17、SDK CLI、ADB、Build Tools 执行真实探测。
- Android 项目优先使用项目 Gradle Wrapper；没有 Wrapper 时才使用已验证的内嵌 Gradle。
- SDK/JDK/PATH/JAVA_HOME/LD_LIBRARY_PATH/TMPDIR 继续统一由 Environment 提供。
- ADB 安装使用 `adb install -r`，返回非 0 时任务失败，不显示伪成功。

## 真机闭环
1. `adb devices -l` 发现设备。
2. 只接受 ONLINE 设备作为安装目标。
3. `adb install -r` 安装 APK。
4. 有 Activity 时使用 `am start -W -n`，否则使用 `monkey -p` 启动包。
5. Build/Run/Logcat 状态继续进入统一 Session 状态源。

## 插件市场
- 市场只接受 HTTPS。
- 下载大小上限 100 MiB，并在流式下载过程中再次限制。
- SHA-256（如果 Catalog 提供）必须匹配。
- `plugin.xml` 的 id/version 必须与 Catalog 一致。
- 插件 API 必须兼容宿主 API 1。
- MCP Registry 与 Nebula Plugin Catalog 明确分离；MCP Server 不当作 Dex 插件安装。
- JetBrains Marketplace 仅作为外部生态参考，不把 IntelliJ 插件伪装成 Nebula 插件。

## 真实插件源原则
当前源码只内置真实的官方 MCP Registry 地址作为外部 MCP 源；没有伪造不存在的 Nebula 官方第三方插件仓库。Nebula 插件需要 Catalog 提供真实 HTTPS 下载地址、SHA-256 和 plugin.xml 元数据。
