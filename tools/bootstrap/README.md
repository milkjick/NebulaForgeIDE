# NebulaForge embedded Termux bootstrap

The app uses the official Termux bootstrap format for runtime compatibility, but a production fork should build a custom bootstrap with `TERMUX_APP_PACKAGE=com.nebulaforge.app`. Termux maintainers document `build-bootstraps.sh` specifically for forked apps.

Current runtime release: `2026.09.20-r1+apt.android-7`. The official release publishes aarch64, arm, i686 and x86_64 archives. The installer resolves the GitHub asset digest when available, downloads with HTTP Range resume, extracts into a staging directory, verifies `bin/sh`, then atomically commits the userland.

Do not place a downloaded bootstrap ZIP into the source tree and call it a completed custom fork: the custom-package build must be produced from the Termux package sources.
