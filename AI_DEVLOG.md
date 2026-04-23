# ShizukuX — AI Session Devlog & Planning Space

Living document. Update at the start of every AI session: mark completed items, add new ones.
This is the single source of truth for cross-session continuity — reduces re-explanation,
prevents re-introducing fixed bugs, and keeps the roadmap visible without user steering.

---

## Open Backlog (unfinished / planned)

Items carried forward from previous sessions that have not yet been committed.

### Bugs / Correctness

- [ ] **ActivityLogDao persistence** — Activity log currently lives only in memory (`ActivityLogManager`
  uses a `MutableStateFlow`). On app restart all records vanish. Need a Room DAO or DataStore
  list to persist across sessions. Mid-flight work existed in Apr 17 session but was not committed.
  Files involved: `utils/ActivityLogManager.kt`, needs new `ActivityLogDao.kt`.

- [ ] **IWindowManagerPlus AIDL alignment** — Cross-module AIDL interface consistency between
  `manager/` and `api/` was flagged but not verified. Check that method signatures in
  `IWindowManagerPlus.aidl` match on both sides. Apr 17 session.

- [ ] **Activity log toggle not synced to server** — `syncAllPlusFeaturesToServer()` does not yet
  include the activity logging enabled/disabled state. Needs a new key added to the sync payload.

- [ ] **suCopyOpen rish command display** — Root Compat Hub should surface the rish command string
  that a Shizuku-aware app needs to invoke, so power users can configure apps manually without
  guessing. Mar 30 session.

- [ ] **Root Compat Hub "Shizuku-aware only" label** — UI currently does not communicate that the
  Root Compat Hub only affects apps that speak the Shizuku API. Add a subtitle or info chip to
  set correct expectations. Mar 30 session.

- [ ] **ServiceDoctorActivity coordinator_root audit** — Confirm `ServiceDoctorActivity` does not
  override `getLayoutId()` with a content-only layout (same class of bug fixed Apr 23 in 5 other
  activities). Check `activity_service_doctor.xml` is inflated via `rootView`.

### Performance / Cleanup

- [ ] **Handler.kt dead code** — `serverScope` and `workerDispatcher` initialization may still be
  unused after the Apr 20 revert (`9b4fc82c`). Quick audit + removal if confirmed dead.

- [ ] **LogAdapter DiffUtil** — `ActivityLogActivity.LogAdapter.update()` calls
  `notifyDataSetChanged()`. Should use `DiffUtil.DiffResult` or `ListAdapter` for smooth updates.

### CI / Infrastructure

- [ ] **Sentry quota** — Quota was at 100% through end of April 2026. Check current Sentry
  dashboard at start of May to confirm events are flowing again or decide on quota expansion.

- [ ] **Pre-push guard stale package names** — `.github/pre-push-check` may still reference
  `moe.shizuku.privileged.api` in some checks. Audit after next CI pass.

---

## Ideas Parking Lot (floated, not committed to)

Things discussed or sketched that we never formally decided to build.

- **AICore+ full implementation** — `retro_notes.md` describes XML UI hierarchy dumping and
  physical input simulation (touch/swipe/text) for AI automation. This was described as a feature
  but no code path for it exists yet. Would require a privileged accessibility bridge.

- **Context7 integration for dev sessions** — Use the `mcp__claude_ai_Context7__query-docs` tool
  at the start of sessions involving Android API questions (Lifecycle, WindowManager, Mavericks,
  Sentry SDK changes) rather than relying on training data. Particularly useful for Mavericks
  version differences and Sentry 8.x migration quirks.

- **Jetpack Compose re-migration** — Compose was fully migrated then reverted (`25d796d4` revert
  of `749d72a6`) due to instability. If Compose is reconsidered, start with a single isolated
  screen (e.g. Service Doctor) rather than a full home screen migration.

- **Dynamic remote DB versioning** — `AppContextManager` fetches `database/apps.json` but has no
  version/ETag caching. Could add `If-None-Match` header + local cache timestamp to reduce
  redundant fetches.

- **Changelogs in-app** — Show a "What's new" bottom sheet on first launch after an update,
  driven by the GitHub release body fetched during update check.

