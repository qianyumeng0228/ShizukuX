# ShizukuPlus Development Sprint Plan

## 🎯 Sprint Goal
Resolve all 21 identified GitHub issues with proper testing, documentation, and release management.

---

## 📋 Workstreams (Parallel Execution)

### **Stream A: Security & Critical Fixes** 🔴
**Priority:** P0 - Must complete first
**Issues:** #51, #31

#### Task A1: SSL Certificate Validation (#51)
- [ ] Remove all `@SuppressLint("TrustAllX509TrustManager")` annotations
- [ ] Implement proper X509TrustManager with certificate validation
- [ ] Add certificate pinning for ADB pairing
- [ ] Test ADB pairing with valid/invalid certificates
- [ ] Add unit tests for certificate validation

#### Task A2: Empty Catch Blocks (#31)
- [ ] Add logging to all 22 empty catch blocks
- [ ] Use Timber or Log.e() for error logging
- [ ] Add error reporting for critical failures
- [ ] Review and rethrow where appropriate
- [ ] Test error paths

---

### **Stream B: Server Implementation** 🟠
**Priority:** P1 - High
**Issues:** #32, #33, #34

#### Task B1: TODO Implementation (#32)
- [ ] Implement user service kill functionality (Line 833)
- [ ] Add runtime permission listener (Line 841)
- [ ] Remove TODO comments
- [ ] Test service lifecycle

#### Task B2: Shell Script Implementation (#33)
- [ ] Implement actual binder calls in `/manager/src/main/assets/plus`
- [ ] Add `cmd shizuku` integration
- [ ] Test all CLI commands
- [ ] Add help documentation

#### Task B3: Server Plus Features (#34)
- [ ] Implement ContinuityBridgeImpl real APIs
- [ ] Implement AICorePlusImpl screen capture
- [ ] Implement WindowManagerPlusImpl bubble APIs
- [ ] Implement VirtualMachineManagerImpl AVF APIs
- [ ] Add proper error handling
- [ ] Test each implementation

---

### **Stream C: Code Quality & Error Handling** 🟡
**Priority:** P2 - Medium
**Issues:** #35, #36, #38

#### Task C1: Resource Leaks (#35)
- [ ] Add `use {}` blocks for auto-closeable resources
- [ ] Fix Document URI leaks in ShellTutorialActivity
- [ ] Fix socket leaks in AdbPairingClient
- [ ] Test resource cleanup

#### Task C2: Input Validation (#36)
- [ ] Add validation for spoof target strings
- [ ] Validate DNS hostnames in NetworkGovernorPlusImpl
- [ ] Add enum/sealed class for known values
- [ ] Test with invalid inputs

#### Task C3: Hardcoded Values (#38)
- [ ] Move magic numbers to constants
- [ ] Make MAX_RECORDS configurable
- [ ] Externalize hardcoded defaults
- [ ] Test configuration changes

---

### **Stream D: UI/UX Improvements** 🟢
**Priority:** P2/P3 - Medium/Low
**Issues:** #42, #43, #44, #50

#### Task D1: Icon Visibility (#42)
- [ ] Fix remaining hardcoded color icons
- [ ] Ensure all icons use theme tinting
- [ ] Test in light/dark/AMOLED themes
- [ ] Verify visibility in all themes

#### Task D2: Icon Semantics (#43)
- [ ] Replace ic_close_24 with visibility icon
- [ ] Replace notification bell with palette for theming
- [ ] Replace generic link with specific icons
- [ ] Test icon recognition

#### Task D3: Empty States (#44)
- [ ] Add empty state for app management list
- [ ] Add empty state for home cards
- [ ] Add empty state for Plus Features
- [ ] Design consistent empty state layout

#### Task D4: Icon Consistency (#50)
- [ ] Standardize all icons to 24x24 viewport
- [ ] Convert to consistent Material Design style
- [ ] Normalize stroke weights
- [ ] Test icon rendering

---

### **Stream E: Performance & Battery** 🟢
**Priority:** P2/P3 - Medium/Low
**Issues:** #40, #46, #47

#### Task E1: Performance Optimization (#40)
- [ ] Cache expensive objects in ShizukuPlusSettingsFragment
- [ ] Implement diff-based updates
- [ ] Cache drawables in ApplicationManagementActivity
- [ ] Profile performance improvements

