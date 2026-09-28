# Nebula Forge IDE 1.60.0 Final D

本轮重点是 AI Agent 的持续学习与联网搜索，而不是修改模型权重。

## 1. 项目长期记忆

`.nebulaforge/agent-memory/memory.json` 保存项目事实、决策、约束和高置信度经验。

## 2. 项目经验库

`.nebulaforge/agent-memory/experiences.json` 保存任务问题、解决方案、结果、标签和是否验证。

## 3. 持续学习记录

`.nebulaforge/agent-memory/learning.json` 保存任务、反馈、来源、验证状态和 reward。Agent 使用词法匹配 + 验证加权 + 反馈 reward 进行本地混合检索。

## 4. 数据集导出

可生成 `.nebulaforge/agent-memory/training-dataset.jsonl`，用于后续人工审查、离线微调或评估数据准备。不会在后台擅自修改模型权重。

## 5. 联网搜索

`SEARCH_WEB` 先搜索，再保留标题、来源、URL、摘要，并尝试读取前 3 个网页正文作为证据。非 HTTP/HTTPS、非文本内容会被拒绝。

网页证据不能替代项目源码和构建结果；Agent 提示词要求交叉验证并保留 URL。

## 6. 安全边界

- 不把 API Key 写入项目记忆。
- Agent 计划不能直接产生任意 shell 命令。
- 搜索网页只作为外部证据。
- 学习数据需要可审计、可删除、可导出。
- “自我学习”指经验、反馈、检索排序的持续改进，不声称手机端自动训练大模型权重。

## 7. 本轮版本

versionCode: 70
versionName: 1.60.0-final-d-agent-learning-search
