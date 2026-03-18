# ShizukuPlus - GitHub Issues Tracker

This document contains pre-formatted issues ready to be created on GitHub. Each issue includes title, description, labels, and priority.

---

## 🔴 Critical Priority (P0)

### Issue #1: [SECURITY] SSL Certificate Validation Disabled in AdbKey.kt

**Labels:** `bug`, `security`, `critical`, `good first issue`
**Priority:** P0 - Critical
**Affected File:** `manager/src/main/java/moe/shizuku/manager/adb/AdbKey.kt`

**Description:**
Multiple `@SuppressLint("TrustAllX509TrustManager")` annotations disable SSL certificate validation, creating a critical security vulnerability that could allow man-in-the-middle attacks.

**Code Location:**
```kotlin
// Lines 223-243
@SuppressLint("TrustAllX509TrustManager")
// Trust all certificates - INSECURE
```

**Expected Behavior:**
- Implement proper certificate pinning
- Validate SSL certificates properly
- Remove all TrustAllX509TrustManager suppressions

**Security Impact:**
- Man-in-the-middle attacks possible
- ADB pairing credentials could be intercepted
- Device could be compromised

**Reproduction:**
1. Review AdbKey.kt lines 223-243
2. Observe multiple TrustAllX509TrustManager annotations
3. No certificate validation occurs during ADB pairing

---

### Issue #2: Empty Catch Blocks Hide Critical Errors Throughout Codebase

**Labels:** `bug`, `code-quality`, `error-handling`
**Priority:** P0 - Critical
**Affected Files:** 22 locations across codebase

**Description:**
The codebase contains 22 instances of empty catch blocks that silently swallow exceptions, making debugging impossible and hiding critical failures.

**Known Locations:**
- `server/src/main/java/rikka/shizuku/server/AICorePlusImpl.kt:38`
- `manager/src/main/java/moe/shizuku/manager/management/ToggleAllViewHolder.kt:56`
- `manager/src/main/java/moe/shizuku/manager/settings/RootCompatibilityActivity.kt:327`
- `server/src/main/java/rikka/shizuku/server/ShizukuService.java` (multiple)
- `api/api/src/main/java/rikka/shizuku/ShizukuPlusAPI.java` (12 instances)

**Pattern:**
```kotlin
catch (e: Exception) {}  // Silent failure
catch (ignored: Exception) {}  // No logging
```

**Expected Behavior:**
- All exceptions should be logged at minimum
- Critical errors should be reported to user
- Use Timber or Log for error logging
- Consider rethrowing as RuntimeException for unrecoverable errors

**Impact:**
- Failures occur silently without user notification
- Debugging production issues is nearly impossible
- Data corruption may go undetected

---

## 🟠 High Priority (P1)

### Issue #3: TODO Items in ShizukuService.java Not Implemented

**Labels:** `enhancement`, `technical-debt`, `server`
**Priority:** P1 - High
**Affected File:** `server/src/main/java/rikka/shizuku/server/ShizukuService.java`

**Description:**
Two TODO comments indicate incomplete functionality in core service:

**TODO Items:**
1. Line 833: `// TODO kill user service using` - User services not properly terminated
2. Line 841: `// TODO add runtime permission listener` - Permission changes not monitored

**Expected Behavior:**
- Implement proper user service lifecycle management
- Add runtime permission change listener
- Remove TODO comments after implementation

**Impact:**
- User services may remain running after they should be stopped
- Permission changes not detected without restart
- Resource leaks possible

---

### Issue #4: Shell Script Helper Has No Actual Implementation

**Labels:** `bug`, `cli`, `placeholder`
**Priority:** P1 - High
**Affected File:** `manager/src/main/assets/plus`

**Description:**
The `plus` CLI helper script contains only placeholder code with no actual binder interface implementations.

**Current State:**
```bash
case "$1" in
    "vm")
        echo "Calling IVirtualMachineManager bridge..."
        # No actual implementation
        ;;
    "storage")
        echo "Calling IStorageProxy bridge..."
        # No actual implementation
        ;;
esac
```

**Expected Behavior:**
- Implement actual binder calls using `cmd shizuku`
- Return real data from Plus feature APIs
- Or mark script as development placeholder with clear warning

**Impact:**
- Users cannot use CLI to access Plus features
- Misleading error messages
- Wasted development opportunity for power users

