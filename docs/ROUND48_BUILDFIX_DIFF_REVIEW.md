# Round 48 — BuildFix Diff Review

This round upgrades BuildFix review from file-level approval to line-oriented Diff Hunk review.

## Flow

BuildFixProposal → DiffReviewEngine → per-file hunks → Accept/Reject → GenerationTransaction.

AI output is still never written directly to the project. The transaction and SHA-256 optimistic concurrency checks remain the final write boundary.

## Safety

- Diff engine is bounded to 20,000 lines; larger files fall back to one whole-file hunk.
- Hunk operations only mutate the in-memory proposal content.
- Actual project writes still occur only through GenerationTransaction after explicit acceptance.
- Existing/new file handling remains compatible with the previous proposal format.

## UI

Each proposed file now exposes:
- hunk count
- expand/collapse
- accept all hunks
- reject all hunks
- individual hunk Accept/Reject
- open file

The obsolete AI placeholder navigation entry was removed; the main AI route now opens the real BuildFix review surface.
