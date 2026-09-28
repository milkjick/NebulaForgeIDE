# Final E — Embedding + Vector RAG + Web Fact Verification + Failure Patterns + Cross-Project Learning

## 目标

把 Agent 的长期学习从词法检索升级为真正的 Embedding 向量检索，同时保留可解释的词法检索作为 fallback/补充层。

## 1. Embedding

- 新增 `EmbeddingClient`。
- 使用 OpenAI-compatible `/embeddings` HTTP API。
- 默认 embedding model：`text-embedding-3-small`。
- API Key 只从现有 AI Provider 设置读取，不写入项目经验库。

## 2. Vector RAG

- 新增 `VectorKnowledgeStore`。
- 项目向量索引：`.nebulaforge/agent-memory/vector-index.json`。
- 全局跨项目索引：应用私有目录 `nebulaforge-agent/global-experience-vectors.json`。
- 使用 cosine similarity。
- 排序同时考虑：向量相似度、验证状态、reward、新鲜度。
- 新鲜度采用指数衰减，约 45 天时间常数。

## 3. 跨项目经验

成功经验进入项目索引，同时进入全局经验索引；全局条目保留来源项目路径和项目名，Agent 可在新项目中召回相似经验。

## 4. 失败模式库

- `.nebulaforge/agent-memory/failure-patterns.json`
- 对相似失败进行签名去重。
- 记录 occurrences / resolved / reward。
- reward 随时间衰减。
- Agent RECALL_MEMORY 会同时看到失败模式。

## 5. Web Fact Verification

新增 `VERIFY_WEB_FACTS` Agent Action。

要求至少两个网页证据，然后由 AI 根据明确来源返回：

- SUPPORTED
- PARTIAL
- CONTRADICTED
- INSUFFICIENT

同时保留支持来源、冲突来源、理由和置信度。

## 6. Agent 流程

```text
用户需求
  ↓
项目记忆 + 词法经验
  ↓
Embedding Vector RAG
  ↓
跨项目经验
  ↓
失败模式
  ↓
联网搜索
  ↓
网页证据
  ↓
事实核验（需要时）
  ↓
Agent Plan
  ↓
执行
  ↓
Build/Run/Verify
  ↓
成功/失败 + 用户反馈
  ↓
经验库 + 向量索引 + 失败模式库
```

## 7. 编译验证限制

当前源码环境的 Gradle Wrapper 指向 mirrors.aliyun.com；如果当前执行环境无法解析该域名，Gradle 会在下载 Gradle 8.9 前失败，因此不能将网络阻塞误报为 Kotlin 编译通过。