---

### Issue #5: Server-Side Plus Features Are Stub Implementations

**Labels:** `enhancement`, `server`, `plus-features`
**Priority:** P1 - High
**Affected Files:**
- `server/src/main/java/rikka/shizuku/server/ContinuityBridgeImpl.kt`
- `server/src/main/java/rikka/shizuku/server/AICorePlusImpl.kt`
- `server/src/main/java/rikka/shizuku/server/WindowManagerPlusImpl.kt`
- `server/src/main/java/rikka/shizuku/server/VirtualMachineManagerImpl.kt`

**Description:**
Multiple Plus feature implementations only log actions instead of implementing real functionality.

**Specific Issues:**

**ContinuityBridgeImpl.kt:**
- `syncData()` - Only executes `log` command
- `listEligibleDevices()` - Only reads settings, no real device discovery
- `requestHandoff()` - Only logs action

**AICorePlusImpl.kt:**
- `getPixelColor()` - Returns `Color.TRANSPARENT` instead of actual pixel color
- `captureLayer()` - Always returns `null`
- `scheduleNPULoad()` - Only logs, doesn't schedule real NPU tasks

**WindowManagerPlusImpl.kt:**
- `setAsBubble()` - Only logs instead of implementing bubble APIs
- `setAlwaysOnTop()` - Uses non-standard `cmd window` command

**VirtualMachineManagerImpl.kt:**
- All methods call non-existent `vm` command

**Expected Behavior:**
- Implement actual Android APIs using reflection where needed
- Or clearly mark as experimental/placeholder with user-facing warnings
- Return meaningful data or throw UnsupportedOperationException

**Impact:**
- Features appear to work but do nothing
- Apps relying on these APIs will fail silently
- User confusion and potential data loss

---

## 🟡 Medium Priority (P2)

### Issue #6: Resource Leaks in AdbPairingClient and ShellTutorialActivity

**Labels:** `bug`, `memory-leak`, `adb`
**Priority:** P2 - Medium
**Affected Files:**
- `manager/src/main/java/moe/shizuku/manager/adb/AdbPairingClient.kt`
- `manager/src/main/java/moe/shizuku/manager/shell/ShellTutorialActivity.kt`

**Description:**
Document URIs and network sockets are not properly closed in error paths, leading to resource leaks.

**Code Pattern:**
```kotlin
// ShellTutorialActivity.kt:56-65
fun writeToDocument(name: String) {
    DocumentsContract.createDocument(...)?.runCatching {
        // No close() call - resource leak
    }
}

// AdbPairingClient.kt:278
val decrypted = pairingContext.decrypt(message) ?: throw Exception()
// Socket not closed in exception path
```

**Expected Behavior:**
- Use `use {}` blocks for auto-closeable resources
- Implement proper try-finally blocks
- Close all Document URIs and sockets

**Impact:**
- Memory leaks over extended use
- File descriptor exhaustion
- App may crash after prolonged use

---

### Issue #7: Missing Input Validation in Settings and Network APIs

**Labels:** `bug`, `security`, `validation`
**Priority:** P2 - Medium
**Affected Files:**
- `manager/src/main/java/moe/shizuku/manager/ShizukuSettings.java`
- `server/src/main/java/rikka/shizuku/server/NetworkGovernorPlusImpl.kt`

**Description:**
User input is accepted without validation, potentially allowing injection attacks or system instability.

**Specific Issues:**

**ShizukuSettings.java:474**
```java
// No validation on spoof target string
return p.getString(Keys.KEY_SPOOF_TARGET, "pixel_8_pro")
```

**NetworkGovernorPlusImpl.kt**
```kotlin
// setPrivateDns accepts any string without validation
override fun setPrivateDns(mode: String?, hostname: String?)
```

**Expected Behavior:**
- Validate all user input against whitelist
- Sanitize DNS hostnames before passing to system
- Reject invalid spoof targets
- Use enum or sealed class for known values

**Impact:**
- DNS injection attacks possible
- System instability from invalid configurations
- Potential privilege escalation

---

### Issue #8: ActivityLogManager Has No Persistence

**Labels:** `enhancement`, `data-loss`, `activity-log`
**Priority:** P2 - Medium
**Affected File:** `manager/src/main/java/moe/shizuku/manager/utils/ActivityLogManager.kt`

