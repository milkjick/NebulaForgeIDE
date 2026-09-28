# Final F — Agent Learning Complete

本轮在 Final E 上继续完成 Agent 学习闭环：

- 本地离线向量能力：支持 `files/nebulaforge-agent/models/local-embedding.json` 的 token-vector 模型；模型不存在时使用确定性的离线子词向量编码器，保证无网络时 RAG 仍可检索。
- 在线 Embedding 失败自动回退到离线编码，不伪装网络可用。
- Agent 经验自动评估：按完成率、计划状态、验证步骤、失败步骤计算可审计分数。
- 多轮任务记忆：`.nebulaforge/agent-memory/conversation.json`，Planner 会读取最近对话上下文。
- 失败自动归因：Android SDK、JDK、Gradle Wrapper、网络/DNS、权限、Kotlin 编译、ADB 等常见模式。
- Web 来源可信度评分：官方开发者文档、政府机构、学术机构、GitHub 与普通网站分层；这是来源启发式，不是事实真值。
- RAG 引用回链：搜索输出保留 `[n] 标题 — URL`，同时保存来源可信度。
- Agent 学习控制中心：显示记忆/经验/验证/失败/对话统计、Embedding 模式、失败模式与训练数据导出。
- 应用图标改为 Nebula Forge 专属几何图标，不使用 Android 默认启动器图标。

## 边界

“离线向量能力”不等同于本地大模型权重训练。当前支持加载真实本地 token-vector 模型文件，并提供无模型时的离线向量编码器；若要在手机上运行真正的 Transformer Embedding 模型，需要进一步集成对应推理 Runtime 与模型权重。

源码验证：ZIP 完整性必须通过；Android/Gradle 编译仍以实际构建环境为准，不在无法取得 Wrapper 的环境中虚报编译通过。
