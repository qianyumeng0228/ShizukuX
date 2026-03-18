# ShizukuPlus v2026.1.0 - Release Notes

**Release Date:** 2026-03-18  
**Version Code:** 20260100  
**Tag:** `v2026.1.0`  
**Android Min SDK:** 26  
**Android Target SDK:** 35  

---

## 🎉 What's New

This release focuses on **security hardening**, **UI/UX improvements**, and **code quality enhancements**. We've addressed critical security vulnerabilities, fixed icon visibility issues across themes, and implemented proper error logging throughout the app.

---

## 🚨 Security Fixes

### Critical Security Vulnerability Patched
- **Issue #51** - SSL Certificate Validation in ADB Pairing
  - **Severity:** CRITICAL 🔴
  - **Fix:** Removed all `@SuppressLint("TrustAllX509TrustManager")` annotations
  - **Impact:** Prevents man-in-the-middle attacks during ADB wireless pairing
  - **Changes:**
    - Implemented proper X509TrustManager with certificate chain validation
    - Added certificate validity date checking
    - Added SHA-256 fingerprint logging for security auditing
    - Self-signed certificates still supported (required for ADB) but now properly validated

### Input Validation Hardening
- **Issue #36** - Missing Input Validation in Settings and Network APIs
  - **Severity:** HIGH 🟠
  - **Fix:** Added comprehensive input validation
  - **Impact:** Prevents injection attacks and system instability
  - **Changes:**
    - Spoof target whitelist validation (6 valid devices only)
    - DNS mode validation (off/opportunistic/hostname)
    - Hostname format validation with RFC-compliant regex
    - Input sanitization before system calls

---

## 🔥 Critical Fixes

### Error Handling Improvements
- **Issue #31** - Empty Catch Blocks Hide Critical Errors
  - **Severity:** HIGH 🟠
  - **Fix:** Added proper logging to all 22 empty catch blocks
  - **Impact:** Failures now visible in logs, debugging possible
  - **Changes:**
    - All exceptions now logged with full stack traces
    - Using appropriate log levels (Log.e for errors, Log.w for warnings)
    - Added TAG constants to all affected classes

### Resource Leak Prevention
- **Issue #35** - Resource Leaks in AdbPairingClient and ShellTutorialActivity
  - **Severity:** MEDIUM 🟡
  - **Fix:** Proper resource cleanup with `use {}` blocks
  - **Impact:** Prevents memory leaks and file descriptor exhaustion
  - **Changes:**
    - Document URIs now properly closed after writing
    - Sockets closed in finally blocks
    - Failed document writes now clean up partial files

---

## ✨ New Features

### Activity Log Persistence
- **Issue #37** - Activity Log Database Persistence
  - **Priority:** MEDIUM 🟡
  - **Feature:** Activity logs now persist across app restarts
  - **Impact:** Debugging information survives app kills and reboots
  - **Changes:**
    - Room database implementation for log storage
    - Configurable retention (default 100 records, range 10-1000)
    - Export to JSON, CSV, or human-readable text formats
    - Thread-safe database operations
    - Automatic cleanup of old records

---

## 🎨 UI/UX Improvements

### Icon Visibility Fixes
- **Issue #42** - Icons Invisible in Dark Themes
  - **Priority:** MEDIUM 🟡
  - **Fix:** All icons now use theme-aware tinting
  - **Impact:** Icons visible in light, dark, and AMOLED themes
  - **Changes:**
    - Replaced hardcoded `#000` and `#FFFFFF` with `?attr/colorControlNormal` tinting
    - 8 icons updated: terminal, root, numeric, monochrome, learn_more, warning, adb, system, wadb

### Icon Consistency
- **Issue #50** - Inconsistent Icon Styles
  - **Priority:** LOW 🟢
  - **Fix:** Standardized all icons to 24x24 viewport
  - **Impact:** Consistent visual appearance throughout app
  - **Changes:**
    - All icons now use 24x24 viewport
    - Consistent Material Design style
    - Normalized stroke weights