**Description:**
Activity logs are stored only in memory and lost when the app is killed or device restarts.

**Current Implementation:**
```kotlin
private val records = Collections.synchronizedList(LinkedList<ActivityLogRecord>())
private const val MAX_RECORDS = 100
```

**Expected Behavior:**
- Implement Room database persistence
- Add configurable log retention period
- Export logs to file option
- Thread-safe disk writes

**Impact:**
- Debugging information lost on app restart
- Cannot analyze historical patterns
- Forensic analysis impossible after crashes

---

### Issue #9: Hardcoded Values Throughout Codebase

**Labels:** `code-quality`, `refactoring`, `good first issue`
**Priority:** P2 - Medium
**Affected Files:** Multiple

**Description:**
Magic numbers and hardcoded strings should be constants or configurable settings.

**Examples:**
```kotlin
// ActivityLogManager.kt
private const val MAX_RECORDS = 100  // Should be configurable

// ShizukuSettings.java:474
return p.getString(Keys.KEY_SPOOF_TARGET, "pixel_8_pro") // Hardcoded default
```

**Expected Behavior:**
- Move magic numbers to constants file
- Make limits user-configurable where appropriate
- Use resource strings for user-facing text

**Impact:**
- Difficult to tune behavior without recompilation
- Inconsistent user experience across devices
- Maintenance burden

---

## 🟢 Low Priority (P3)

### Issue #10: Missing Null Checks in Multiple Locations

**Labels:** `bug`, `null-safety`, `crash`
**Priority:** P3 - Low
**Affected Files:**
- `manager/src/main/java/moe/shizuku/manager/home/HomeActivity.kt`
- `manager/src/main/java/moe/shizuku/manager/management/AppViewHolder.kt`

**Description:**
Potential null pointer exceptions due to missing null checks.

**Code Patterns:**
```kotlin
// HomeActivity.kt:271
val versionName = packageManager.getPackageInfo(packageName, 0).versionName
// versionName could be null

// AppViewHolder.kt:96
val isGranted = runCatching { AuthorizationManager.granted(packageName, appInfo.uid) }
// appInfo could be null
```

**Expected Behavior:**
- Add null checks before property access
- Use safe call operator `?.` where appropriate
- Add Elvis operator `?:` with defaults

**Impact:**
- Occasional crashes on certain devices/ROMs
- Unpredictable behavior with malformed data

---

### Issue #11: Performance Issues in Settings and UI Code

**Labels:** `performance`, `optimization`
**Priority:** P3 - Low
**Affected Files:**
- `manager/src/main/java/moe/shizuku/manager/settings/ShizukuPlusSettingsFragment.kt`
- `manager/src/main/java/moe/shizuku/manager/management/ApplicationManagementActivity.kt`

**Description:**
Inefficient code patterns cause unnecessary CPU and memory usage.

**Specific Issues:**

**ShizukuPlusSettingsFragment.kt:**
- `updateAllPlusFeatureDependencies()` called on every preference change
- Iterates through all 9+ preferences each time
- Should use diff-based updates

**ApplicationManagementActivity.kt:**
- `drawSwipeBackground()` creates new ColorDrawable and icon on every draw call
- Should cache drawables

**Expected Behavior:**
- Cache expensive-to-create objects
- Use diff-based updates for preferences
- Implement RecyclerView ViewHolder pattern properly

**Impact:**
- Settings screen feels sluggish
- Increased battery drain
- Janky animations

---

### Issue #12: Accessibility Gaps Throughout App

**Labels:** `accessibility`, `a11y`, `enhancement`
**Priority:** P3 - Low
**Affected Files:** Multiple layout files

**Description:**
Several UI elements lack proper accessibility support for TalkBack users.

**Issues:**
- Several `ImageView`s have `android:importantForAccessibility="no"` without alternative text
- No content descriptions on action buttons in dialogs
- Swipe gestures have no TalkBack support
- No keyboard navigation support in some screens

**Expected Behavior:**
- Add `android:contentDescription` to all actionable icons
- Implement accessibility actions for custom gestures
- Test with TalkBack enabled
- Add keyboard navigation support

**Impact:**
- App is partially unusable for visually impaired users
- Violates Android accessibility guidelines
- May violate legal requirements in some jurisdictions

