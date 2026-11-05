# Launcher Position Fixer

Standalone pure-Java LSPosed module for **Oplus Launcher** (`com.android.launcher`) on OxygenOS 15 / OnePlus.

## Problem

Ice Box (root mode) freezes apps with:

```text
IPackageManager.setApplicationEnabledSetting(pkg, 3 /* DISABLED_USER */, ...)
```

and unfreezes with:

```text
IPackageManager.setApplicationEnabledSetting(pkg, 1 /* ENABLED */, ...)
```

After unfreeze the launcher icon may disappear or move. This module restores the exact previous position.

## What it does

- Hooks **only** the launcher process (`com.android.launcher`).
- On `onPackagesUnavailable` → snapshots matching favorites rows (package + component + profileId).
- On `onPackagesAvailable` / `onPackageChanged` / `onPackageAdded` → restores position (or recreates the item if it was removed).
- Saves snapshots in the **module’s own SharedPreferences** (never touches launcher private storage).
- Updates only the changed package’s rows.
- Never clears launcher data, never deletes databases, never force-stops the launcher.
- Does not modify Ice Box behaviour and does not re-enable packages itself.

## Scope (important)

In LSPosed Manager enable the module and scope it **only** to:

```text
com.android.launcher
```

Do **not** scope system_server, SystemUI, Ice Box, or any other app.

## Build

```sh
# Requires Android SDK + JDK 17
gradle :app:assembleRelease
```

APK location:

```text
app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug keystore so it installs without a custom keystore.

## Install

1. Install the APK.
2. Open LSPosed → Modules → enable **Launcher Position Fixer**.
3. Scope → check only `com.android.launcher`.
4. Reboot (or force-stop the launcher once).

## Test

1. Place a test app on a known home-screen cell (or inside a folder).
2. Freeze it with Ice Box (root mode).
3. Unfreeze it.
4. The icon must return to the exact same page / folder / cell.

Logs are tagged `LauncherPositionFixer`.

## Safety

- Fail-closed: if the favorites provider URI or schema is unknown, the module does nothing.
- All heavy work runs on a background executor.
- Debounced restores to avoid racing rapid package callbacks.
- Never rewrites the whole favorites table.

## Version

0.2.0 – rewritten for Oplus Launcher 15.8.x / OxygenOS 15.
