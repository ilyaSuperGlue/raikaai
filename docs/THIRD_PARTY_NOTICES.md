# Third-Party Notices

## Terminal Emulator for Android (vendored under `app/src/main/java/com/termux/terminal/`)

- Source: https://github.com/termux/termux-app/tree/master/terminal-emulator
- Origin: https://github.com/jackpal/Android-Terminal-Emulator
- License: **Apache License 2.0** (per the upstream exception notice — the
  `terminal-emulator` library is Apache-2.0 code used inside the otherwise
  GPLv3 `termux-app` repository).
- Used for: VT100/xterm emulation backing the interactive Terminal tab.
- Local change: `JNI.java` loads our own `raikaai_pty` native library
  instead of Termux's `termux` one (same 4 native method signatures).

## PRoot (bundled binaries in `jniLibs/` + `assets/container/`)

- Source: Termux package builds of https://github.com/termux/proot
  (upstream project: https://proot-me.github.io/)
- License: **GNU GPL v2 or later** (runs as a separate process; it does not
  link into RaikaAI code). See `app/src/main/assets/container/ATTRIBUTION.md`.
