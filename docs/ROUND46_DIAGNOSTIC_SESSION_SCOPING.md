# Round 46 — Diagnostic Session Scoping

## Goal
BuildFix must analyze the diagnostics produced by the relevant Build session, not stale diagnostics from older builds or unrelated LSP sessions.

## Data flow
`BuildSession -> parseErrors -> session-scoped DiagnosticStore -> Problems -> BuildFixCoordinator -> rebuild BuildSession`.

## Rules
- DiagnosticStore partitions diagnostics by session ID while retaining file-based lookup for editor surfaces.
- Starting/re-evaluating a build clears only that build session's diagnostic partition.
- BuildFix first uses `WorkspaceState.lastBuildSessionId`; if unavailable it falls back to the latest session containing errors.
- After Apply, remaining errors are counted only from the newly created rebuild Build session. LSP diagnostics are not treated as Gradle build failures.
- The old AiPlaceholderScreen was removed because the real BuildFixReviewScreen is now the active workflow.

## Validation boundary
No APK/device validation is claimed when the Gradle wrapper JAR/Android SDK are unavailable.
