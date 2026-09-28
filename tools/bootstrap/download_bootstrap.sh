#!/usr/bin/env sh
set -eu
VERSION="2026.09.20-r1+apt.android-7"
ARCH="${1:-aarch64}"
OUT="bootstrap-${ARCH}.zip"
URL="https://github.com/termux/termux-packages/releases/download/bootstrap-${VERSION}/bootstrap-${ARCH}.zip"
MIRROR="https://sourceforge.net/projects/termux-packages.mirror/files/bootstrap-${VERSION}/bootstrap-${ARCH}.zip/download"
printf '%s\n' "Downloading $ARCH bootstrap..."
if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -C - -o "$OUT" "$MIRROR" || curl -fL --retry 3 -C - -o "$OUT" "$URL"; else wget -c -O "$OUT" "$MIRROR" || wget -c -O "$OUT" "$URL"; fi
unzip -t "$OUT" >/dev/null
printf '%s\n' "OK: $OUT"
sha256sum "$OUT"
