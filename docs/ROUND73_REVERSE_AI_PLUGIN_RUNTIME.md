# Round73：逆向 AI 修改事务与真实 MCP 插件生态

本轮不是新增静态页面，而是把三条链路继续连接：

1. APK/Web/API 逆向结果 -> AI 分析 -> 候选修改 -> Diff 校验 -> 用户确认后写入逆向工作区。
2. MCP Registry 发现 -> 读取真实 remote URL -> MCP Host Streamable HTTP 连接 -> initialize -> tools/list。
3. 插件运行时增加依赖拓扑解析，检测缺失依赖和循环依赖；市场安装继续执行 HTTPS、SHA-256、plugin.xml、ID/版本一致性检查。

AI 修改默认只产生候选 unified diff，不自动覆盖文件。应用时限制在 apktool/JADX 逆向工作区，并生成 .nebulaforge.ai.bak。

外部 MCP Registry 条目没有可直接连接的 HTTPS remote 时，只作为发现结果展示，不把 repository URL 冒充 MCP endpoint。
