# Round 33 — Run State Machine / Device & Port Execution

## Scope

This round turns persisted Run Configuration data into a guarded execution lifecycle. It does not introduce a second process executor: Android, Flutter and Web continue to use the existing embedded Way-B PTY path.

## Implemented

- `RunConfigurationValidator`: validates project root, detected project type, supported mode, environment variable names, port range and working directory before process launch.
- `RunPortCoordinator`: checks configured ports at launch time and selects an available replacement when necessary; the persisted configuration is not silently mutated.
- `RunExecutionStateMachine`: explicit RUN/STOP/RESTART action policy derived from `SessionState`.
- `UnifiedRunController`: validates and resolves a configuration before dispatching to Android or StackRunController.
- Android `RunSessionManager`: accepts an optional preferred device serial and fails explicitly when that requested device is unavailable/unauthorized.
- Android `RealAndroidBuildRunController`: forwards the selected device serial into the ADB install/launch lifecycle.
- Run Tool Window: stop and restart actions are connected to the shared controller.

## Execution model

```text
RunConfigurationSpec
        |
        v
RunConfigurationValidator
        |
        v
RunPortCoordinator
        |
        +---- Android -> Gradle -> APK -> ADB(preferred device) -> Logcat
        |
        +---- Flutter -> flutter run -> PTY
        |
        +---- Web -> npm run -> PTY
        |
        v
IdeSessionBus -> RunCenterStore -> Run Tool Window
```

## Important boundaries

- No Android `ProcessBuilder` was introduced.
- A stopped/cancelled process is not resurrected after IDE restart.
- Port fallback is runtime-only; the saved configuration is not silently rewritten.
- Device selection is validated against actual `adb devices`/Flutter discovery before use where the corresponding stack supports it.
- This round does not claim APK/device execution success because the current source package still lacks the Gradle wrapper JAR required for a full Android Gradle validation in this environment.
