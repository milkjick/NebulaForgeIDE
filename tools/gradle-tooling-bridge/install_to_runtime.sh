#!/system/bin/sh
set -eu
SRC="${1:?usage: install_to_runtime.sh <bridge-jar> <app-files-runtime-dir>}"
DEST="${2:?usage: install_to_runtime.sh <bridge-jar> <app-files-runtime-dir>}"
mkdir -p "$DEST/tools"
cp "$SRC" "$DEST/tools/gradle-tooling-bridge.jar.tmp"
mv -f "$DEST/tools/gradle-tooling-bridge.jar.tmp" "$DEST/tools/gradle-tooling-bridge.jar"
sha256sum "$DEST/tools/gradle-tooling-bridge.jar" > "$DEST/tools/gradle-tooling-bridge.jar.sha256"
echo "installed: $DEST/tools/gradle-tooling-bridge.jar"
