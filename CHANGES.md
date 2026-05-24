# Changes from Upstream

This file documents the significant changes made in ShizukuX relative to its
upstream sources, as required by the Apache License 2.0 §4(b).

## Base: thedjchi/Shizuku → ShizukuX

ShizukuX is forked from [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku),
which is itself a fork of [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku).

---

## Manager Application (`manager/`)

### Package & Identity
- Package renamed from `moe.shizuku.privileged.api` to `af.shizuku.plus.api`
- App name changed to "ShizukuX"
- Custom launcher icons and branding
- **Coexistence Fix:** Removed conflicting `moe.shizuku.manager.permission.API_V23` declaration and updated `autoResConfig` namespace to ensure zero-overlap with original Shizuku installations.

### Architecture
- Adopted **Mavericks (MvRx)** MVI architecture for home screen (`HomeViewModel`, `HomeState`)
- Adopted **Koin** dependency injection throughout the manager
- Added **Room** database for persistent activity log storage
- Integrated **Sentry** for crash reporting (manually initialized; `io.sentry.auto-init=false`)
- Added **Coil** for async image loading of app icons

### Home Screen
- Redesigned with a modular card system; cards are user-reorderable via drag-and-drop
- Added `HomeEditMode` for toggling card edit/reorder mode
- Expressive M3 drag animations with haptic feedback
- Predictive-back gesture support for edit mode exit

### Settings
- Full **Material 3 Expressive** theme (`Theme.Material3Expressive`)
- Added **Update Settings** screen with stable/dev channel toggle and in-app APK download
- Added **About** screen with version easter-egg developer unlock
- Added **Developer Options** hidden category revealed via version tap
- `ShizukuSettings.java` extended with many new keys (update channel, watchdog, expressive animations, vector mode, etc.)

### Root Compatibility Hub (`AppContextManager`, `RootCompatHelper`)
- New dashboard listing 60+ root apps with metadata (enhancement hints, root support level)
- Auto-setup: configures supported apps to use the SU Bridge via privileged shell
- Magic Setup: one-tap bulk configuration for all installed root apps
- Shell injection mitigations: `suPath` and `packageName` are now escaped before interpolation into `sed` commands

### Service Doctor (`ServiceDoctorActivity`)
- Diagnostic tool for common Shizuku startup failures
- Samsung-specific checks: Auto Blocker, Background Usage Limits (OneUI 6+)
- Accessibility service detection and deep-link fix buttons

### Watchdog Service (`WatchdogService`)
- Background foreground service that monitors Shizuku state and auto-restarts on crash
- Exponential backoff: 5s → 10s → 20s → … → 5min cap to prevent battery drain
- Crash notification with channel-disable action

### Update System (`UpdateChecker`, `UpdateManager`)
- GitHub Releases API integration for OTA updates
- Stable channel: `/releases/latest`; Dev channel: `/releases?per_page=1` (includes pre-releases)
- In-app APK download with progress notification and install prompt

### ADB Subsystem
- `AdbStarter`: SSL version mismatch detection with guidance dialog
- `SettingsPage`: typed navigation helper for Android settings deep-links (Developer Options, Wireless Debugging, Accessibility, Samsung-specific pages)

### Activity Log
- Real-time log of Shizuku API calls and SU bridge commands
- Persistent storage via Room; entries survive process restarts

### Dhizuku Mode
- `DhizukuProvider`: ContentProvider stub serving the Device Owner binder to apps with Shizuku permission
- Unified privilege provider: Root, ADB, and Device Owner in one interface

### Quick Settings Tile
- `ShizukuTileService`: shows running/stopped state; tap to open the app

### Build
- AGP upgraded to 8.11.1, Kotlin to 2.2.20, JVM target 21
- All modules set to `sourceCompatibility = JavaVersion.VERSION_21`
- Added `aboutlibraries` Gradle plugin for open-source license metadata generation
- Sentry Gradle plugin configured with conditional upload (skipped when auth token absent)
- Release APKs use AAPT2 `--collapse-resource-names` optimization

---

## Server (`server/`)

- Partial Kotlin migration: `ShizukuService.java` (1293 lines) remains primary implementation
- Added `DhizukuProvider` binder shim

## Starter (`starter/`)

- `ServiceStarter.java` replaced with `ServiceStarter.kt`
- JVM target corrected to 21 to match root project Java compatibility

## API (`api/`)

- Package retained as `rikka.shizuku` for drop-in compatibility with existing Shizuku clients
- No breaking changes to public API surface

---

*Last updated: 2026-04-29*
## 2026-05-24: Security & Stability Hardening
- **Ported improvements from community contributions (PR #239):**
    - Implemented **Path Traversal Protection** in StorageProxyImpl using canonical path validation and directory whitelisting.
    - Added **Runtime Tamper Detection** in SecurityGuard to identify Xposed/Frida hooks.
    - Strengthened **DhizukuProvider** security by adding signature-level permissions.
    - **Service Hardening**: Forced Shizuku server classes into primary DEX to resolve ClassNotFoundException on cold start.
    - **Interface Alignment**: Standardized IActivityManagerPlus AIDL and implementation to support package-specific process limits.
    - **Stability**: Added authentication requirement for REQUEST_BINDER and improved error handling for shell-based storage fallbacks.
## 2026-05-24: Security & Stability Hardening
- **Ported improvements from community contributions (PR #239):**
    - Implemented **Path Traversal Protection** in StorageProxyImpl using canonical path validation and directory whitelisting.
    - Added **Runtime Tamper Detection** in SecurityGuard to identify Xposed/Frida hooks.
    - Strengthened **DhizukuProvider** security by adding signature-level permissions.
    - **Service Hardening**: Forced Shizuku server classes into primary DEX to resolve ClassNotFoundException on cold start.
    - **Interface Alignment**: Standardized IActivityManagerPlus AIDL and implementation to support package-specific process limits.
    - **Stability**: Added authentication requirement for REQUEST_BINDER and improved error handling for shell-based storage fallbacks.
