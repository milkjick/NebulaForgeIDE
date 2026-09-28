# Round 47 — BuildFix Session Chain

## Goal
Make repeated Build → Fix → Rebuild cycles explicit, durable, and bounded.

## State
BuildFixCoordinator persists round, maxRounds, source build session, rebuild session, and round history.

## Chain
Build #1 → BuildFix #1 → Rebuild #2 → BuildFix #2 → Rebuild #3.

Each BuildFix session is linked to its source Build session and each rebuild is linked back to the BuildFix session through IdeSessionBus relations.

## Safety
- Human review remains required before every apply.
- SHA-256 optimistic concurrency remains enforced.
- Max rounds defaults to 3.
- Reaching max rounds produces FAILED rather than pretending the issue is resolved.
- No shell/Gradle/ADB execution is delegated to the AI provider.
- Rebuild diagnostics are scoped to the actual rebuild session.

## Recovery
Round metadata and history are persisted in the existing BuildFix task store. REVIEW state remains recoverable after process death; APPLYING/REBUILDING uses the existing transaction recovery path.
