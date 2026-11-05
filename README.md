# Launcher Position Fixer

Standalone pure-Java LSPosed module for the Oplus Launcher package `com.android.launcher`.

## What it does

- Hooks only the launcher process.
- Saves home-screen/folder favorite rows before Ice Box root mode reports packages unavailable.
- Restores `container`, `screen`, `cellX`, `cellY`, `spanX`, `spanY`, `rank`, and profile metadata after the package becomes available.
- Avoids clearing launcher data, deleting the launcher database, or force-stopping the launcher.
- Does not change Ice Box's real package state. Ice Box still uses `DISABLED_USER` during freeze.

## Build

This is an Android Gradle project. The Xposed API is compile-only at `app/libs/xposed-api-82.jar`.

```sh
gradle :app:assembleRelease
```

The generated APK is under `app/build/outputs/apk/release/`.

## Install and scope

1. Install the APK normally.
2. Enable it in LSPosed.
3. Scope it only to `com.android.launcher`.
4. Reboot or restart the launcher process.
5. Test with one non-critical application.

## Test

1. Put a test app on a known home-screen slot.
2. Freeze it with Ice Box in root mode.
3. Unfreeze it.
4. Check that the icon returns to the same page and cell.

The module logs under `LauncherPositionFixer`. If the Oplus build uses a changed provider schema, disable the module and collect the log before changing the provider URI or columns.
