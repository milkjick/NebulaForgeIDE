# Final C：Agent 项目记忆、经验库、联网检索与可审查学习

## 本轮目标

把 Agent 从“一次性计划器”升级为跨会话的项目协作 Agent：

1. 项目长期记忆：写入项目 `.nebulaforge/agent-memory/memory.json`。
2. 项目经验库：写入 `.nebulaforge/agent-memory/experiences.json`。
3. 计划建立前自动检索相关记忆和经验并注入上下文。
4. Agent 新增 `RECALL_MEMORY` 与 `SEARCH_WEB` 动作。
5. 联网搜索默认使用 DuckDuckGo HTML，结果保留标题、URL、摘要。
6. 每次 Agent 计划结束后自动沉淀一次经验；完成且成功的经验标记为已验证，并形成项目记忆。
7. 所有经验去重、数量限制和项目目录隔离。

## 记忆内容边界

不会保存 API Key、密码、Cookie、访问令牌等秘密。Agent 记忆用于项目架构、构建问题、用户明确决定、工具链状态和已验证解决方案。

## “自我学习”定义

本轮实现的是可持久、可检索、可审查的经验学习，而不是偷偷修改大模型权重。模型权重训练/微调属于独立训练流水线，需要数据集、训练资源、评估和发布流程，不能由一次 Android IDE Agent 任务安全地自动完成。

当前学习闭环：

用户需求 → Agent Plan → 执行 → 结果 → 经验去重 → 项目经验库 → 下次 Recall → 新计划

后续可以在此基础上增加向量 Embedding、混合检索、人工反馈评分和独立微调数据集导出。

## 编译验证

源码结构检查通过；本轮 Gradle 编译仍受当前沙箱无法解析 `mirrors.aliyun.com` 阻塞，因此没有声称 Kotlin 编译通过。
