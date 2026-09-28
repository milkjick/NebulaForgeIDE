# Round 10 — Embedded Termux Runtime / Bootstrap Lifecycle

## Implemented

- BootstrapRuntime single lifecycle owner.
- Mutex prevents concurrent bootstrap installations.
- GitHub release metadata lookup for the selected bootstrap asset and published SHA-256 digest.
- HTTP Range resume remains supported by BootstrapInstaller.
- Staging extraction directory; incomplete extraction never receives the installed marker.
- Zip-slip protection.
- Permission normalization and SYMLINKS.txt restoration.
- `bin/sh` execution smoke test before commit.
- Atomic-ish directory commit followed by `.bootstrap_installed` marker.
- Runtime self-test before Terminal/Build code treats the environment as READY.
- Reproducible script for building a NebulaForge-specific bootstrap from termux-packages sources.

## Important boundary

The checked-in source does not contain a 30+ MB binary bootstrap archive. The official Termux project publishes current architecture archives separately; its maintainer documentation also distinguishes generated official bootstraps from bootstraps built for forked app package names. A production NebulaForge release should run the custom bootstrap build script and package the resulting archive, or download the verified official asset at first launch.