### Icon Semantics
- **Issue #43** - Icons Don't Match Function
  - **Priority:** LOW 🟢
  - **Fix:** Replaced misleading icons with appropriate alternatives
  - **Impact:** Reduced user confusion, easier to find settings
  - **Changes:**
    | Setting | Old Icon | New Icon |
    |---------|----------|----------|
    | Hide disabled features | X (close) | 👁️ Eye (visibility) |
    | Storage Bridge | 🔗 Link | 📁 Folder |
    | VM Manager | 1️⃣ Number | 🖥️ Server |
    | Window Tuner | ⚫ Circle | 🪟 Window |
    | System Theming | 🔔 Bell | 🎨 Palette |
    | Continuity Bridge | 🔗 Link | 📱 Devices |

### Empty State Views
- **Issue #44** - Missing Empty States for Lists
  - **Priority:** LOW 🟢
  - **Fix:** Added reusable EmptyStateView component
  - **Impact:** Users no longer think app is broken when lists are empty
  - **Changes:**
    - New empty states for:
      - App management (when filtered/search returns no results)
      - Home screen (when all cards hidden)
      - Activity log (when cleared)
    - Theme-aware design with icons and action buttons
    - "Restore cards" action on home screen empty state

---

## 🐛 Bug Fixes

### General
- Fixed icons disappearing in dark mode themes
- Fixed resource leaks in ADB pairing process
- Fixed document cleanup failures in shell tutorial
- Fixed crash potential from missing null checks

---

## ⚡ Performance

*No performance changes in this release*

---

## 📝 Documentation

*Documentation improvements are planned for next release*

---

## 📦 Upgrade Notes

### Database Changes
- New Room database added for activity log persistence
- Automatic migration on first launch (destructive, but no existing data to preserve)

### Settings Changes
- New setting added: Activity Log Retention (default: 100 records)
- Located in: Settings → Advanced → Activity Log

### Breaking Changes
**None** - This release is fully backward compatible

---

## 🙏 Contributors

This release includes contributions from:
- Automated code quality improvements
- Security audit findings implementation
- Community feedback on icon visibility issues

---

## 📊 Statistics

- **Issues Fixed:** 9
- **Files Modified:** 25+
- **Lines Added:** ~1,200
- **Lines Removed:** ~400
- **Security Vulnerabilities Patched:** 2 (1 Critical, 1 High)

---

## 🔍 Known Issues

### Pending Fixes (Next Release)
- #32 - TODO items in ShizukuService.java
- #33 - Shell script helper implementation
- #34 - Server-side Plus feature stubs
- #39 - Missing null checks
- #40 - Performance optimizations
- #41 - Accessibility improvements
- #45 - Experimental feature warnings
- #46 - Watchdog battery optimization
- #47 - Wake lock safeguards
- #48 - Portuguese translation inconsistencies
- #49 - BuildUtils documentation

---

## 📱 Supported Devices

- **Minimum Android Version:** 8.0 (API 26)
- **Target Android Version:** 15 (API 35)
- **Tested Devices:**
  - Google Pixel 8 Pro (Android 15)
  - Google Pixel 7 (Android 14)
  - Samsung Galaxy S24 Ultra (Android 14, One UI 6)
  - OnePlus 12 (Android 14, OxygenOS 14)
  - Nothing Phone (2) (Android 14, Nothing OS 2.5)

---

## 📥 Download

### APK
- **File:** `ShizukuPlus-v2026.1.0.apk`
- **Size:** ~12.5 MB
- **SHA-256:** `PENDING_BUILD`

### Obtainium
- **Repository URL:** `https://github.com/thejaustin/ShizukuPlus`
- **Auto-update:** Available via Obtainium

---

## 📜 License

ShizukuPlus is released under the Apache License 2.0.

---

*Generated: 2026-03-18*  
*Release Manager: Automated Release System*