---

## 🔵 UX/UI Issues

### Issue #13: Icons Have Black-on-Black Visibility Issues

**Labels:** `bug`, `ui`, `theme`, `icons`
**Priority:** P2 - Medium
**Affected Files:** Multiple drawable files

**Description:**
Icons with hardcoded colors don't respect theme, causing visibility issues in dark/AMOLED themes.

**Problematic Icons (Hardcoded Colors):**
- `ic_terminal_24.xml` - `fillColor="#000"` (black on dark theme)
- `ic_root_24dp.xml` - `fillColor="#000"`
- `ic_numeric_1_circle_outline_24.xml` - `fillColor="#000"`
- `ic_monochrome.xml` - `fillColor="#FF000000"`
- `ic_learn_more_24dp.xml` - `fillColor="#FF000000"`
- `ic_warning_24.xml` - `fillColor="#FF000000"`
- `ic_adb_24dp.xml` - `fillColor="#FF000000"`
- `ic_system_icon.xml` - `fillColor="#fff"` (white on light theme)
- `ic_wadb_24.xml` - `fillColor="#FFFFFF"`

**Expected Behavior:**
- All icons should use `android:tint="?attr/colorControlNormal"` or `?appColorPrimary`
- Icons should be visible in both light and dark themes
- Use theme-aware color attributes

**Impact:**
- Icons invisible in certain themes
- Poor user experience
- Settings appear broken

**Status:** ✅ Partially Fixed - Core icons updated

---

### Issue #14: Icons Don't Match Their Function

**Labels:** `enhancement`, `ui`, `design`
**Priority:** P3 - Low
**Affected Files:** Multiple drawable files

**Description:**
Several icons don't visually represent their associated function, causing user confusion.

| Icon File | Used For | Issue | Suggested Replacement |
|-----------|----------|-------|----------------------|
| `ic_close_24.xml` | "Hide disabled Plus Features" | Shows X/close, should be eye/visibility | `ic_outline_visibility_24` |
| `ic_outline_notifications_active_24.xml` | "System Theming Bridge" | Shows bell, should be palette/theme | `ic_palette_24` |
| `ic_baseline_link_24.xml` | "Storage Bridge" & "Continuity Bridge" | Generic link | `ic_folder_24` / `ic_devices_24` |
| `ic_monochrome.xml` | "Window Tuner" | Abstract shape | `ic_window_24` |
| `ic_numeric_1_circle_outline_24.xml` | "Virtual Machine Manager" | Just number 1 | `ic_server_24` |

**Expected Behavior:**
- Icons should visually represent their function
- Use Material Design icons where possible
- Maintain consistent icon style throughout app

**Impact:**
- User confusion
- Settings harder to find
- Unprofessional appearance

---

### Issue #15: Missing Empty State Views

**Labels:** `enhancement`, `ui`, `empty-state`
**Priority:** P3 - Low
**Affected Files:** Multiple RecyclerView adapters

**Description:**
Most lists don't show empty state views when there's no data, only ActivityLogActivity has one.

**Screens Missing Empty States:**
- App management list (when filtered)
- Home screen cards (when all hidden)
- Plus Features (when all disabled)
- Activity Log (when cleared)

**Expected Behavior:**
- Show informative empty state with icon and message
- Provide action to add first item where applicable
- Use consistent empty state design

**Impact:**
- Users think app is broken when lists are empty
- No guidance on how to add content
- Poor first-time experience

---

## ⚠️ Experimental Feature Warnings

### Issue #16: Experimental Features Lack User Confirmation

**Labels:** `enhancement`, `safety`, `experimental`
**Priority:** P2 - Medium
**Affected Files:**
- `manager/src/main/res/xml/settings_shizuku_plus.xml`
- `manager/src/main/java/moe/shizuku/manager/settings/ShizukuPlusSettingsFragment.kt`

**Description:**
Dangerous experimental features can be enabled without user confirmation or warnings.

**Features Requiring Warnings:**
- `Vector Acceleration` - "Highly unstable" but no confirmation
- `Experimental Root Compatibility` - Can break system without warning
- `Spoof Device Identity` - No rollback mechanism

**Expected Behavior:**
- Show confirmation dialog before enabling
- Explain risks clearly
- Provide rollback/warning about potential issues
- Consider requiring multiple confirmations for dangerous features

