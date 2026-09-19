<div align="center">

# ShizukuX

**English** | [简体中文](./README.zh-CN.md)

**Android privileged process manager for advanced users**

ShizukuX is a community-enhanced fork of [Shizuku](https://github.com/RikkaApps/Shizuku), built on [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) and [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus). It inherits all capabilities of the original project while adding localization and experience refinements for a broader audience.

Shizuku lets ordinary apps call system-level APIs directly through a privileged process started via **ADB / Root**. ShizukuX stays 100% compatible while adding practical capabilities for power users and developers.

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)

> **Heritage note**: ShizukuX is the renamed continuation of ShizukuPlus (qianyumeng0228) with Chinese localization; ShizukuPlus itself is a fork of thejaustin/ShizukuPlus and thedjchi/Shizuku, ultimately tracing back to RikkaApps' Shizuku project. ShizukuX is an independently maintained community fork and has **no affiliation, endorsement, or partnership** with **RikkaApps/Shizuku, thedjchi/Shizuku, thejaustin/ShizukuPlus, or their maintainers**. All upstream copyright and attribution notices are fully preserved (see [LICENSE](LICENSE), [NOTICE](NOTICE), [CHANGES.md](CHANGES.md)).

</div>

## ⬇️ Download

Grab the latest release from [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases). See each release's notes for details.

## ✨ Core Features

*   **Universal permission provider**: a single interface unifying **Root**, **ADB Shell**, and **Dhizuku (Device Owner)** as permission sources.
*   **OneUI 8+ theming fix**: keeps theme engines such as Hex Installer and Substratum working on Android 16/17 and OneUI 8+.
*   **Dhizuku mode**: shares the system Device Owner Binder with any app holding Shizuku permission — configurable via ADB, no Root required.
*   **Customizable gestures**: swipe left, swipe right, long press and more, configurable per app.
*   **In-app changelog**: view release highlights right after an update without leaving the app.
*   **Batch management**: select multiple apps to grant/revoke permissions or hide them in one tap.
*   **Activity log**: real-time audit of API calls and `su` bridge commands, with app icons and live refresh.
*   **Root compatibility hub**: a dashboard for legacy Root apps with fine-grained module controls (Magisk spoofing, auto-grant, file interception, etc.).
*   **Universal SU automation**: one-tap "magic settings" that points all installed Root apps at the ShizukuX SU bridge.
*   **Service diagnostics**: diagnose and fix service startup issues (including Samsung Auto Blocker).
*   **Built-in feature guidance**: every enhancement ships an info icon explaining its purpose in plain language.
*   **Quick Settings tile**: view and toggle service status from the notification shade.

## 🚀 Plus API Enhancements

ShizukuX provides exclusive system interfaces that the original Shizuku **does not have**, for advanced automation and tooling:

