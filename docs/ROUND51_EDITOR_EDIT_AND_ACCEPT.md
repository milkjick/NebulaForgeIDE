# Round 51 — Editor Edit-and-Accept

## Goal

Extend BuildFix Diff Review so the real Sora editor can be used to manually edit an AI proposal before transaction commit.

## Flow

AI Proposal → Diff Review → Open Sora Editor → manual edit → keep as candidate / Edit-and-Accept → GenerationTransaction → SHA-256 check → rebuild

## Safety

`replaceProposalContentFromEditor()` only changes the in-memory/persisted BuildFix proposal state. It does not write the project file. Project mutation still happens only through the existing GenerationTransaction commit path in `applyAndRebuild()`.

## Actions

- `保留编辑`: copy the current Sora editor buffer into the proposal and leave all newly calculated hunks pending.
- `编辑并接受`: copy the current Sora editor buffer into the proposal and mark its newly calculated hunks accepted.
- Existing `接受/拒绝` remains available for individual hunks.

When the editor buffer is accepted, the transaction is rebuilt from the edited proposal, so the manually edited content is what receives the existing SHA-256 concurrency validation and commit.

## Verification boundary

No APK/device validation is claimed when the project wrapper JAR or Android SDK is unavailable.
