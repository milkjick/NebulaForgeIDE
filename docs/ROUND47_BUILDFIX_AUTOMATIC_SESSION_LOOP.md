# Round 47 — BuildFix automatic Session loop

## Scope
Round 47 extends the Round 45/46 BuildFix Session architecture into a bounded automatic analysis loop.

## Execution chain
Build #1 -> BuildFix #1 -> reviewed Apply -> Rebuild #2 -> BuildFix #2 -> reviewed Apply -> Rebuild #3.

## Safety
- AI never writes files directly.
- Every generated proposal still enters REVIEW before commit.
- Each round is linked to its source Build Session and rebuild Build Session.
- Rebuild diagnostics are read only from the rebuild session.
- A deterministic diagnostic signature detects no-progress loops.
- `maxRounds` remains enforced.
- Task persistence stores the current signature and per-round signature history.

## Behavior
When a reviewed fix is applied and the rebuild still has errors, the next round is automatically analyzed. The resulting proposal is again placed into REVIEW; there is no automatic file application.
