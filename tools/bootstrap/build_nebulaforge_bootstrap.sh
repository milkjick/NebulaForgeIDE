#!/usr/bin/env bash
set -euo pipefail

# Build a bootstrap from Termux package sources for the NebulaForge fork.
# Termux maintainers document build-bootstraps.sh for forked apps and require the
# package identity/prefix to be rebuilt when TERMUX_APP_PACKAGE changes.
TERMUX_PACKAGES_DIR="${TERMUX_PACKAGES_DIR:-$PWD/termux-packages}"
ARCH="${1:-aarch64}"
APP_PACKAGE="${TERMUX_APP_PACKAGE:-com.nebulaforge.app}"
BRANCH="${TERMUX_PACKAGES_BRANCH:-infra-improvs}"
REPO="${TERMUX_PACKAGES_REPO:-https://github.com/termux/termux-packages.git}"

if [[ ! -d "$TERMUX_PACKAGES_DIR/.git" ]]; then
  git clone --branch "$BRANCH" --single-branch "$REPO" "$TERMUX_PACKAGES_DIR"
fi

cd "$TERMUX_PACKAGES_DIR"
git fetch --depth=1 origin "$BRANCH"
git checkout -B "$BRANCH" "origin/$BRANCH"

# The upstream properties file assigns TERMUX_APP__PACKAGE_NAME directly, so merely
# exporting TERMUX_APP_PACKAGE is not enough. Patch only the fork-safe package identity
# and data/prefix paths in a disposable working tree.
python3 - "$APP_PACKAGE" <<'PY'
import pathlib, re, sys
pkg = sys.argv[1]
p = pathlib.Path('scripts/properties.sh')
s = p.read_text()
s = re.sub(r'^TERMUX_APP__PACKAGE_NAME="[^"]*"$', f'TERMUX_APP__PACKAGE_NAME="{pkg}"', s, flags=re.M)
s = re.sub(r'^TERMUX_APP__DATA_DIR="[^"]*"$', f'TERMUX_APP__DATA_DIR="/data/data/{pkg}"', s, flags=re.M)
p.write_text(s)
PY

export TERMUX_APP_PACKAGE="$APP_PACKAGE"
export TERMUX_PACKAGE_MANAGER=apt

# A package-name change requires a clean/forced rebuild according to Termux maintainers.
./scripts/run-docker.sh ./clean.sh || ./clean.sh
./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures "$ARCH" \
  --add openssh --add git --add python

OUT="$PWD/bootstrap-$ARCH.zip"
DIST="$PWD/../NebulaForgeIDE/tools/bootstrap/dist"
mkdir -p "$DIST"
cp "$OUT" "$DIST/bootstrap-$ARCH.zip"
sha256sum "$OUT" | tee "$DIST/bootstrap-$ARCH.sha256"
