# Round 45 — BuildFix Persistent Session/Event Integration

## Goal

把 BuildFixAgent 从“持久 JSON 任务”提升为 NebulaForge 的真实 Session：AI 分析、Review、Apply、Rebuild、Resolved/Failed/Cancelled 全部进入 `IdeSessionBus`、`SessionRegistry` 与 `SessionEventJournal`。

## Session model

新增 `SessionKind.BUILD_FIX`。Proposal 的 `sessionId` 与 BuildFix Session 使用同一个 ID，避免 Agent Session、Proposal Session、持久任务出现三套 ID。

## Event graph

```text
Build Session
     │
     │ build_fix / fixes
     ▼
BuildFix Session
     │
     │ rebuild / build_fix
     ▼
Rebuild Build Session
```

## Lifecycle

```text
ANALYZING  -> Running
REVIEW     -> Preparing
APPLYING   -> Running
REBUILDING -> Running
RESOLVED   -> Succeeded
FAILED     -> Failed
CANCEL     -> Cancelled
```

应用重启后，Round 44 的事务恢复机制仍然负责文件安全；Round 45 增加 Session 层恢复/登记，因此 Session Registry、Event Journal、Session Graph 能看到 BuildFix 生命周期。

## Safety

AI 仍然不能直接执行 shell、Gradle、ADB 或直接修改工作区。只有经过人工 Accept 的 `FileChange` 才进入 `GenerationTransaction.commit()`。
