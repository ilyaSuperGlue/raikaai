# Development

Requirements: JDK 17+, Android SDK (compileSdk 37 via AGP shim, targetSdk 36),
ARM64 device / emulator (API 26+).

```bash
./gradlew testDebugUnitTest            # unit tests
./gradlew assembleDebug                 # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease -PabiFilter=arm64-v8a   # → app/build/outputs/apk/release/app-arm64-v8a-release.apk
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Without `-PabiFilter`, the build produces all ABIs + universal APK.

## Release signing

`app/release.keystore` is **git-ignored and never committed**. Provide it locally
plus one of:

| Variable / property | Purpose |
|---|---|
| `RAIKAAI_KEYSTORE_STORE_PASSWORD` / `raikaai.keystore.storePassword` | keystore password |
| `RAIKAAI_KEYSTORE_KEY_PASSWORD` / `raikaai.keystore.keyPassword` | key password |
| `RAIKAAI_KEYSTORE_KEY_ALIAS` / `raikaai.keystore.keyAlias` | key alias |

Legacy `LITECHAT_*` / `litechat.*` names are accepted as fallback.

## CI

`.github/workflows/build-apk.yml` builds the arm64-v8a release APK on manual
dispatch (needs the secrets above). Android 37 platform is shimmed from 36 —
see comments in the workflow.

## Project layout

- `app/` — the Android application module.
- `docs/` — user-facing docs (`ARCHITECTURE.md`, `API.md`, `DEVELOPMENT.md`).
- Root `*.md` working notes (`AGENTS.md`, `PLAN.md`, …) are intentionally
  git-ignored; only `README.md` + `docs/` ship publicly.
