# ShizukuPlus Architecture & Core Features

This reference guide documents the core features, API extensions, and architectural modifications in ShizukuPlus.

---

## 🚀 Unified Privilege Provider

ShizukuPlus combines three execution environments into a single privileged interface:
1. **Root (su)**: Leverages `libsu` to run commands with full system rights.
2. **ADB Shell**: Operates under the system Shell user credentials (`uid 2000`).
3. **Dhizuku (Device Owner)**: Shares the system `DevicePolicyManager` binder with authorized applications, allowing device owner administrative actions without requiring root access.

---

## 🎨 OneUI 8+ & Android 16+ Theming Fix

The **System Theming Bridge (Overlay Manager Plus)** resolves compatibility issues on modern Android versions (Android 16+ and Samsung OneUI 8+):
- **Mechanism**: Utilizes stable `OverlayManagerTransaction` APIs via a privileged system service connection.
- **Goal**: Allows rootless customization engines (such as Hex Installer) to dynamically apply themes and resource overlays.

---

## 🔌 Plus API Suite

ShizukuPlus provides exclusive system interfaces via its custom binder proxy system:

- **AICore+ Automation Bridge**: A privileged accessibility proxy that dumps XML window hierarchies and simulates physical taps/swipes.
- **AVF (Android Virtualization Framework) Manager**: Configures and boots isolated Linux/Microdroid VMs with GPU acceleration.
- **Privileged Storage Proxy**: Bypasses storage sandbox restrictions to allow file access under protected directories (`/data/data/` or `/data/app/`).
- **Device Identity Bridge**: Spoofs system properties (such as product, model, manufacturer) to project hardware signatures of modern flagships.
- **Deep Process Control**: Accesses advanced `ActivityManager` API endpoints to place apps in specific standby buckets or perform deep app terminations.
- **Network & DNS Governor**: Directly manages private DNS configurations and routes traffic using local `iptables` rules.

---

## 🛡️ Watchdog & Process Resilience

Three independent recovery layers, each catching a different failure mode:

1. **`WatchdogService`** — in-process, listens to `ShizukuStateMachine`'s state flow, restarts on `CRASHED` with exponential backoff (5s → 5min cap). Fastest recovery, but useless if the whole process is dead.
2. **`WatchdogWorker`** (WorkManager, every 2h) — restarts `WatchdogService` if just that service was killed but the app process survived.
3. **`WatchdogAlarmReceiver`** (`AlarmManager`, every 15min) — the only layer that survives a *full process kill*, since `AlarmManager` callbacks are dispatched by `system_server`, which can cold-start a fresh process. Needed because some OEM battery managers (Samsung One UI's "Sleeping apps" is the known case) freeze/kill the entire manager process on screen lock, not just a service — a dead process can't detect its own death. Requires `SCHEDULE_EXACT_ALARM` (runtime-revocable); falls back to an inexact wake if unavailable. This is a mitigation bounded by the alarm interval, not a complete fix for aggressive OEM freezers.

See the [Permissions wiki page](https://github.com/thejaustin/ShizukuPlus/wiki/Permissions) for the full permission rationale and the [Service Connection wiki page](https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#watchdog) for user-facing detail.

---

## 🎨 Adaptive Icon Safe Zone (verification gotcha)

`ic_launcher_foreground.xml` / `ic_launcher_background.png` / `ic_monochrome.xml` render inside a 108dp canvas, but OEM launcher masks and notification-icon compositors only guarantee a **~66dp circle centered in that canvas** (roughly the 21–87dp range on both axes) survives. Content outside it — a badge near a corner, an eye near an edge — gets clipped hard on real devices, sometimes down to an invisible sliver.

**A flat, unmasked composite preview (e.g. `PIL.Image.alpha_composite` with no mask) will not catch this.** It looks perfect in the preview and breaks on-device. Confirmed the hard way: the app icon's plus badge was placed to match a flat, non-adaptive source image pixel-for-pixel, verified only with an unmasked composite, and shipped almost entirely invisible on a real Samsung One UI notification icon.

Before shipping any adaptive-icon content change, render it through an actual circular-mask simulation (crop to a circle of radius `33/108 * canvas_px` centered on the canvas) and check what survives — or verify on a real device. Full-bleed *background* art (a gradient, a large illustration extending to the edges) is fine to let bleed past the safe zone; small *foreground* content (a badge, an icon glyph) is not.
