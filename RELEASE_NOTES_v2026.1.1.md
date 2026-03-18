# ShizukuPlus v2026.1.1 - Release Notes

**Release Date:** 2026-03-18  
**Version Code:** 20260101  
**Tag:** `v2026.1.1`  
**Android Min SDK:** 26  
**Android Target SDK:** 35  

---

## 🎉 What's New

This release includes **all fixes from v2026.1.0** plus a critical fix for the Shizuku status display issue. If Shizuku wasn't showing as running in the app, this update fixes that!

---

## 🐛 Critical Bug Fixes

### Status Display Fixed
- **NEW** - Shizuku Status Not Showing When Running
  - **Severity:** CRITICAL 🔴
  - **Fix:** Added initial status load in ViewModel init block
  - **Impact:** Status card now appears immediately when Shizuku is running
  - **Changes:**
    - Call `checkServerStatus()` in `onCreate()` before observer setup
    - Added `reload()` call in `HomeViewModel` init block
    - Improved error handling with try-catch in `load()`
    - Enhanced state listener to handle all state transitions
    - Force refresh status on `onResume()`

---

## 📋 All Changes (Includes v2026.1.0)

### 🔒 Security Fixes

#### Critical Security Vulnerability Patched
- **Issue #51** - SSL Certificate Validation in ADB Pairing
  - **Severity:** CRITICAL 🔴
  - **Fix:** Removed all `@SuppressLint("TrustAllX509TrustManager")` annotations
  - **Impact:** Prevents man-in-the-middle attacks during ADB wireless pairing

#### Input Validation Hardening
- **Issue #36** - Missing Input Validation in Settings and Network APIs
  - **Severity:** HIGH 🟠
  - **Fix:** Added comprehensive input validation

### 🔥 Critical Fixes

#### Error Handling Improvements
- **Issue #31** - Empty Catch Blocks Hide Critical Errors
  - **Severity:** HIGH 🟠
  - **Fix:** Added proper logging to all 22 empty catch blocks

#### Resource Leak Prevention
- **Issue #35** - Resource Leaks in AdbPairingClient and ShellTutorialActivity
  - **Severity:** MEDIUM 🟡
  - **Fix:** Proper resource cleanup with `use {}` blocks

### ✨ New Features

#### Activity Log Persistence
- **Issue #37** - Activity Log Database Persistence
  - **Priority:** MEDIUM 🟡
  - **Feature:** Activity logs now persist across app restarts

### 🎨 UI/UX Improvements

#### Icon Visibility Fixes
- **Issue #42** - Icons Invisible in Dark Themes
  - **Priority:** MEDIUM 🟡
  - **Fix:** All icons now use theme-aware tinting

#### Icon Consistency
- **Issue #50** - Inconsistent Icon Styles
  - **Priority:** LOW 🟢
  - **Fix:** Standardized all icons to 24x24 viewport

#### Icon Semantics
- **Issue #43** - Icons Don't Match Function
  - **Priority:** LOW 🟢
  - **Fix:** Replaced misleading icons with appropriate alternatives

#### Empty State Views
- **Issue #44** - Missing Empty States for Lists
  - **Priority:** LOW 🟢
  - **Fix:** Added reusable EmptyStateView component

---

## 📦 Upgrade Notes

### Database Changes
- New Room database added for activity log persistence
- Automatic migration on first launch

### Settings Changes
- New setting added: Activity Log Retention (default: 100 records)

### Breaking Changes
**None** - This release is fully backward compatible

---

## 🙏 Contributors

This release includes contributions from:
- Automated code quality improvements
- Security audit findings implementation
- Community feedback on icon visibility issues
- **Critical status display fix** from user reports

---

## 📊 Statistics

- **Issues Fixed:** 10 (9 from v2026.1.0 + 1 new)
- **Files Modified:** 27+
- **Lines Added:** ~1,350
- **Lines Removed:** ~450
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
- **File:** `ShizukuPlus-v2026.1.1.apk`
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
