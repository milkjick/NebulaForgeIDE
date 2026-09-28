# Round 31 — Unified Run Tool Window / persistent run state

## Scope

This round turns the Round 30 resident Flutter/Web run path into a unified IDE Run tool window. Android, Flutter and Web events are consumed from the same `IdeSessionBus` and presented through a bounded persistent run-state store.

## Implemented

- `RunCenterStore`: one bounded source for Run UI state/output/artifacts/devices.
- Persistent metadata at `files/sessions/run-center.json`.
- Output is capped in memory and persisted to a smaller bounded tail; processes are never resurrected.
- `StackRunController` now publishes `Cancelled` when a resident Flutter/Web run is stopped.
- Application owns one shared `StackRunController` and `RunCenterStore` instead of each screen creating an isolated resident-run controller.
- `RunToolWindowScreen`: session list, state, project, device/artifact, output, stop and restart actions.
- Build Center links to the Run tool window.
- Run window is a route/tool-window-style surface, not a replacement for the core editor.

## Deliberate boundary

The existing Android `RealAndroidBuildRunController` remains the source of Android Build/ADB/Logcat lifecycle. This round does not fake Android process control through the Flutter/Web controller. Android session metadata still arrives through the shared bus.

## Validation boundary

No APK/device validation is claimed when the Gradle wrapper JAR/Android SDK/device are absent. Source/ZIP integrity checks are the validation boundary for this round.
