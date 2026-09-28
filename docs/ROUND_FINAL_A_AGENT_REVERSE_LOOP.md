# Final Stage A：AI Agent + Reverse 闭环

本轮直接合并最终阶段 A 的关键闭环，不增加任意 shell action。

## Agent Plan
- 计划动作扩展：PROPOSE_MODIFICATION、VERIFY_RESULT。
- AI 计划器明确要求修改类需求先生成候选修改方案，不自动应用。
- 计划执行状态继续持久化，支持 DRAFT/APPROVED/EXECUTING/PAUSED/COMPLETED/FAILED。
- 每一步输出保存到计划状态，UI 显示进度与预计剩余时间。

## Reverse Evidence
- APK/Web/API 共享 ReverseEvidenceStore。
- 增加统一 Snapshot，Agent 验证步骤可以读取 APK 路径、Web 捕获数、API 端点数和最近 IDE diagnostics。
- APK 修改建议通过 ReverseAiAssistant 生成结构化风险/解释/unifiedDiff，不直接写入。

## Build/Run verification
- Agent BUILD 使用 UnifiedRunController.build。
- Agent RUN 等待 UnifiedRunController.run 的实际 Flow 完成，不再后台 fire-and-forget。
- VERIFY_RESULT 汇总逆向证据与 IDE diagnostics。

## 安全边界
- Agent 计划不能生成任意 shell 命令。
- APPLY_REVIEWED_CHANGES 仍拒绝直接自动接受，必须经过 Diff 审查链。
- 逆向修改限定在逆向工作区。

## 验证
- ZIP 完整性通过。
- 当前沙箱无法完成 Gradle 编译：Gradle 8.9 Wrapper 下载阶段 DNS 无法解析 mirrors.aliyun.com；因此不宣称本轮 APK 编译通过。