- **Onboarding step for Root Compat Hub** — Users don't know the Root Hub exists. An optional
  onboarding card after first Shizuku connection could walk through what toggles are available.

---

## Session History (newest first)

### 2026-04-23 — Claude (Sonnet 4.6)
**Commits:** `98f47424`

**Done:**
- Mavericks ProGuard keep rules (`-keep class * implements MavericksViewModelFactory`)
  fixes `HomeViewModel` companion factory crash after R8 `-repackageclasses`
- `showMigrationDialog()` reconstructed from literal placeholder comment to working
  root/no-root dialog branches
- `showCrashReportDialog()` duplicate `setNeutralButton` removed; "Share File" now shows
- Removed `getLayoutId()` overrides from 5 activities that were passing content-only layouts
  as the root frame → fixed `coordinator_root IllegalStateException` on all affected screens:
  `RootCompatibilityActivity`, `ActivityLogActivity`, `AdbPairingTutorialActivity`,
  `StarterActivity`, `ShellTutorialActivity`
- `MaterialSharedAxis` X transitions added to `AppBarActivity`
- `MotionUtils.kt` — spring-scale touch animations with haptic feedback
- `header_card.xml` — reusable M3 hero header (icon + title)

**Session started with analysis of all previous Claude session logs to surface the open backlog
above. This document was created as a result of that analysis.**

---

### 2026-04-21 — Claude (Sonnet 4.6)
**Commits:** `827754ec`, `153cfe05`, `e9002881`, `b0176fd2`

**Done:**
- Manual crash reporting system: `CrashHandler` + `CrashReporter` + dialog in `MainActivity`
  that offers "Generate Report", copy to clipboard, open GitHub issue, or share as file
- A11y: `importantForAccessibility="no"` on collapsible category arrow and doctor check icon;
  contentDescriptions added to drag handle and remove button in `home_item_container`
- `AppContextManager`: Added `ENH_OVERLAY` + `ENH_NETWORK` constants and wired into
  `loadFromCache()` — remote JSON entries with these keys were being silently dropped
- Settings import/export in Developer Options (export to JSON, import with validation)

**Unresolved in session:** Mavericks ProGuard rules (edit rejected), `RootCompatibilityActivity`
coordinator_root fix (carried to Apr 23).

---

### 2026-04-20 — Claude (Sonnet 4.6)
**Commits:** `003fd16f` through `0d47caba` (12 commits)

**Done:**
- DhizukuProvider permission check dead code fixed (empty if body)
- SU Bridge bounds guards + `RISH_APPLICATION_ID` env var in su wrapper
- Auto-close terminal and pairing screens when Shizuku becomes ready
- Shell injection fix; watchdog backoff; `SettingsPage` enum centralization; `CHANGES.md` created
- `fbcc1cd7`: sealed `ListItem` in Root Compat adapter; activity log synced to server state
- Comprehensive open-source attribution (`NOTICE`, `OPEN_SOURCE_LICENSES.md`)
- `a318d884`: deep stability audit + CI compilation fix
- Removed `markdowntwain` private dep, fixed `jvmTarget`, upgraded `rikkax-material-preference`
- Samsung Freecess bypass, Sentry 8.x best practices, localization audit, Material Symbols icons

---

### 2026-04-17 — Claude (Sonnet 4.6)
**Commits:** (session ended before most changes were committed — see Open Backlog)

**Context:** Long session focusing on deeper architecture. Many edits discussed but not all landed.

**Done or confirmed:**
- `ActivityLogActivity` header card layout design discussed + partially implemented
- Root Compat Hub sealed ListItem adapter (landed in Apr 20 `fbcc1cd7`)
- AIDL interface review for `IWindowManagerPlus` (flagged, not resolved)
- `ActivityLogDao` persistence gap identified (not resolved — still in backlog)

**Left open:** Activity log persistence, AIDL alignment, rish command display, Root Compat Hub
"Shizuku-aware" label.

---

### 2026-04-15 — Claude (Sonnet 4.6)
**Commits:** `decb2a88` through `1c19937b` (9 commits)

