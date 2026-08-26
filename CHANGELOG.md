# Changelog

All notable changes to ShizukuPlus are documented here.

## [Unreleased]

### 🐛 Bug Fixes

#### Server / Service
- **Re-verified the shell/rish consent re-implementation (`32382e89`) across every client type** — rish/shell, root/su, normal app clients, and Dhizuku clients each go through their own already-uid-verified path; no remaining spoofing or persistence gap found. Fixes "Allow always" not persisting ([#420](https://github.com/thejaustin/ShizukuPlus/issues/420)) and the underlying re-implementation request ([#416](https://github.com/thejaustin/ShizukuPlus/issues/416)).
- Fixed a stale code comment in `ShizukuService.checkCallerPermission()` still describing the deleted notification-based shell-consent flow.

#### Manager App (UI)
- **Fixed authorized-apps count briefly showing "0" on cold start** — `HomeState.grantedAppCount` now starts as `null` (not loaded) instead of `0`, so the home screen shows a loading state instead of a wrong count before the real value arrives. ([#424](https://github.com/thejaustin/ShizukuPlus/issues/424))
- **Fixed Watchdog unable to recover from a full process freeze** (Samsung One UI "Sleeping apps" and similar OEM freezers kill the entire manager process on screen lock, taking Watchdog down with it) — added an external `AlarmManager`-based re-arm (`WatchdogAlarmReceiver`, every 15 min) that's dispatched by the system rather than anything inside the frozen process, so it can restart the service even after a full process kill. Mitigation, not a complete fix — see the [Watchdog wiki section](https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#watchdog). ([#415](https://github.com/thejaustin/ShizukuPlus/issues/415), [#417](https://github.com/thejaustin/ShizukuPlus/issues/417))
- **SU bridge redeploy now also triggers whenever the privileged service starts**, not only on app self-update — covers devices where the service wasn't running yet at update time (Xiaomi/HyperOS, Samsung One UI). Follow-up to [#423](https://github.com/thejaustin/ShizukuPlus/issues/423) (`9dba4bd3`).
- **Compat Hub install failures now show a specific reason** for insufficient storage and unsupported CPU architecture, on top of the existing signing-conflict detection. Follow-up to [#412](https://github.com/thejaustin/ShizukuPlus/issues/412).
- **Update download failure notifications now distinguish cause** (insufficient storage, interrupted/unresumable transfer, network/HTTP error) instead of one generic "download failed" message. Follow-up to [#414](https://github.com/thejaustin/ShizukuPlus/issues/414).
- Fixed two dead in-app help links pointing at wiki pages that no longer exist (`wiki/Setup`, `wiki/Supported-apps`) — now point at real pages/sections.

### ✨ Enhancements

#### UI / UX
- **App icon plus badge repositioned** to match the intended design and separated back out into its own semi-transparent overlay layer (was previously baked into the flattened artwork at the wrong position).
- **Themed Icons (Material You) now scoped to just the plus badge** rather than the whole icon — the monochrome layer Android re-tints for Themed Icons no longer includes the cat/hexagon, since the platform re-tints the entire monochrome layer as one flat color and there's no way to theme only part of it.
- **New onboarding step**: "Themed app icon" toggle (defaults on) with a direct link to your launcher's icon-theming settings.
- **New wiki page: [Permissions](https://github.com/thejaustin/ShizukuPlus/wiki/Permissions)** — every permission ShizukuX requests, mapped to the feature it powers and why.

## [v13.6.0.r2287]

### 🐛 Bug Fixes

#### Manager App (UI)
- **Fixed a launch crash on some OEM builds** (observed on Samsung OneUI 3.1/Android 11): notification icons that used a theme-attribute tint (`android:tint="?attr/..."`) could fail to render in the system's own theme context, throwing `RemoteServiceException: Couldn't create icon StatusBarIcon` and crashing the app outright. Found and fixed 4 affected icons (`AutomationService`'s foreground notification plus 3 others reused across update/wireless-ADB notifications). Added `scripts/dev/check-notification-icons.sh` (wired into `scripts/dev/lint.sh`) and a JUnit regression test so this class of bug can't silently reappear. ([#422](https://github.com/thejaustin/ShizukuPlus/issues/422))
- **Fixed Update channel: Dev/Beta channel silently tracking the same release as Stable.** This is a regression of the earlier r2248-era fix below — `checkViaApi()` picked the newest release *by creation date* instead of by its `prerelease` flag, so once a stable release was cut, the dev/beta channel converged on it too. Also fixed the underlying CI bug: `gh release create` computed `IS_PRERELEASE` for the build flavor but never passed `--prerelease` to the release itself, so every published release was `prerelease: false` regardless of channel. ([#407](https://github.com/thejaustin/ShizukuPlus/issues/407))
- **SU Bridge self-test now shows the actual deploy failure reason** (exit code/stderr) instead of a generic "could not deploy" message — the detail was already being captured to logcat but never reached the dialog users actually see/report. ([#402](https://github.com/thejaustin/ShizukuPlus/issues/402))

## [Unreleased / Build r2248+]

### 🐛 Bug Fixes

#### Server / Service
- Fixed `attachApplication` (binder code 17) dead-code regression that caused ALL API v13+ clients to fail. Affected: Morphe, InstallerX Revived, Droid-ify, ObtainX, Obtainium, MT Manager APK install, Termux `rish`. ([#406](https://github.com/thejaustin/ShizukuPlus/issues/406), [#394](https://github.com/thejaustin/ShizukuPlus/issues/394), [#392](https://github.com/thejaustin/ShizukuPlus/issues/392), [#391](https://github.com/thejaustin/ShizukuPlus/issues/391), [#387](https://github.com/thejaustin/ShizukuPlus/issues/387), [#386](https://github.com/thejaustin/ShizukuPlus/issues/386))
- Fixed shell consent (`rish`/`adb`) not persisting after `Allow always` tap. Callers are now identified by UID via `Os.getuid()` fallback even when PM lookup fails. ([#391](https://github.com/thejaustin/ShizukuPlus/issues/391), [#398](https://github.com/thejaustin/ShizukuPlus/issues/398))
- Fixed SU bridge self-test failing due to writing to an already-open file descriptor. ([#402](https://github.com/thejaustin/ShizukuPlus/issues/402))
- Fixed Live Activity notification reappearing after disabling the toggle. ([#400](https://github.com/thejaustin/ShizukuPlus/issues/400))
- SU bridge: removed the `id()`/`whoami()` shell function mocks from the dynamic Magisk-mode injection header — they were shadowing the real coreutils binaries for every `sh -c` call routed through the bridge, not just root-check probes.
- Fixed `newProcess()` dropping the entire boot environment (`BOOTCLASSPATH`, `ANDROID_DATA`, `ANDROID_ROOT`, etc.) when Magisk mocking is enabled and the caller passes a `null` env — it now seeds from `System.getenv()` before appending the Magisk vars, instead of replacing the env outright. Fixes spawned `app_process` children dying instantly with `ANDROID_DATA environment variable unset`. ([#410](https://github.com/thejaustin/ShizukuPlus/issues/410))

#### Manager App (UI)
- **Eliminated black screen flash** on every theme/accent/icon/blur change — settings screen now recomposes in-place without `Activity.recreate()`. ([#407](https://github.com/thejaustin/ShizukuPlus/issues/407) adjacent)
- Fixed Update channel: Dev/Beta channel now correctly fetches pre-releases and selects the right APK (Plus vs Drop-In). ([#407](https://github.com/thejaustin/ShizukuPlus/issues/407))
- Fixed bottom navigation bar overlapping: Feature Hub, Settings list, Activity Log, and App search bar. 
- Fixed Dhizuku Mode toggle not persisting state immediately on change.
- **Fixed "Shizuku is running" status icon** rendering as a garbled server-rack-and-checkmark mashup — reverted to the clean circle-checkmark glyph.
- **Fixed One-handed mode squeezing all content into the bottom-right corner** — the scale-down pivot was anchored at the corner instead of bottom-center, the actual Samsung OneUI behavior. Affected both Home and Settings.
- **Fixed update downloads appearing to hang instead of prompting to install** — the silent-install attempt (via root/Shizuku) had no timeout, so a stuck root prompt or dead Shizuku binder wedged the coroutine forever with no fallback to the system installer. Now falls back after 5s.
- Fixed update download progress notification vibrating/alerting on every 500ms progress tick instead of only once.
- **Manual crash reports were shipping with empty log sections** (`[#405](https://github.com/thejaustin/ShizukuPlus/issues/405)`, `[#397](https://github.com/thejaustin/ShizukuPlus/issues/397)`, `[#317](https://github.com/thejaustin/ShizukuPlus/issues/317)`) — the logcat tail filter (`*:S AndroidRuntime:E ...`) silenced everything by default and only allow-listed tags that didn't match real crash output. Now captures the last 300 unfiltered lines of the app's own logcat.
- Fixed `showStartAdbHome()` defaulting to `true` in Java while its XML preference (`show_start_adb_home`) defaults to `false` — first-run users saw the "Start ADB" home shortcut despite the setting screen showing it off.

### ✨ Enhancements

#### UI / UX  
- **Redesigned App Icon**: Added a "Plus" badge in the upper right of the app icon across the board. The Plus integrates directly into the original hexagon geometry.
- **Themed Icon Support**: Implemented a fully vector Material You monochrome icon, enabling the app icon (including the new Plus badge) to respond flawlessly to expressive theme color changes.
- **Authentic Samsung OneUI one-handed mode**: Content scales to 75% with bottom-center pivot anchor (matching Samsung's actual behavior) with smooth spring animation. Works on all devices.
- **Samsung OneUI Settings header**: LargeTopAppBar uses ExtraBold (W800) title at 28sp with −0.5sp letter-spacing and transparent container — matching OneUI 6/7 Settings exactly.
- **App search bar**: Modernized with Material 3 pill shape (28dp corner radius).
- Shell consent notification now shows the app package name instead of "cannot be identified" when PM lookup fails.

## [v13.6.0.r2239]

### Bug Fixes
- Power-save whitelist re-applied on each `bindApplication` retry.
- Watchdog scope clarified; RNDIS/Ethernet transport monitored.
- Binder delivery retried on frozen-app failure with actionable UI feedback.

## [v13.6.0.r2222]

### Bug Fixes  
- Shell caller now correctly identified; package name shown in consent notification.

## [v13.6.0 / r2215]

Initial public release of ShizukuX.