**Impact:**
- Users may brick devices unknowingly
- System instability
- Support burden from preventable issues

---

## 🔋 Battery & Performance

### Issue #17: WatchdogService Lacks Exponential Backoff

**Labels:** `performance`, `battery`, `watchdog`
**Priority:** P3 - Low
**Affected File:** `manager/src/main/java/moe/shizuku/manager/service/WatchdogService.kt`

**Description:**
Watchdog service runs continuously without exponential backoff, even when Shizuku is stopped.

**Expected Behavior:**
- Implement exponential backoff for retries
- Stop checking when Shizuku is explicitly stopped
- Use WorkManager for background tasks
- Add user-configurable check interval

**Impact:**
- Unnecessary battery drain
- Wake locks held indefinitely
- System resource waste

---

### Issue #18: AdbPairingService Holds Wake Locks Without Safeguards

**Labels:** `bug`, `battery`, `adb`, `wake-lock`
**Priority:** P2 - Medium
**Affected File:** `manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt`

**Description:**
Wake locks are acquired without timeout safeguards, potentially draining battery indefinitely.

**Expected Behavior:**
- Use WorkManager for background pairing
- Add timeout to all wake locks
- Release wake locks in finally blocks
- Log wake lock acquisition/release

**Impact:**
- Battery drain during failed pairing attempts
- Device may not enter deep sleep
- Thermal issues from prolonged wake state

---

## 🌍 Localization Issues

### Issue #19: Inconsistent Translations in Portuguese Strings

**Labels:** `localization`, `translation`, `pt-br`
**Priority:** P3 - Low
**Affected Files:**
- `manager/src/main/res/values-pt/strings.xml`
- `manager/src/main/res/values-pt-rBR/strings.xml`

**Description:**
Some strings have inconsistent translations between Portuguese variants.

**Specific Issues:**
- `bug_report_dialog_wiki` - Different phrasing between pt and pt-rBR
- `bug_report_dialog_issues` - Inconsistent terminology

**Expected Behavior:**
- Consistent terminology across Portuguese variants
- Native speaker review of translations
- Use Crowdin or similar for translation management

**Impact:**
- Confusing for Portuguese speakers
- Unprofessional appearance
- Support queries from confused users

---

## 📝 Documentation Issues

### Issue #20: Common Build Comment Suggests Replacement Needed

**Labels:** `documentation`, `technical-debt`, `common`
**Priority:** P3 - Low
**Affected File:** `common/src/main/java/moe/shizuku/common/util/BuildUtils.java`

**Description:**
TODO comment indicates this class should be replaced with rikka.core.util.BuildUtils.

**Code:**
```java
* TODO: Replace it with {@link rikka.core.util.BuildUtils}.
```

**Expected Behavior:**
- Either implement the replacement or remove TODO
- Update all usages to use rikka.core.util.BuildUtils
- Remove duplicate code

**Impact:**
- Code duplication
- Maintenance burden
- Confusion about which class to use

---

## 📊 Summary Statistics

| Priority | Count | Status |
|----------|-------|--------|
| P0 - Critical | 2 | Open |
| P1 - High | 3 | Open |
| P2 - Medium | 7 | 1 Partially Fixed |
| P3 - Low | 9 | Open |
| **Total** | **21** | **20 Open, 1 In Progress** |

### By Category

| Category | Count |
|----------|-------|
| Security | 3 |
| Bug | 10 |
| Enhancement | 11 |
| Code Quality | 5 |
| UI/UX | 4 |
| Performance | 4 |
| Accessibility | 1 |
| Localization | 1 |
| Documentation | 1 |

---

## How to Use This Document

1. **Create Issues:** Copy each issue title and description to GitHub
2. **Apply Labels:** Use the suggested labels for each issue
3. **Set Priority:** Use GitHub milestones or labels for priority tracking
4. **Track Progress:** Update this document as issues are resolved

### Suggested Labels to Create

```
Priority:
- P0-Critical
- P1-High
- P2-Medium
- P3-Low

Type:
- bug
- enhancement
- security
- code-quality
- technical-debt
- documentation
- localization

Category:
- ui
- server
- adb
- plus-features
- accessibility
- performance
- battery
```

---

*Generated: $(date)*
*Total Issues: 21*