**Done:**
- Sentry 8.x SDK migration: `SentryTimberTree` re-init, OkHttp artifact fix, compile errors
- Samsung Freecess bypass: detect + skip Freecess process kill targeting Shizuku
- Service Doctor real-time diagnostics for Wireless ADB and Auto Blocker
- Localization audit (removed stale keys, added missing ones)
- Material Symbols icon refresh for settings
- Duplicate UI components removed from pairing and shell tutorial screens

---

### 2026-04-14 — Claude (Sonnet 4.6)
**Commits:** `391cc7bc`

**Done:**
- Missing `R` import in `MainActivity` resolved
- Dead `helper.cpp` removed

---

### 2026-03-30 — Claude (Sonnet 4.6)
**Session:** `0d4f362f` (118KB)

**Context:** Architecture and stability session. Lots of planning.

**Done or carried forward into Apr 20:**
- Shell injection vulnerability identified and fixed
- Watchdog backoff strategy designed
- `syncAllPlusFeaturesToServer()` partial work — activity log toggle NOT yet included
- suCopyOpen rish command display idea raised but deferred
- Root Compat Hub scope clarification idea raised but not implemented
- `Handler.kt` dead code flagged

---

### 2026-04-11 — Claude (Sonnet 4.6)
**Session:** `2a96eec2` (60KB)

**Context:** Storage and infrastructure focus.

**Done:** Details subsumed into Apr 20 batch commits.
**Notable:** Sentry quota concern raised here; quota at 100% through April 2026.

---

## Architecture Rules (crash-critical — do not reintroduce)

| Rule | Why |
|------|-----|
| Launcher activity MUST be `.MainActivity` | `HomeActivity` is abstract — cannot be instantiated |
| `getLayoutId()` must return the FULL frame layout | Content-only layouts as root cause `coordinator_root IllegalStateException` |
| Subclass activities inflate content via `inflate(layoutInflater, rootView, true)` | Never override `getLayoutId()` with a content-only XML |
| `<include>` tags MUST NOT have `android:id` | Overrides nested view IDs, breaks `toolbarContainer` |
| `io.sentry.auto-init=false` MUST stay in `AndroidManifest.xml` | Manual init in `ShizukuManagerApplication` — double-init crashes |
| `Mavericks.initialize(this)` MUST be called before Koin in `ShizukuApplication.onCreate()` | Koin modules reference Mavericks state classes |
| Mavericks ProGuard keep rules MUST stay in `proguard-rules.pro` | `-repackageclasses rikka.shizuku` breaks companion factory reflection |
| `AppBarActivity.rootView` is a `ViewGroup` — use `rootView.getChildAt(0)` for ViewBinding in subclasses that need it | Direct cast to binding class will fail |
| Compose was tried and reverted — do not re-introduce without a plan | Full migration caused instability; revert was `25d796d4` |

---

## Key Files Quick Reference

| Area | File |
|------|------|
| All settings logic | `manager/.../settings/SettingsFragment.kt` |
| Update settings UI | `manager/.../settings/UpdateSettingsFragment.kt` |
| GitHub Releases + update detection | `manager/.../update/UpdateChecker.kt` |
| APK download + install | `manager/.../update/UpdateManager.kt` |
| All SharedPreferences keys | `manager/.../ShizukuSettings.java` (`inner class Keys`) |
| App metadata + enhancement DB | `manager/.../utils/AppContextManager.kt` |
| Activity log (in-memory) | `manager/.../utils/ActivityLogManager.kt` |
| Crash capture | `manager/.../utils/CrashHandler.kt` |
| Crash report generation | `manager/.../utils/CrashReporter.kt` |
| Root compat modules | `manager/.../utils/RootCompatHelper.kt` |
| Shizuku state machine | `manager/.../utils/ShizukuStateMachine.kt` |
| Server sync | `manager/.../utils/SettingsHelper.kt` |
| Home screen state | `manager/.../home/HomeViewModel.kt` + `HomeState.kt` |
| Base activity | `manager/.../app/AppBarActivity.kt` |
| Entry point | `manager/.../MainActivity.kt` |
| ProGuard | `manager/proguard-rules.pro` |
| UI strings | `manager/src/main/res/values/strings.xml` |
| Settings XML | `manager/src/main/res/xml/settings*.xml` |