*   **AICore+ automation bridge**: Root-free privileged UI automation for AI tools (hierarchy dump, tap/swipe). ([intro commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **AVF (Virtual Machine) manager**: run isolated Linux/Microdroid VMs with GPU acceleration. ([intro commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Privileged storage proxy**: authenticated access to restricted paths (`/data/data/`, `/data/app/`) for backup and file management. ([intro commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Device spoofing** (*Spoof Device Identity* in Settings): present different device information to the system to bypass model-specific restrictions. ([intro commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44))
*   **Smart bridge** (*AI Core Plus*): privileged NPU scheduling and screen-context intelligence. ([intro commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Window Manager Plus**: force free-form window resizing, manage floating bubble bars and elastic overlays. ([intro commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **System theme bridge** (*Overlay Manager Plus*): Root-free privileged Overlay management for theming (e.g. Hex Installer). ([intro commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Network & DNS governor**: manage private DNS and firewall routing to power Root-free ad blocking. ([intro commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Deep process control** (*Activity Manager Plus*): let process managers kill apps more aggressively and set standby buckets. ([intro commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Continuity bridge**: securely transfer state and tasks between multiple ShizukuX devices. ([intro commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7))

## 🛠️ Backports & Performance

Without touching a single line of code, ShizukuX makes ordinary Shizuku apps faster and more compatible:

*   **Transparent shell interceptor**: routes common `pm`, `am`, and `settings` commands to faster native APIs.
*   **Local ADB proxy**: emulates an ADB server on port 15555 so legacy apps keep working with Shizuku after wireless debugging is off.
*   **SU bridge**: a Shizuku-based `su` alternative for Root-free apps that support custom Root paths.
*   **`plus` command-line tool**: a privileged CLI usable inside `rish`.
*   **Dynamic app database**: syncs app descriptions and suggestions from GitHub in real time to keep the UI current.

## ⚙️ Modular Control

Everything in ShizukuX is optional. Toggle features under the **Plus features** category in Settings:

*   Transparent shell interception
*   Individual Plus APIs (AVF, storage, smart, etc.)
*   Home screen card display
*   Activity log

## 🔌 Third-party App Compatibility

ShizukuX installs under its own package name (`xyz.shizuku.extra.api`) and can coexist with the original Shizuku. Since most Shizuku apps only check for the `moe.shizuku.privileged.api` package name, ShizukuX ships a lightweight **Compat Hub** — a tiny companion app that registers that package name and forwards binder/permission requests to ShizukuX.

**If a third-party app can't detect ShizukuX:**

1. Start the ShizukuX service first (ADB or Root method).
2. On the home screen, use the **Compat Hub** card to install the companion app (it ships inside the APK; installation depends on a running service, so start the service first).
3. Reopen the third-party app — it should now detect Shizuku and receive the service binder.

You can also install the **drop-in variant**, which registers directly as `moe.shizuku.privileged.api` (do not install it alongside the original Shizuku).

## ☑️ System Requirements

**Minimum: Android 7+ · Fully supported up to Android 17 (SDK 37)**

- **Root mode**: requires a rooted device
- **Wireless debugging mode**: Android 11+ and all Android TVs
- **PC ADB mode**: all devices
- **Boot startup**: available in wireless debugging or Root mode only

On **Android 16+**, ShizukuX requests the new local network protection permission to keep wireless debugging discovery and pairing working; on **Android 17**, it transparently handles the hidden API `deviceId` change so authorized apps remain visible and permission grants keep working.

## 📱 Developer Guide
<a name="developer-guide"></a>

Documentation for the exclusive Plus APIs lives in [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) (the `api` submodule of this repository points to it).

## 🙏 Acknowledgements & License

ShizukuX is a community-driven enhanced fork originating from [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) (itself a fork of [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)), continued through [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus). This project has **no affiliation** with the original RikkaApps team.

Thanks to the following upstream contributors and projects whose work made ShizukuX possible:

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — the Shizuku foundation and its elegant API design.
- **[thedjchi](https://github.com/thedjchi)** — intermediate fork with experience improvements, and home of the **Android 17 (SDK 37) compatibility** work that ShizukuX's A17 support was ported from.
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — source of the Android 17 hidden API `deviceId` compatibility (`Android17Compat` / `InstalledPackagesCompat` reflection layer) and local network protection handling.
- **[LandonMoran](https://github.com/LandonMoran)** — ported Nightzuku's Android 17 support into the thedjchi fork and validated it **end-to-end on a real Android 17 device** (pairing, service startup, authorized app list), providing the on-device verification basis for ShizukuX's port.
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — the aShell You codebase that inspired the terminal and shell automation features.
- **[iamr0s](https://github.com/iamr0s)** — Dhizuku for the unified Device Owner permission mode, and AndroidAppProcess for standalone Java process execution.
- **[pascua28](https://github.com/pascua28)** — native Samsung System UID 1000 privilege integration.
- **[kerneldroid](https://github.com/kerneldroid)** — the Nightzuku fork that inspired our Android 16/17 (SDK 37) hidden API resilience (handling `deviceId`) and UI modernization.
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — the concept of system-level UID 1000 privilege beyond normal limits.

### Upstream Projects

| Project | Author | License | Role |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | Foundation privileged process architecture |
| [Shizuku (fork)](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | Intermediate fork with experience improvements; hosts the Android 17 compatibility work ShizukuX adapts |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Source of Android 17 hidden API `deviceId` + local network protection compatibility |
| [Shizuku (fork)](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | Samsung UID 1000 system execution scheme |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 16/17 API resilience and UI modernization |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | ShizukuExt team | Apache 2.0 | System UID privilege concept |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Device Owner binder sharing (Dhizuku mode) |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | Standalone high-privilege Java process wrapper |

### Open Source Libraries

| Library | Author | License |
|---------|--------|---------|
| [AndroidX Jetpack](https://developer.android.com/jetpack) | Google / AOSP | Apache 2.0 |
| [Material Components](https://github.com/material-components/material-components-android) | Google | Apache 2.0 |
| [Material Symbols](https://fonts.google.com/icons) | Google | Apache 2.0 |
| [Kotlin / Coroutines / Serialization](https://github.com/JetBrains/kotlin) | JetBrains | Apache 2.0 |
| [RikkaX Libraries](https://github.com/RikkaApps) (appcompat, material, insets, html, recyclerview, preference, lifecycle, parcelablelist) | Rikka | Apache 2.0 |
| [Hidden API / Refine](https://github.com/RikkaApps/HiddenApiCompat) | Rikka | Apache 2.0 |
| [Mavericks (MvRx)](https://github.com/airbnb/mavericks) | Airbnb | Apache 2.0 |
| [Lottie](https://github.com/airbnb/lottie-android) | Airbnb | Apache 2.0 |
| [Coil](https://github.com/coil-kt/coil) | Coil Contributors | Apache 2.0 |
| [Koin](https://github.com/InsertKoinIO/koin) | Koin Contributors | Apache 2.0 |
| [Timber](https://github.com/JakeWharton/timber) | Jake Wharton | Apache 2.0 |
| [libsu](https://github.com/topjohnwu/libsu) | topjohnwu | Apache 2.0 |
| [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | LSPosed | Apache 2.0 |
| [libcxx](https://github.com/lsposed/libcxx) | LSPosed / LLVM | Apache 2.0 + LLVM Exception |
| [AppIconLoader](https://github.com/zhanghai/AppIconLoader) | Zhang Hai | Apache 2.0 |
| [BoringSSL (NDK)](https://github.com/vvb2060/ndk-boringssl) | vvb2060 / Google | Apache 2.0 / ISC |
| [Gson](https://github.com/google/gson) | Google | Apache 2.0 |
| [LeakCanary](https://github.com/square/leakcanary) | Square | Apache 2.0 |
| [AboutLibraries](https://github.com/mikepenz/AboutLibraries) | Mike Penz | Apache 2.0 |
| [Bouncy Castle](https://www.bouncycastle.org/) | Legion of Bouncy Castle | MIT |
| [Sentry Android SDK](https://github.com/getsentry/sentry-java) | Sentry | MIT |
| [SQLite (C Recovery API / CLI)](https://sqlite.org/) | D. Richard Hipp / SQLite Consortium | Public Domain |

Full license texts for each library: [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) | [NOTICE](NOTICE)

## 📃 License

[Apache 2.0](LICENSE)

### Credits
- Special thanks to **AkayamiShurui42** for proactive security research and stability patches (ref: #239).
- Thanks to **AlexeiCrystal** for pinpointing the MIUI crash and proposing the Compat Hub scheme for legacy apps (#241, #242).
- Thanks to **ddnexus** and **kai-bash** for flagging the Device Owner factory-reset pitfall and Google Backup conflicts (#237).
- Thanks to **Kevinco1** for feedback on Root compatibility app detection (#243).
- Thanks to **aragortsantiago6-beep**, **Scoop2389** (Pixel 9a), and **ConversionRituals** (Xiaomi) for on-device Android 16/17 testing, crash reports, and logs that drove the SDK 37 hidden API and local network protection compatibility fixes (#317, #323).
- Thanks to **gmm96** for multiple rounds of logcat debugging that finally isolated the Cached Apps Freezer binder delivery bug (#371).
- Thanks to **[odorizzioficial](https://github.com/odorizzioficial)** for a complete Brazilian Portuguese translation (#409) and a detailed report on Samsung "Sleeping apps" watchdog freezes (#415).
