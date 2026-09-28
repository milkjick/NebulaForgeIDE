#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")"/../.. && pwd)"
SPIKE="$ROOT/tools/spike/android-mvp"
RUNTIME_JAR="${NEBULAFORGE_BRIDGE_JAR:-}"
fail(){ echo "[STAGE0][FAIL] $*"; exit 1; }
ok(){ echo "[STAGE0][OK] $*"; }
[[ -f "$SPIKE/settings.gradle" ]] || fail "spike settings.gradle missing"
[[ -f "$SPIKE/app/build.gradle" ]] || fail "spike app/build.gradle missing"
[[ -f "$SPIKE/app/src/main/AndroidManifest.xml" ]] || fail "spike manifest missing"
ok "Android MVP validation project structure present"
[[ -n "$RUNTIME_JAR" ]] || { echo "[STAGE0][INFO] Set NEBULAFORGE_BRIDGE_JAR to run bridge self-test."; exit 0; }
[[ -f "$RUNTIME_JAR" ]] || fail "bridge JAR missing: $RUNTIME_JAR"
JDK_HOME="${JAVA_HOME:-}"
[[ -n "$JDK_HOME" && -x "$JDK_HOME/bin/java" ]] || fail "JAVA_HOME must point to a runnable JDK"
"$JDK_HOME/bin/java" -jar "$RUNTIME_JAR" --self-test
ok "Gradle Tooling API bridge self-test passed"
