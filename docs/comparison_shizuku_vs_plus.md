# Shizuku vs. ShizukuX Architectural Comparison

ShizukuX is a comprehensive enhancement of the foundational Shizuku project. While maintaining 100% compatibility with existing Shizuku-enabled apps, it introduces a modern, modular architecture designed for the next generation of Android power-user tools.

## 1. Core Privilege Architecture

| Feature | Original Shizuku | ShizukuX |
|---------|------------------|----------|
| **Primary Backend** | Root / ADB Shell | Root / ADB Shell / **Dhizuku (Device Owner)** |
| **Service Process** | `shizuku_server` | `shizuku_plus_server` (Optimized native bridge) |
| **Binder Interface** | Standard IShizuku | Extended IShizukuPlus with granular capability flags |
| **Multi-User** | Basic support | Robust cross-user binder sharing (e.g. Secure Folder) |

### Unified Backend
Unlike the original which relies primarily on `shizuku_server` started via ADB, ShizukuX integrates **Dhizuku Mode**. This allows the ShizukuX manager itself to be set as a **Device Owner**, providing a permanent, rootless anchor for system privileges that survives reboots (on supported configurations).

## 2. The Plus API Ecosystem

ShizukuX goes beyond simple shell execution by exposing stable, version-agnostic bridges to internal Android system services.

### Overlay Manager Plus (OMP)
*   **The Issue:** Android 14+ and OneUI 8+ introduced strict restrictions on the standard `OverlayManager` system service, breaking rootless theming.
*   **The Solution:** ShizukuX uses a proprietary `OverlayManagerTransaction` bridge that allows themes (Substratum, Hex Installer) to apply overlays safely and persistently without full system root.

### AICore+ Automation Bridge
*   **Capability:** Provides a privileged `AccessibilityService` proxy.
*   **Functions:** 
    *   `dumpHierarchy()`: Exports the full UI tree as XML (faster than `uiautomator`).
    *   `performTap/Swipe()`: Simulates physical input at the kernel/hardware abstraction level.
*   **Use Case:** Enables advanced AI-driven automation (like Tasker + GPT) to interact with any app without root.

### Root Compatibility Hub (SU Bridge)
ShizukuX acts as a translation layer for legacy apps.
*   **SU Wrapper:** Provides a `/system/bin/su` drop-in replacement that routes commands through the Shizuku binder.
*   **Module Mocking:** Can fool apps into thinking Magisk or BusyBox is installed to unlock advanced features in legacy tools.

## 3. Performance & Stability Improvements

### Transparent Shell Interceptor
Original Shizuku apps often run shell commands (e.g., `pm install`). These are slow because they fork a new process.
*   **ShizukuX Optimization:** Intercepts these calls and routes them through direct Java/Native APIs (like `IPackageManager`) inside the existing privileged process.
*   **Result:** Up to 10x faster execution for shell-heavy apps like MacroDroid or terminal emulators.

### Service Doctor
A real-time diagnostic engine that monitors for vendor-specific "app killers" (like Samsung's Auto Blocker or Oppo's Battery Guard) and provides one-tap fixes to keep the Shizuku service alive.

## 4. UI/UX Refinement
*   **Material 3 Expressive:** Uses the latest M3 design language with spring animations and adaptive shapes.
*   **In-App Changelogs:** Direct access to release notes upon update.
*   **Bulk Management:** Industrial-grade permission management for power users with hundreds of apps.

---
*For documentation on integrating the Plus APIs into your own app, see the [ShizukuX-API](https://github.com/thejaustin/ShizukuPlus-API) repository.*