#### Task E2: Watchdog Battery (#46)
- [ ] Implement exponential backoff
- [ ] Stop checking when Shizuku explicitly stopped
- [ ] Add user-configurable interval
- [ ] Test battery impact

#### Task E3: Wake Lock Safeguards (#47)
- [ ] Add timeout to all wake locks
- [ ] Release in finally blocks
- [ ] Log wake lock acquisition/release
- [ ] Test with failed pairing

---

### **Stream F: Accessibility & Documentation** 🟢
**Priority:** P3 - Low
**Issues:** #37, #39, #41, #45, #48, #49

#### Task F1: Activity Log Persistence (#37)
- [ ] Implement Room database for logs
- [ ] Add configurable retention period
- [ ] Add export to file option
- [ ] Test persistence across restarts

#### Task F2: Null Safety (#39)
- [ ] Add null checks in HomeActivity
- [ ] Add null checks in AppViewHolder
- [ ] Use safe call operators
- [ ] Test with null data

#### Task F3: Accessibility (#41)
- [ ] Add content descriptions to all icons
- [ ] Implement TalkBack support for gestures
- [ ] Add keyboard navigation
- [ ] Test with TalkBack enabled

#### Task F4: Experimental Warnings (#45)
- [ ] Add confirmation dialogs for experimental features
- [ ] Explain risks clearly
- [ ] Add rollback warnings
- [ ] Test user flow

#### Task F5: Localization (#48)
- [ ] Review Portuguese translations
- [ ] Ensure consistency between pt and pt-rBR
- [ ] Native speaker review
- [ ] Test in app

#### Task F6: Documentation (#49)
- [ ] Remove or implement BuildUtils TODO
- [ ] Update usages to rikka.core.util.BuildUtils
- [ ] Remove duplicate code
- [ ] Test after refactoring

---

## 🏷️ Release Management

### Release Checklist
- [ ] Create version tag (e.g., v2026.1.0)
- [ ] Write comprehensive release notes
- [ ] Include all fixed issues with links
- [ ] Add upgrade notes
- [ ] Create GitHub release
- [ ] Build and test release APK
- [ ] Update CHANGELOG.md

### Release Notes Template
```markdown
## ShizukuPlus v{VERSION} - {DATE}

### 🚨 Security Fixes
- #{NUMBER} - {DESCRIPTION}

### 🔥 Critical Fixes
- #{NUMBER} - {DESCRIPTION}

### ✨ New Features
- #{NUMBER} - {DESCRIPTION}

### 🐛 Bug Fixes
- #{NUMBER} - {DESCRIPTION}

### 🎨 UI/UX Improvements
- #{NUMBER} - {DESCRIPTION}

### ⚡ Performance
- #{NUMBER} - {DESCRIPTION}

### 📝 Documentation
- #{NUMBER} - {DESCRIPTION}

### 📦 Upgrade Notes
{Any special upgrade instructions}

### 🙏 Contributors
{List of contributors}
```

---

## ✅ Quality Gates

### Before Merge
- [ ] Code reviewed
- [ ] Tests passing
- [ ] Lint checks passing
- [ ] No new warnings
- [ ] Documentation updated

### Before Release
- [ ] All critical issues resolved
- [ ] Manual testing completed
- [ ] Release notes written
- [ ] Version bumped
- [ ] Tag created

---

## 📊 Progress Tracking

| Stream | Total Tasks | Completed | In Progress | Blocked |
|--------|-------------|-----------|-------------|---------|
| A: Security | 2 | 0 | 0 | 0 |
| B: Server | 3 | 0 | 0 | 0 |
| C: Code Quality | 3 | 0 | 0 | 0 |
| D: UI/UX | 4 | 0 | 0 | 0 |
| E: Performance | 3 | 0 | 0 | 0 |
| F: Accessibility | 6 | 0 | 0 | 0 |
| **TOTAL** | **21** | **0** | **0** | **0** |

---

## 🚀 Quick Start

```bash
# Create feature branch
git checkout -b sprint/issue-{NUMBER}

# Make changes
# ...

# Test
./gradlew test
./gradlew lint

# Commit
git commit -m "Fix: #{NUMBER} - {DESCRIPTION}"

# Push and PR
git push origin sprint/issue-{NUMBER}
gh pr create --title "Fix #{NUMBER}: {DESCRIPTION}" --body "Fixes #{NUMBER}"
```

---

*Generated: 2026-03-18*
*Sprint Start: 2026-03-18*
*Target Completion: 2026-03-25*
