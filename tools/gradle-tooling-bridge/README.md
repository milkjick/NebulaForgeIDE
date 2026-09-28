# NebulaForge Gradle Tooling API Bridge

This is the Termux-userland side of the Gradle bridge required by the NebulaForge v2 design.

Build with a host JDK/Gradle:

```sh
gradle jar
```

The resulting `build/libs/gradle-tooling-bridge.jar` is installed by the IDE into:

`$APP_FILES/runtime/tools/gradle-tooling-bridge.jar`

The Android process never loads `org.gradle.tooling.*`; it launches this worker through the
embedded PTY runtime and consumes `NEBULA_EVENT` records.
