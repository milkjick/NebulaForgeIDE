# Round74 — AI Agent 计划可视化与执行

## 本轮目标
让 AI Agent 在执行前把用户需求转换成结构化计划，并持续显示每一步状态、输出与最终结果。

## 实现
- `core-agent/AgentPlan.kt`
  - AgentPlan / AgentPlanStep
  - AgentPlanStatus / AgentPlanStepStatus
  - AgentPlanAction
  - AgentPlanParser：严格 JSON、限制动作集合，不接受模型生成 shell 命令
  - AiAgentPlanner：使用真实 OpenAI-compatible Provider 建立计划
  - AgentPlanStore：持久化计划，支持进程重启恢复
  - AgentPlanRunner：逐步执行并持久化状态
- `AgentPlanScreen`
  - 用户需求输入
  - AI 建立计划
  - 显示计划摘要
  - 显示每一步 action、状态、输出
  - 执行前确认
- `NebulaForgeApplication`
  - 接入计划 Store/Planner/Runner
  - ANALYZE_PROJECT、BUILD、RUN、INSPECT_APK、INSPECT_WEB、INSPECT_API、DISCOVER_MCP 执行入口
  - APPLY_REVIEWED_CHANGES 明确拒绝直接自动接受，必须经过 Diff Review

## 安全边界
AI 计划不能直接携带任意 shell 命令。模型只能选择宿主定义的动作枚举。文件修改仍然走已有 Diff Review + GenerationTransaction。

## 验证
ZIP 完整性检查通过。Gradle 编译尝试被当前环境的 Gradle 8.9 Wrapper 下载 DNS 问题阻断：`UnknownHostException: mirrors.aliyun.com`；因此没有声称编译成功。
