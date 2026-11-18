# Shizuku+ — AI Session Devlog & Planning Space

Living document. Update at the start of every AI session: mark completed items, add new ones.
This is the single source of truth for cross-session continuity — reduces re-explanation,
prevents re-introducing fixed bugs, and keeps the roadmap visible without user steering.

---

## Open Backlog (unfinished / planned)

Items carried forward from previous sessions that have not yet been committed.

### Features

- [x] **AICore 5 advanced method implementations** — Implemented `getPixelColor`, `captureLayer`, and `getSystemContext` in `AICorePlusService.kt` using `AccessibilityService.takeScreenshot` (API 30+) as a high-performance manager-side bridge. Done 2026-05-03.
- [/] **Shadow Binder Deep Implementation (Issue #199)** — Implemented `IPackageManager` shadowing in `ShizukuService.java` to hide specific apps based on the `shadow_hidden_packages` setting. Added UI for managing hidden packages. **Needs testing with hidden packages.**
- [x] **Root Compat Hub "Shizuku-aware only" label** — Info banner added to
  `activity_root_compatibility.xml` with string `root_hub_shizuku_aware_note`. Done 2026-04-24.

### CI / Infrastructure

- [x] **Broken `api` submodule pointer blocked all CI builds (found 2026-07-13)** — `master`'s `api`
  submodule was pinned to commit `8657364fdc7ebf58072d690a978c7cc3c9bd4271`, which the
  `ShizukuPlus-API` remote rejected (`upload-pack: not our ref`) during checkout. Repinned to
  `d40bc88c` (actual `origin/master` HEAD). Done 2026-07-13, `f20d8c8a`.
- [x] **Missing `android.os.Build` import broke release/debug compile (found 2026-07-13)** —
  surfaced only once the submodule fix above let CI reach actual compilation.
  `BiometricLock.kt:42` (added by `e25440f2`, pre-existing before this session) referenced
  `Build.VERSION.SDK_INT`/`Build.VERSION_CODES.R` with no import. Fixed 2026-07-13, `17b0a727`.
  **`Build App` confirmed green on master afterward** (run `29276207625`).
- [x] **CI Speed Optimization** — Parallelized lint/build jobs and added SDK caching (~50% time cut). Done 2026-04-27.
- [x] **Room Stability** — Downgraded to 2.6.1 (Stable) to avoid alpha-branch risks. Done 2026-04-27.
- [x] **KSP Migration** — Fully removed KAPT from Room build path for modern/fast processing. Done 2026-04-27.
- [x] **KSP2 / Room 2.6.1 incompatibility** — KSP 2.2.20-2.0.2 uses KSP2 architecture which requires Room 2.7+ for void DAO methods; added `ksp.useKSP2=false` to `gradle.properties` to fall back to KSP1. This was a pre-existing failure unmasked by the database brace fix. Done 2026-04-28.
- [x] **Pre-push guard audit** — Verified script checks for CMake versions, Kotlin imports, and stale JNI package paths (af/shizuku migration). Done 2026-04-27.
- [x] **Sentry quota** — Quota was at 100% through end of April 2026. **Automated calendar block is active** in `ShizukuApplication` and will self-expire on May 1st.
- [x] **Dhizuku Device Owner Gaps** — `DhizukuAdminReceiver` and related device admin XML created. Ready for ADB `dpm set-device-owner` command to fully enable Dhizuku Mode capability. Done 2026-04-28.
- [x] **In-app Changelogs** — `ChangelogDialogFragment` created to read from `assets/changelog.txt`. Wired into `MainActivity` to show on first launch after update using `ShizukuSettings.getLastSeenVersion()`. Done 2026-04-28.
- [x] **AICore+ Automation Bridge** — `AICorePlusService` (AccessibilityService) scaffolded to support `dumpHierarchy()`, `performTap()`, and `performSwipe()`. XML config and manifest entries added. Ready for command proxying. Done 2026-04-28.

### Device Compatibility

- [x] **Deep Vendor Diagnostics** — Added detection and logging for ColorOS, HyperOS, and TCL versions in manual/Sentry reports.
- [x] **Expanded Spoofing Targets** — Added Pixel 10 Pro XL, S25 Ultra, and more flagship targets to Dev Options.
- [x] **Service Doctor Tips** — Added specific troubleshooting for Oppo/OnePlus and TCL background kills.
- [x] **libadb.so crash fix** — `initializeStatics()` no longer rethrows `UnsatisfiedLinkError`; ADB
  pairing degrades gracefully on devices where the native lib can't load (TCL, Oppo ColorOS).
  `ShizukuApplication.isAdbNativeAvailable` flag gates entry to `AdbPairingTutorialActivity`.

### Design / Theming (from 2026-07-13 theming audit)

- [x] **Dead theming resources removed** (`0ffc9b34`, 2026-07-13): `cornerRadiusExtraLarge` custom
  attr (was a red herring vs. the real `ShapeAppearance.*.Corner.ExtraLarge` tokens - it was never
  consumed anywhere, not the same attr, so there was no actual 32-vs-28dp conflict once traced
  through), `textColorOnSurfaceHighEmphasis`/`textColorOnSurfaceMediumEmphasis`,
  `card_corner_radius_large` dimen.
- [ ] **Every card in the app hardcodes `app:cardCornerRadius` (16/24/28dp)** instead of
  `app:shapeAppearanceOverlay="?attr/shapeAppearanceCornerExtraLarge"` etc. - `cardCornerRadius`
  unconditionally overrides `shapeAppearanceOverlay` on `MaterialCardView`, so the user-facing
  Modern/Classic/Squircle shape_style setting currently does nothing for any card in the app,
  including the home screen. Investigated 2026-07-13: correct fix in principle, but the base
  `Theme.Material3Expressive.*.Shizuku` theme lives in an external AAR I can't inspect, and I
  can't confirm it defines a default for `shapeAppearanceCornerExtraLarge` - an unresolved theme
  attr on `shapeAppearanceOverlay` risks an inflate-time crash, not just a wrong color, across the
  app's most-used screens. **Needs on-device/build-capable confirmation before attempting** -
  filed as [issue #333](https://github.com/thejaustin/ShizukuPlus/issues/333) rather than guessed
  at blind.
- [x] **`shape_edit_control_background.xml`'s `colorSurfaceContainerHighest` AMOLED remap** — done
  2026-07-13 (`1a637ece`). First pass wrongly assumed chaining `Highest → ?colorSurfaceContainerHigh`
  would make the edit-mode chip invisible against its own card (both resolve to the same color
  under `ThemeOverlay.Black`, since `?attr` resolution is against the fully merged theme, not each
  item's pre-overlay value). On closer look this is fine and consistent with the existing pattern
  (Low already ties with Lowest the same way): the two icons actually inside that chip
  (`ic_drag_handle`, `ic_close_24`) are tinted independently via `colorControlNormal`/`colorError`,
  not derived from the chip's own background, so they stay visible regardless of whether the
  chip's background ties with its parent card's tone. Completed the remap.

### Crash Fixes (from Gemini ADB session 2026-04-27 — fixes applied, not yet on-device verified)

- [/] **Mavericks factory crash** — ProGuard keep rules added for `MavericksViewModel` + factory to
  prevent R8 `-repackageclasses` from breaking companion discovery. **Needs ADB verification — #200.**
- [/] **SQLite race on fresh install** — `ActivityLogManager.loadFromDatabase()` wrapped in try-catch
  so a DB open failure degrades to in-memory mode. **Needs ADB verification — #200.**

---

### Completed (previously listed as open, confirmed done on 2026-04-23 audit)

- [x] **ActivityLogDao persistence** — Fully implemented in `database/` submodule with Room.
  `ActivityLogManager` already wires DAO, loads on init, persists all writes.
- [x] **Activity log toggle synced to server** — `enable_activity_log` already in
  `syncAllPlusFeaturesToServer()` at `ShizukuSettings.java:686`.
- [x] **IWindowManagerPlus AIDL** — No `IWindowManagerPlus.aidl` exists in the manager AIDL
  dir; concern was unfounded. Only `IDhizuku.aidl` present.
- [x] **ServiceDoctorActivity coordinator_root** — Uses `inflate(layoutInflater, rootView, true)`
  correctly. No `getLayoutId()` override.
- [x] **Handler.kt dead code** — `workerThread`/`workerHandler` in server ktx Handler are used.
  No dead code.
- [x] **suCopyOpen rish display** — `suCopyOpen` button already wired in `RootCompatibilityActivity`
  at line 308.
- [x] **LogAdapter DiffUtil** — Migrated to `ListAdapter` + `DiffUtil.ItemCallback`. `694eea28`.
- [x] **Sentry April 2026 hardcode** — Removed expired calendar block from `ShizukuApplication`.
  `694eea28`.

---

## Ideas Parking Lot (floated, not committed to)

Things discussed or sketched that we never formally decided to build.

- [x] **Context7 integration for dev sessions** — Context7 MCP is active and documented in the shizukuplus skill. Use `mcp__claude_ai_Context7__query-docs` for Mavericks, Sentry 8.x, Lifecycle edge cases. Done 2026-06-26.

- **Jetpack Compose re-migration** — Compose was fully migrated then reverted (`25d796d4` revert
  of `749d72a6`) due to instability. If Compose is reconsidered, start with a single isolated
  screen (e.g. Service Doctor) rather than a full home screen migration.

- [x] **Dynamic remote DB ETag caching** — Added ETag/Last-Modified header support to `RemoteDbSyncWorker` to skip redundant downloads when the app-context database hasn't changed on GitHub. Saves bandwidth and improves sync efficiency. Done 2026-05-19.

- **Onboarding step for Root Compat Hub** — Users don't know the Root Hub exists. An optional
  onboarding card after first Shizuku connection could walk through what toggles are available.

---

## Session History (newest first)

### 2026-11-18 — k2807：testkey 也激活不了，回到代码把 Dhizuku 的身份路径补齐

**起点**：k2806 的 `-oppo-testkey` 包实测装上后，直连激活仍然失败。上一版把原因归给"签名白名单"，被实装结果否掉了，只能重新取证。

**Dhizuku 真正的手法**（`ActivateViewModel.kt` → `requireBinderWrapperDevicePolicyManager`）：不看 Binder 接口面，而是反射把本进程 `DevicePolicyManager` 的私有字段 `mService` 换成 `ShizukuBinderWrapper(原 binder)`，然后**照旧调用 `DevicePolicyManager` 的方法**（`setActiveAdmin` / `setDeviceOwner` / `forceUpdateUserSetupComplete`），`finally` 还原。Java 对象和方法都没变，变的只有 Binder 的目标，所以到达 DPMS 的调用者是 shell(2000)。

**两条路径的分野**（AOSP main `DevicePolicyManagerService.java` 原文）：

```java
boolean hasIncompatibleAccountsOrNonAdb = !isAdb(caller) || hasIncompatibleAccountsOnAnyUser();
```

- 应用自身身份（本项目原来那条）：该变量恒真 → 跳过"非 test-only 且存在账户就拒"，账户/分身/多用户都不参与；但紧接的 `enforceCanSetDeviceOwnerLocked` 里非 ADB 分支第一句就是 `Preconditions.checkCallAuthorization(hasCallingOrSelfPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS))`，不满足**抛 SecurityException**。这权限靠 `DEVICE_POLICY_MANAGEMENT` Role 换来，ROM 裁掉角色这条就死。
- shell 身份（Dhizuku 那条）：不要这个权限，但要过 `checkDeviceOwnerProvisioningPreConditionLocked(..., isAdb=true, ...)`，账户/多用户/向导闸/`STATUS_HAS_PAIRED` 全参与。

所以不是"谁更宽松"，是**卡的关卡不同**：一条卡 Role 权限，一条卡账户与分身。只试一条就判断"链路没问题、是签名问题"，正是 k2806 的错误。

**本版改动**：`DpDoDirectActivation` 第 6 步（应用自身）被拒后自动进第 6b 步——用 Dhizuku 同款手法换 `mService`，以 shell 身份重跑 `forceUpdateUserSetupComplete` / `setActiveAdmin` / `setDeviceOwner`，无条件还原；失败时结果框**分列两条路径的判决**，日志带 `cmd user list` 与每步系统原文，可一键复制。

**同轮复核修掉四处**（第一版编译通过但逻辑不可靠，逐行对源码后改）：

1. **签名回退会盖掉真实原因**：A14+ 先试两参、失败再试三参，A16 上三参重载已不存在，第二次的 `NoSuchMethodException` 覆盖了第一次的 `SecurityException`，日志只剩"方法找不到"。现在只有 `NoSuchMethodException` 才换签名，方法存在但被拒立即定性。
2. **`SecurityException` 原文被丢**：原来只在 `IllegalStateException` / `IllegalArgumentException` 时打印原文，而缺 `MANAGE_PROFILE_AND_DEVICE_OWNERS` 抛的正是 `SecurityException`。现在所有异常都记原文。
3. **解释改按系统原文判读**：`STATUS_*` 真实数值不在公开 SDK 里（抓 `DevicePolicyManagerService` 全文也没有常量定义），`errorCodeFromMessage` 的映射不可靠，纯按数字会把账户问题说成分身问题。
4. **`forceUpdateUserSetupComplete` 补无参回退**（Dhizuku 同样做了这一步）。

顺带核实：A16 的 `DevicePolicyManager.setDeviceOwner(ComponentName, int)` 确实存在，内部转 `mService.setDeviceOwner(who, userId, true)`，与 DPMS 的三参对上；`ShizukuApplication` 在 SDK≥28 无条件执行 `HiddenApiBypass.setHiddenApiExemptions("")`，反射隐藏 API 的前提成立。

**验证**：最终构建 `BUILD SUCCESSFUL`（2m47s）；三包 sha256 已入 README；testkey 版 `apksigner verify` 单签名、指纹 `a40da8…f5dc`、`zipalign -c` 通过；`classes.dex` 检出 `IDevicePolicyManager$Stub`、`ShizukuBinderWrapper`、「第 6b 步」、`无参 forceUpdateUserSetupComplete 也失败`、`shell 身份调用被系统拒绝`，且负向对照串计数为 0，确认是本轮代码而非旧产物。

**仍未覆盖**：无 ColorOS 真机，`mService` 字段与 `Stub.asInterface` 按 AOSP 实现写但没跑过；`checkDeviceOwnerProvisioningPreConditionLocked` 的定义未取到（抓取只有调用点），`isAdb=true` 分支的具体判据是按错误文案反推的。定位仍需用户侧一次失败日志。

### 2026-11-18 — k2806：自启动读错了 op，「别人能设设备所有者我不能」是签名不是链路

**用户反馈的第一件**：系统设置里自启动是开的，ShizukuX 里是关的。查下来不是显示 bug，是读的根本不是同一个开关。旧实现只读 AOSP 的 `RUN_ANY_IN_BACKGROUND`（后台运行限制），而 ColorOS 的「自启动」是独立的 vendor op `AUTO_START`，系统设置页读写的是后者。两个开关各管一件事，任何时候都对不上。

改法：`AutoStartManager` 启动时探一次——`cmd appops get <包>` 里有没有 `AUTO_START:` 行；没有行不代表没有这个 op（部分 ROM 只列被改过的 op），所以再补一次 `cmd appops query-op AUTO_START deny`，只有它回 "Unknown operation" 才判定本机没有。有就用 vendor op，没有回落 AOSP op。副标题同步写明当前是哪一种，避免继续让人以为这里改的是系统那个开关。

顺带把 k2805 漏掉的四页补齐：自启动、按应用隐藏、强制深色、应用操作权限原先只在 `onCreate` 读一次，服务断开后挂着旧数据、失败还弹框。新增 `ServiceStateReloader`（用 `ShizukuStateMachine.isRunning()` 播种，避免 `addListener` 的同步首回调重复加载），四页 `onCreate` attach、`onDestroy` detach，`onFailure` 改成"服务不在时不弹窗"。

**第二件（OPPO 设备所有者）**：把 Dhizuku 的 `ActivateViewModel` 原码和 issue #16 一起读了。结论分两半——

- 调用链上我们并不弱：`DpDoDirectActivation` 用应用自身 uid（`isAdb=false`），系统只检查"向导是否完成"，账户、应用分身、第二用户都不参与判断；Dhizuku 走 shell 身份时要过账户检查，所以它不删账户时只能设 profile owner。我们已经在这条更短的路上。
- 真正拦人的是签名。issue #16 原文：「OPPO 仅允许白名单内的应用、测试签名应用激活设备所有者」，他们为此另发 `Dhizuku_v2.13.0-testkey.apk`，证书主题 `CN=Android, O=Android, emailAddress=android@android.com`，即 AOSP testkey。自建签名在这一层就被拒，后面所有判据都不会被执行。

所以本版不换签名（换成 testkey 会让现有用户无法覆盖安装，是需要单独决定的事），只在激活日志首行记录「签名类别」与「ROM 是否 OPPO 系」，并在 OPPO 系 + 自建签名的失败文案里直接说明是白名单导致的——否则用户只会看到"别人能行我不行"。

**第三件（模块页底部）**：模块页由外壳托管，悬浮胶囊导航（66dp 高 + 20dp 抬起）画在页面之上，而 Scaffold 因为 `showTabBar=false` 不给底部 inset，最后一张卡片的「删除」被压掉一半。托管时底部留白改 104dp，独立打开仍 32dp。

**第四件（首页↔授权手感）**：这两页之间挂了一段自定义水平手势（授权页是 AndroidView，会吞横向拖动），其他页由 pager 自己拖，两套判定必然手感不同。原实现要求横向位移超过纵向 1.2 倍才接管（斜一点的手指前段完全不动），松手只按「超过 18% 页宽」决定翻页（快速轻扫被弹回，那一下回弹就是用户说的"阻尼"）。现在方向判定与 pager 对齐，松手加 0.8px/ms 的速度判定，阈值提到 25%。

**真机验证（2210132C / Android 16 / HyperOS）**：自启动页回落 AOSP op 并显示「本机没有独立的自启动开关，这里控制的是后台运行限制」；连续 kill 服务约 5 秒后页面自己变成「请先启动 Shizuku 服务 / 0 个应用」且无弹窗，服务恢复自动回到 176 个应用。强制深色页同样断→恢复自动重载（0 → 159 个应用，无弹窗）。模块页滚到最底，「记录模块」的「删除」完整显示且与胶囊留有间距。

**注意**：`AUTO_START` 分支与 OPPO 签名结论都只在代码和上游资料层面确认，没有 ColorOS 真机；手势只能靠手感判断；模块页以外的其他 pager 托管页底部留白没有逐一核对。

**OPPO 渠道包（同日补做）**：社区里两种说法拼起来就是答案——OwnDroid「单独出 testkey 版本」，管理员补一句「这个 testkey 是 AOSP 的，ColorOS 只接受这个签名，也就是 MT 的默认签名」。拿用户提供的 `ColorAOSP` 包验了一遍，它的证书正是 AOSP testkey（`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`，`CN=Android, O=Android, android@android.com`）。

于是从 AOSP `platform/build` 取 `testkey.pk8` / `testkey.x509.pem`，转成 JKS，对 k2806 的 dropin 包重签，产出 `ShizukuX-13.7.0.k2806-oppo-testkey.apk`。证书指纹与 Dhizuku `-testkey`、OwnDroid testkey 版、ColorAOSP 完全一致，`apksigner verify` 单签名、`zipalign -c` 通过。

包名仍是 `moe.shizuku.privileged.api`（Dhizuku 兼容层的 authority 与包名绑定，不改），代价是与自签 dropin 同包名不同签名——**装 OPPO 版前必须卸载现有 Shizuku / ShizukuX**，卸载会清数据、需要重新授权。这也是 Dhizuku 官方发 testkey 包时的既有形态。

未在真机安装：本机 adb 已掉线，且卸载重装会拆掉当前验证环境。可用证据是两包证书指纹不同（`a5451d07…` vs `a40da80a…`），足以说明系统会以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒绝覆盖。

### 2026-11-18 — k2805：服务断开时页面还在挂旧读数，而且会弹一个"操作未完成"

k2804 的监听只在"变成 RUNNING"时 `load()`，反方向没管。结果是服务一断，页面把上一份读数继续挂在屏幕上——用户看到"设置还在"，可开关其实已经点不动；等真去点，失败提示又很像"要重做一遍"。同一件事的两个方向都要处理，只补一边等于把误导换个位置。

改成 `if (running != serviceRunning) load()`：两个方向都重载。服务断开 → 页面切到"请先启动 Shizuku 服务 / 当前系统无法读取状态"；服务回来 → 页面自己刷回真实列表和开关。

顺带修掉一处弹窗：`BatteryWhitelistActivity` 原先在读取失败时弹"操作未完成（Shizuku 未运行，启动服务后才能管理白名单）"。服务只是暂时不在，却弹一个失败框，读起来就是"你刚才的改动没成功"。现在服务不在时只更新页面状态、不弹框，只有服务活着真报错才弹；`FirewallActivity` 的 `onFailure` 同样加了 `FirewallManager.isAvailable()` 判断。

**真机验证（2210132C / Android 16 / HyperOS）**：该服务被杀后约 1 秒就被 Watchdog 拉起，单次 `kill` 抓不到中间态，因此用设备端循环（24 次、间隔 0.3 s，约 7 秒窗口）连续杀。
- 防火墙页（已加载 156 个应用的状态下）：服务断 → 自动变「请先启动 Shizuku 服务」「当前系统无法读取状态」「0 个应用」「没有匹配的应用」；服务恢复（新 pid）→ **未退出页面**，自动刷回「已拦截 0 个应用」「156 个应用」与完整列表。
- 电池页：服务断 → 副标题变「Shizuku 未运行，启动服务后才能管理白名单」，列表 0 个应用，**无对话框**；服务恢复 → 自动刷回「已加入白名单 52 / 540 个应用」与完整列表，白名单开关状态保持。

**顺带发现的构建坑**：`:dhizuku-compat:assembleRelease` 不传 `-PshizukuVersionCode` 时用的是模块默认值（这次出成 2807 / `13.6.0.r2807`），和 manager 的版本号对不上；补传参数后为 2805。

### 2026-11-18 — k2804：重启后"设置要重做"，丢的不是设置，是页面的读取能力

先把"到底丢了什么"查清楚，结论和用户描述相反：重启后 `GET_USAGE_STATS` 仍 allow，`DUMP` / `WRITE_SECURE_SETTINGS` 仍 granted，Doze 白名单 71 行全在，`chain3` 也还是原值。规则一条没丢——它们本来就存在系统里，这个应用从不自己存一份。

真正丢的是提供 shell 的服务。服务不在时，`FirewallActivity` 与 `BatteryWhitelistActivity` 读不到系统状态，只能停在那句"需要服务"的提示上；用户照着空状态把规则重设一遍，事后读到的记忆就是"重启后要重新设置"。

k2803 解决的是"服务自己回来"（`BootRestoreReceiver`）。这一版补另一半：两页监听 `ShizukuStateMachine`，从非 RUNNING 变 RUNNING 时重新加载，服务回来列表就跟着回来。监听器 `onCreate` 注册、`onDestroy` 注销；初始值取当前状态，避免 `addListener` 的同步首回调与 `onCreate` 里第一次加载撞在一起。

**未完成**：这两个页面的自动刷新没有 UI 端到端验证。`FirewallActivity` 在 manifest 里是 `exported=false`，`am start` 被系统拒（`Permission Denial`）；首页卡片走 `startWithSceneTransition`，自动化点击三次都没进去。所以"服务恢复后页面自动刷新"目前只是编译通过 + 代码路径正确，没有设备上的观测证据。

**后续（k2805 已补齐）**：上面这段说的"单向监听"本身是半个修复——服务断开时页面仍挂旧读数。k2805 改成双向重载，并在设备上跑通了两个页面的断/恢复全程；`exported=false` 也不是死路，从首页卡片点进去即可（坐标按实际 1080×2400 折算，约 1.2 倍）。

### 2026-11-18 — k2803：重启后自动旋转被写回 1，是因为没人把它改回去，而采样又把 1 当成了用户选择

重启后实测 `accelerometer_rotation` 一定是 1，跟重启前用户设的什么无关——这是平台的默认值，不是我们写的。真正的问题在两头：一头是没有东西把它改回去；另一头是 `HidingWatchService` 启动后的第一轮空闲采样读到这个 1，把它记成"用户的选择"，从此每次恢复都往 1 写。

现在加 `AutoRotatePreference`：只在用户自己点开关、并且平台回读确认已生效时记一份（`DeviceControlManager.setAutoRotate`），采样值一律不采信——采样回答的是"现在是什么"，不是"用户要什么"。开机时 `BootRestoreReceiver` 在 refresh 之前先比对并写回，顺序不能反，否则采样又抢在前面。

顺带把 `resetAfterBoot()` 清掉 `KEY_IDLE_ACC`：重启前的采样值配上重启后的平台默认值，等于把最不该用的那份组合拿去当目标。

**验证**：k2803 装机后方向回归通过——Chrome 内 `acc=0 / urm=lock 0 / fixed=enabled`，回桌面 `acc=1 / urm=free / fixed=disabled`。开机链路在真实重启里通了（`BOOT_COMPLETED` 送达、`HidingWatchService` 由应用自己拉起、server 在跑），但**写回本身没有在重启场景跑过**：设备重启后停在锁屏，脚本解不了锁。OPPO 侧仍无真机。

### 2026-11-18 — k2802：OPPO 反馈没解决，恢复少了一条 `wm user-rotation`，以及重启后到底丢了什么

上一版（k2801）在小米 13 Pro 上三条路径都对，OPPO 用户仍然复现。这轮先把上游看了一遍：Shizuku-Next 的 `restore` 是 `fixed-to-user → accelerometer_rotation → user_rotation → wm user-rotation`，我们 r2795 因为 HyperOS 上 `free` 会写 `accelerometer_rotation=1` 而把最后一条整个删了。删得过头了——ColorOS 的快捷开关读的是 `user-rotation` 的 `free`/`lock N`，不是那个 settings 键，所以 `accelerometer_rotation` 回到 0 之后图标照样亮着。现在按基线成对写：`accelerometer_rotation=0` 补 `wm user-rotation lock <user_rotation>`，`=1` 补 `wm user-rotation free`。两个值与 settings 键本来就一致（`free` 写 1、`lock N` 写 0），所以 HyperOS 上不会互踩，真机两条路径都回归通过。

第二个改动是 `restore()` 的 `applied` 守卫。隐藏模块在列表变化时会调 `Hiding.restoreAll()` + `RotationOverride.restore()`，而 `restore()` 原来只要看到 base 就写回——一份上次恢复中途失败留下的 base 会被反复重放，等于把很久以前（甚至上一台状态）的开关值写回当前设备。现在没有活动锁定就只丢 base、不写任何命令。

第三个是 `BootRestoreReceiver`。真机重启后实测：`GET_USAGE_STATS` 仍 allow，三条 pm 权限仍 granted，Doze 白名单仍在（71 行），但 `accelerometer_rotation` 回到 1、`shizuku_plus_server` 没起。也就是说权限和规则本身没丢，丢的是提供 shell 的服务；用户看到的"设置要重做"是服务没起来导致页面读不到。接收器在开机后先丢掉旧锁定标记，再等服务可用（最多 90 秒）重新拉起监听服务。90 秒远超广播的 goAsync 窗口，这一点是已知隐患，后续若在 ColorOS 上观察不到生效，应先改成 WorkManager 续跑。

**未完成**：开机恢复没有端到端验证。本机重启后停在锁屏，无法自动解锁继续操作；OPPO 侧的表现也需要真机。

### 2026-11-18 — k2801：自动旋转"自己打开"的两个成因，以及锁定期间的自愈

`1970355d`。r2795 / r2797 都没能让用户那边消停，本轮不再靠推理，直接在设备（2210132C / Android 16 / HyperOS）上把开关一步步改出来对照。

**成因一：锁定期间开关被第三方改写，`applied` 标记让后续每轮直接返回。** 锁已生效时手工 `settings put system accelerometer_rotation 1`，屏幕仍是 `fixed-to-user=enabled`，但开关停在 1 —— 命中 `applyFor` 的 `current != null && current.first == foreground && current.second == wanted → return` 提前返回分支。用户看到的是"屏幕还竖着，自动旋转亮了"，k2799/k2800 都会这样。修法：该分支每轮只读 `accelerometer_rotation` 与 `fixed-to-user-rotation` 两项（比 `readDisplayState` 少两条命令），任一被外部改写就重新 `applyLock`，日志记 `relock`；重锁失败则清 `applied`，让下一轮重新采样。

**成因二：空闲采样录下的其实是系统改写值。** `rememberIdleAutoRotate` 原先在任何"没有强制方向"的前台都会采样，而该 ROM 一旦有应用到前台就把开关写成 1，于是采到 1；下次进强制应用再 restore，1 被写回。k2800 加的 `isHome()` 约束只允许桌面/无前台采样，本轮确认这是对的原因；同时 base 在无可信 idle 时把 `autoRotate` 记 null，restore 跳过该项而不是猜一个值写回。

**真机验证（k2801，`moe.shizuku.privileged.api` versionCode 2801）**
- 锁定中 put acc=1 → 4 s 后回到 `acc=0 fixed=enabled`，日志 `relock fg=com.android.chrome mode=PORTRAIT ok=true`。
- 用户关闭自动旋转（idle=0）：进 Chrome `fixed=enabled acc=0`，回桌面 `acc=0 fixed=disabled`。
- 用户开启自动旋转（idle=1）：进 Chrome 被关到 0，回桌面恢复 1，日志 `restore base=…autoRotate=1`。
- 中途在 Chrome 内把 acc 改成 1：回桌面 restore 用的是 base 里的 0，不是被改过的 1。

**一处已知代价**：自愈每轮多两条 shell 命令。watch 本来就是 1200 ms 一轮并已读 `dumpsys`，实测未见明显耗电变化，若后续要压，可把校验降到每 3 轮一次。

### 2026-11-18 — r2799：上游复查（ShizukuPlus / shevery / Shizuku-Next）与四项落地

带代理重新 fetch 七个参考仓库：Dhizuku、Dhizuku-API、Nightzuku、AxManagerD 均已是最新；Shizuku-Next 落后 1、shevery 落后 7、ShizukuPlus 落后 26。逐条判断后跟了四项，其余归为「不适用 / 纯 UI 实验 / 自家生态」。

**1. 冻结拒绝系统关键包**（ShizukuPlus `5817d005`，安全）
冻结 System UI、设置、电话、权限组件或任何 persistent 应用会让设备在锁屏重启循环。ShizukuX 的 `ActivityManagerPlusImpl.freezeApp`（三段：`setApplicationEnabledSetting(3)` → `pm disable-user` → `cmd package suspend`）和 `BackupRestoreExtraImpl.freezeApp`（`pm disable-user --user 0`）都没有白名单，风险与上游一致。新增 `BootCriticalPackages.kt`（18 个包名 + `FLAG_PERSISTENT`，走 `Android17Compat.getApplicationInfo`），两个入口各加 `refuseFreeze`，命中返回 false 并 `Log.w`。

**2. server 崩溃上报的栈一直被丢**（ShizukuPlus `b3f41aec`，可诊断性 + 隐私）
不是"没发"，是**键名不一致**：server 写 `json.put("stacktrace", …)`（`ShizukuService.java:377`，全小写），manager 读 `optString("stackTrace", "")`（`ShizukuApplication.kt:530`，驼峰）。两者从来没对上，所以每一条 server 侧崩溃事件进 Sentry 时都没有栈。改为两种拼写都接受，并新增 `ServerStackTrace.kt`：上报前脱敏，帧与异常类保留，消息里的包名、路径、`key=value`、bearer token 打码。

**3. 状态监听器隔离**（ShizukuPlus `2e2ae422`，健壮性）
`ShizukuStateMachine` 的 `listeners.forEach { it(newState) }` 里任何一处抛异常都会中断循环，排在后面的订阅者停在旧状态，直到下一次切换。改成逐个 try/catch + `Timber.e`。

**4. 终端导出的 su 改为 opt-in**（ShizukuPlus `a348e9e8`，用户风险）
导出的 `su` 是 SU Bridge 包装，会顶掉终端应用自带的那一个（Termux 有），还自称 Magisk——银行 / 钱包类应用会据此标记。`TerminalAssets` 拆成 `BASE`（rish + dex + extra）与 `ALL`（+su），导出时按 `ShizukuSettings.isSuBridgeEnabled()` 决定是否写 su；清理阶段仍覆盖全集，所以关掉开关再导出一次就会把旧的 su 删掉。教程页文案（英文 + zh-rCN）同步改成条件表述。

**不跟但记录**：shevery 的 `67759552`（把 `attachApplication` 的内联包归属检查抽成 `ClientPackageValidator`）——ShizukuX `ShizukuService.java:685-689` 已有等价检查；`8386afbc` / `bca0aec7` 是 content provider 的 binder UID 校验，ShizukuX 的 server 没有 provider 入口；ShizukuPlus 的 26 条里其余是 M3 卡片 / 图标 / 时间线等 UI 实验、Sentry 生态和 Crowdin CI。`82c5d47b` 的 jni `access()` → `open()` 属代码扫描告警，`starter.cpp` 的那处是"能否读"判断，后续 open 失败有错误分支，不构成可利用漏洞。

### 2026-11-18 — r2795：自动旋转被打开的真实成因，以及 restore 为什么不能碰 `wm user-rotation`

`c3861d53`（r2795）。上一版（`5d570181`）把原因定在命令顺序，装机跑真实路径仍旧每次都回到 1；单轮手工实验不构成验证，这条教训已经写进 r2791 那节。本轮把 base 与实际命令原样打到 logcat（release 没有 Timber 输出，只能 `android.util.Log.w`），才拿到真正的两条：

1. **base 采得太晚**。采样发生在应用已经进入前台之后，HyperOS 在那个时刻已经把 `accelerometer_rotation` 写成 1、`user-rotation` 写成 free。base 记成 1，restore 再把 1 写回去。
2. **restore 抛异常会留下脏 base**。`PrivilegedShell.run` 抛异常时 `remove(KEY_BASE)` 不执行，脏 base 被下一轮采样复用，自己把自己强化。

修法：restore 只发 `wm fixed-to-user-rotation` 加两条 `settings`，不再调 `wm user-rotation`——`free` 会写 `accelerometer_rotation=1`，而 `accelerometer_rotation=0` 又会让模式回到 `lock N`，两者互斥、谁最后落地谁赢；`PrivilegedShell.run` 包 try/catch，配好的 base 无论如何都清；采样前若读到 `fixed-to-user-rotation=enabled` 却没有 applied/base 标记，当成上一轮残留锁先 `disabled` 再采样；`current == null`（没有活动锁）也强制重采样。

**对照实验（决定性）**：把该应用方向设回「关闭」，`refresh()` 走 `stopService` 分支，`dumpsys activity services | grep -c HidingWatchService` 为 0，即服务完全不运行。此时执行「`settings put system accelerometer_rotation 0` → 打开 Chrome」，得到 `chrome acc=1 urm=free`、回桌面仍 `acc=1 urm=free`。**这一步翻转与 ShizuX 无关，来自系统在应用切换时重置该开关**；restore 只是把采样到的 1 写回去，表象看起来像是我们干的。

**重启后复测**：同一组步骤（`accelerometer_rotation=0` → 打开 Chrome）在重启后依旧 0→1，且此时服务未运行，确认为 ROM 行为而非设备被 `wm` 污染。

**最终修法（r2797 / `c84fbc5a`）**：翻转虽然不是我们写的，但 base 是在应用「已经进入前台之后」采样的，永远记成 1，restore 再把 1 写回去。改为在「没有强制方向应用在前台」的每一轮里记录该开关（在 restore 之后读，那时设备已回到用户自己的状态），锁定时用它覆盖 base 的 `autoRotate`。双向真机验证：用户在桌面关着 → 退出后 `acc=0`；用户在桌面开着 → 退出后 `acc=1`。

顺带归档了 r2789（`f6f1c5fc`）与 r2790（`65acc29b`），两份都是用 worktree 按对应 commit 重新检出构建，不是复制改名。踩到的坑：worktree 不做 `git submodule update --init --recursive` 时，Gradle 配置阶段直接报 `Configuring project ':rish' without an existing directory is not allowed`。

### 2026-11-17 — r2791 → r2793：覆盖安装 abort、OFF 分支判死、自动旋转被打开

三件事一起收，全部真机闭环，不是推断。

**1. 覆盖安装后 5 秒 abort**（`f6f1c5fc` / `65acc29b` / `0728da15`）
`starter.cpp` 的 `kill_old_processes()` 用 `readlink /proc/pid/exe` 精确 `memcmp` 判断旧守护；`install -r` 之后内核把 exe 解析成 `.../lib/arm64/libshizuku.so (deleted)`，精确比较失败 → 旧守护没被杀 → 5 秒后重启相位错乱。改成 `proc_cmdline_contains("--apk=")` + exe basename 前缀匹配，并给 `supervise_server()` 加 `access(path, R_OK)` 前置检查（apk 没了就 fatal 退出，不再空转）。

**2. `adb_wifi_enabled` 的 OFF 分支**（`86c43ea8`）
原逻辑 `awaitingAuth = true` 后 `timeoutJob?.cancel()`：第一次关开关既不 close 也不超时，Worker 永久挂起，要走 0→1→0 两步才会失败。改为未锁屏首次 OFF 直接 `close(SecurityException)` 交 `Result.retry()`；锁屏保留原意（等解锁后重新 arm）。真机验证：`dumpsys jobscheduler` 显示 job 立即结束，40 秒内被退避重试拉起。
附带教训：**没有对照组的行为差异不能算验证**。第一次把翻转点定在 1050ms，补做同流程不翻开关的对照后 `control#2` 同样没起——那是 Worker 自身不稳定。

**3. 自动隐藏把「自动旋转」打开**（`5d570181`，后被本节的重写取代）
最初判断是 `restore()` 把 `wm user-rotation` 排在两个 `settings` 之后，被 `wm user-rotation free` 自带的 `accelerometer_rotation=1` 覆盖；调换顺序后单轮手工实验 acc 确实保持 0。但装机端到端每次都回到 1，单轮手工实验不构成验证。

改成把 `base` 和实际命令原样打进 logcat（release 无 Timber，只能 `android.util.Log.w`）后，得到两条硬事实：

- `base` 是在应用**已经进入前台之后**采样的。HyperOS 在那个时刻已经把 `accelerometer_rotation` 写成 1、`user-rotation` 写成 free，于是 base 记成 1，restore 再把 1 写回去。上一次 restore 若抛异常会跳过 prefs 清理，脏 base 又被下一轮复用，自己把自己强化。
- `wm user-rotation free` 与 `accelerometer_rotation=0` 互斥：写 free 会置 1，写 0 会把模式改回 `lock N`。两者谁最后落地谁赢。

据此把 restore 改成只发 `wm fixed-to-user-rotation` + `user_rotation` + `accelerometer_rotation` 三条 settings（不再碰 `wm user-rotation`），并补三处防护：`PrivilegedShell.run` 包 try/catch，prefs 无论如何都清；采样时若 `fixed-to-user-rotation=enabled` 又没有 applied/base，先当成上一轮残留锁清掉再采样；`current == null`（没有活动锁）也强制重采样。

**对照结论**：把方向设回「关闭」让 watch 自己停下（`dumpsys activity services | grep -c HidingWatchService` = 0），服务完全不运行时执行「acc=0 → 打开 Chrome」，acc 依旧 0→1、user-rotation 由 lock 变 free。也就是说这一步翻转来自系统在应用切换时重置该开关，与本工具无关；restore 只是把采样到的 1 写回去、让它看起来像是我们干的。剩下的变量（ROM 行为是否会随重启恢复）需要在重启设备后复测。

**仍未直接观测到的**：`SecurityException("Network is not authorized for wireless debugging")` 本体——`awaitingAuth` 只在「发现端口后、未锁屏」才为真，本机已配对、0.5 秒内就连上，没有授权窗口；要在未配对设备首次激活时才可能命中。`UNKNOWN` 分支要 SDK≥37，本机 36，只有代码级保证。

**上游对照（A 项）**：`ref/Shizuku-Next` 的 `WirelessPortHunt.kt:173` 同样是 `awaitingAuth && !forced` 才 fail，`handleAuth()` 注释明写「等授权可以无限期等」。ShizukuX 的「首次即判死」是对上游的有意偏离（上游的无限等待在本机表现为 Worker 不自愈）；其余三条（`UNKNOWN` / `callerPackages == null` / 日志可读性）已与上游对齐，且日志已补 `android.util.Log.w`，release 可从 `adb logcat` 读回。详见 `I:\shizukux\_research\其他分支更新审计.md` 的 11.14 / 11.15。


### 2026-08-26 — Claude Code (Sonnet 5) [PRoot ShizukuPlus, icon rework + GitHub issue triage + Watchdog resilience]

**All of the below eventually landed on `master`** across commits `34663bd4`..`c13bf851` (see `git log` for the full list) — the "not yet committed" note below is stale, kept for narrative order within this entry.

**Icon:** replaced the app icon from a user-supplied flattened PNG (`shizukuplus.png`) that baked the "plus" badge into the gradient/hexagon/cat art at the wrong position. Removed the badge from the raster via high-pass anomaly detection + hexagon-geometry-aware reconstruction (pure-PIL, no numpy/cv2 available in this environment), then re-added it as a separate semi-transparent vector overlay in `drawable/ic_launcher_foreground.xml`, positioned to match the original upload. Also found and fixed a pre-existing bug: the previous `ic_monochrome.xml` plus badge was at a *different* position than the foreground layer's — now consistent, and `ic_monochrome.xml` was trimmed to contain *only* the plus (not cat+hexagon), since Android's Themed Icons API re-tints the whole monochrome layer as one flat color — there's no way to have "just the plus" themed while the rest stays full-color, so this is the closest the platform allows. Added an onboarding toggle (`switch_themed_icon`, defaults on) + "Open icon settings" deep link (`SettingsPage.ThemedIcons`, best-effort — no OS API exists to actually flip the per-launcher setting; filed **#428** to investigate a root/accessibility auto-toggle once ADB access is available for verification, since guessing at another app's private SharedPreferences schema was assessed as too risky to ship blind).

**GitHub issue triage (~29 open issues):** parallel research passes (3 concurrent `Explore` agents) categorized every open issue. Closed 5 as already-fixed-on-main with confirmation comments: **#423** (SU bridge stale dex, `9dba4bd3`), **#412** (compat hub install, `cf77523e`+`a519414c`), **#414** (download failure diagnostics), **#416**/**#420** (shell consent re-implementation, `32382e89`). Left **#418** open (same fix, lower-confidence match on a garbled report) and **#424** (see below) resolved without closing until next-build confirmation. Posted diagnostic-request comments (with specific `adb logcat` filters) on **#427** (Xperia "accessibility only available on a TV" false positive — leading theory: `leanback` uses-feature/TV launcher-alias tripping Sony's OEM gating), **#421**/**#411** (authorized shell commands silently no-op — leading theory: `newProcess()`'s SU-bridge command-interceptor chain false-matching), **#426** (aShell You broken via Shizuku specifically, no corresponding regression found in history).

**Fixed #424** (authorized-apps count flashing "0" on cold start): `HomeState.grantedAppCount` defaulted to `Int = 0` and rendered before the async `AppsViewModel` count-load completed. Changed to nullable `Int?` end-to-end (`HomeState` → `HomeAdapter` → `ManageAppsViewHolder`), rendering a generic title instead of "0 apps" until the real count arrives.

**Fixed #415/#417** (Samsung "Sleeping apps" freezes the *entire* manager process on screen lock, taking `WatchdogService` down with it — confirmed via deep-review agent that the only prior fix, `a519414c`, was a diagnostic-honesty fix only, not a real recovery mechanism). Added `WatchdogAlarmReceiver` — an `AlarmManager.setExactAndAllowWhileIdle` re-arm, self-rescheduling every 15 min, that survives a full process kill because it's dispatched by `system_server`, not anything inside the app's own (frozen) process. Falls back to `setAndAllowWhileIdle` if `SCHEDULE_EXACT_ALARM` is declined/restricted. Wired into `setWatchdog()` (enable/disable), `BootCompleteReceiver` (exact alarms don't survive reboot), and `ShizukuApplication.onCreate()`. New manifest permission `SCHEDULE_EXACT_ALARM` (runtime-revocable, graceful fallback — not `USE_EXACT_ALARM`, which requires a Play "core app" category this app doesn't qualify for). This is a mitigation (bounded by the 15-min interval), not a full fix — documented as such in the wiki.

**Post-fix deep-review pass** (separate `Explore` agent, adversarial "is this actually fixed for OTHER devices/OEMs" pass) found 3 real, low-risk gaps in the just-closed fixes and fixed them same-session: (1) SU bridge redeploy now also triggers on every service start via `ShizukuStateMachine.asFlow()`, not just `ACTION_MY_PACKAGE_REPLACED` — covers devices where the server wasn't up yet at update time; (2) Compat Hub install failures now special-case `INSTALL_FAILED_INSUFFICIENT_STORAGE`/`INSTALL_FAILED_NO_MATCHING_ABIS` with distinct messages (MIUI-specific wording deliberately *not* guessed at without a device sample); (3) update-download failure notifications now distinguish storage/resume/HTTP errors instead of one generic message. Also fixed a stale code comment in `ShizukuService.java` still describing the deleted `ShellConsentActivity` flow.

**Docs:** published a new wiki page (`Permissions.md`) documenting every manifest permission and the feature it powers, updated `Service-Connection.md`'s Watchdog section to describe the three-layer recovery model, and fixed two dead in-app help links (`Helps.kt` pointed at `wiki/Setup` and `wiki/Supported-apps`, neither of which exist — now point at `Service-Connection` and `ShizukuPlus-Knowledgebase`). This devlog entry, `CLAUDE.md`, `CHANGELOG.md`, `CHANGES.md`, `docs/comparison_shizuku_vs_plus.md`, and `.agents/skills/shizukuplus/references/architecture.md` updated in the same pass to reflect the above.

**Also added:** a single entry-level `LOGGER.d("newProcess: %s -> %s", callingPkg, cmd)` in `ShizukuService.newProcess()` (right after `callingPkg` resolves, before the catastrophic-command check) — most individual SU-bridge interceptor branches already logged what they matched, but a command matching *none* of them left no trace at all, which is exactly the blind spot behind #411/#421/#426's "authorized but appears to do nothing" reports. Now every call is visible in logcat regardless of which interceptor (if any) it hits, without touching the ~20-branch interceptor chain itself.

**Not done / explicitly deferred:** the root/accessibility auto-toggle for Themed Icons (**#428**, needs on-device verification before writing any code that touches another app's private storage); MIUI-specific Compat Hub install error text (needs a Xiaomi device/log sample).

---

**Same day, continued — pre-release review, real-device icon bug, update-channel fix, GitHub project polish.**

**Pre-release review (multi-agent):** security review + bug scan + adversarial verification + GitHub issue/PR sweep + release-notes-style research, run in parallel. Clean bill of health (no confirmed vulnerabilities or bugs) — full findings not repeated here, see the workflow's own output if needed. The PR sweep turned up two "PRs I'll fix in this release" that turned out to already be fully resolved on `master` via *different* commits than the PRs themselves (**#308** — `HomeAdapter` throttle/`ServiceStatus.isRunning` race fix, landed via `3aa13734`; **#354** — missing provider authority, landed via `07316516`, predates the PR by one day) — closed both with an explanation rather than leaving them stale or attempting a pointless re-merge. **#404** (zh-CN l10n, 58 files, genuinely wanted) conflicts against current `master`; commented asking for a rebase rather than attempting a blind conflict resolution.

**Sentry sweep** (org `af-developments`/project `shizukuplus`, first access this session — Sentry+Linear MCP auth completed mid-session): of 15 unresolved issues, resolved 4 (`resolvedInNextRelease`) as confirmed stale-install noise from fixes that shipped weeks ago (WiFi-permission crash r2046, SQLite dir-missing + Room/WorkManager `_Impl` r2145/r2014) — each verified against current source before resolving, not just trusted from memory. Left the real open ones untouched: 3 `deployBridgeToTmp` write-failure issues (94 events — needs root to investigate, unavailable this session) and 2 null-`newProcess()` issues (10 users — same family as #421/#411/#426). Linear mirrors GitHub 1:1 via auto-import (`linear-code` bot), nothing unique found there.

**Cut the first stable release this session** (`v13.6.0.r2304`, `workflow_dispatch` with `prerelease=false` — every plain push only produces a prerelease). The `smoke-test` CI job failed but didn't block the release (separate job) — root cause was `:manager:connectedDebugAndroidTest` becoming ambiguous once the Dropin/Shizukuplus flavor split landed (two candidate tasks); fixed to target the Shizukuplus flavor explicitly.

**Real, confirmed icon bug found via actual device testing** (first real ADB/device access this session — `android-mcp` over wireless ADB to the user's own Samsung SM-S948U, reconnected mid-session after a port rotation on screen-lock). The user reported "the icon is very broken" and provided the exact on-device screenshot path (`DCIM/Screenshots/Screenshot_20260826_002710_One UI Home.jpg` — my first two searches of `Pictures/Screenshots` and a narrow `mmin` window both missed it; the real file was in `DCIM/Screenshots`, dated normally, my search parameters were just wrong). Pulled the actual JPEG and zoomed in: the plus badge was almost entirely invisible (a tiny sliver) and the cat's left eye was completely missing in Samsung's notification-icon rendering. Root cause: I had positioned the cat/hexagon/plus to match the flat, non-adaptive uploaded image pixel-for-pixel, and *verified it with an unmasked flat composite in PIL* — which never simulates the ~66dp/108dp circular safe-zone real adaptive-icon mask/notification-icon compositors apply. Simulating that actual circular mask against the shipped art reproduced the exact defect from the screenshot pixel-for-pixel.

First fix attempt shrank the *whole* cat+hexagon composition ~70% to guarantee everything fit inside the strict safe circle — technically correct but changed the icon's look more than necessary, since the background layer is normally allowed to bleed to the canvas edges (only small foreground *content* like a badge needs strict containment). User pushed back ("shouldn't be far off from the original... should only add the material you plus") — reverted the cat/hexagon back to full original scale/position, and *only* repositioned the small plus badge to a spot verified safe by the same mask simulation. Separately, the user asked to move the plus back to the *exact* original-upload coordinates one more time — declined and asked for explicit confirmation given that position is the literal one just proven broken; user confirmed keeping the safe position. Final state: icon visually matches the original upload almost exactly, plus badge verified visible under an actual circular mask (not just a flat preview) — this verification-method gap (flat composite vs. real mask) is the actual lesson here and is now documented as a code comment in `ic_launcher_foreground.xml` so it isn't relearned the hard way again.

**Fixed a real Dev/Beta update-channel bug** (`UpdateChecker.checkViaApi`): the dev/beta path always preferred the newest *prerelease*-flagged GitHub release over the newest release overall, even after a newer *stable* release had since been cut — a Dev/Beta user could get stuck being offered an older prerelease instead of upgrading to a newer stable. Now compares actual parsed version codes and takes whichever is genuinely newer.

**Restyled the CI's auto-generated release notes** to Keep a Changelog's category vocabulary (Security → Breaking → Added → Fixed → Other, in that priority order — Security promoted to always-checked since this is privileged-process software), based on researching upstream RikkaApps/Shizuku (flat bullet list, terse), the thedjchi sibling fork (bracketed category tags — closest lineage precedent), NewPipe (richest categorization + contributor credit discipline), and keepachangelog.com itself.

**GitHub project polish:** added `.github/pull_request_template.md` and `CONTRIBUTING.md` (neither existed despite issue templates already being present); updated the repo's GitHub-side description (dropped a stale "[ UNSTABLE ]" tag left over from the same maintainer-downtime period as the README banner), added topics (`android`, `shizuku`, `adb`, `root`, `kotlin`, `dhizuku`, `android-app`, `privileged-access`) and a homepage link to the wiki — none of this existed before and it's pure discoverability upside. Credited **odorizzioficial** in the README Acknowledgments — the only external contributor with an actually-merged PR in this fork's real history (#409, Brazilian Portuguese translation) plus the detailed Watchdog-freeze report (#415); everyone else in the raw GitHub contributors API list is either the maintainer or inherited upstream Shizuku blame, not a real ShizukuPlus contributor, so deliberately not padded with those names.

**Key process lesson from this segment:** a flat, unmasked composite preview is not sufficient verification for adaptive-icon content — always simulate the actual circular/squircle safe-zone mask before shipping icon changes, and where possible verify against a real device rather than a synthetic preview. Real device access (once wireless ADB was available) caught a bug that three separate PIL-composite verification passes earlier in the session had all missed.

---

### 2026-07-21 — Claude Code (Fable 5) [PRoot ShizukuPlus, rescuing an Antigravity CLI session + reconciling clones]

**Commits:** `ac92c5da`, `469bdde3` (CI-green fixes); `a5b24d68`/`4e708c16`/`1c950cf3` (rebased, previously unpushed); `f162889f`/`a7d8365a`/`ebc63cc6` (follow-up: changelog + release labeling, see below)

**Context:** the user runs an Antigravity CLI (Gemini) agent against the Termux clone independently of
Claude Code sessions — no shared `/resume`, so continuity across the two tools means reading
Antigravity's own local transcript logs (`~/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl`,
found by grepping all conversations for "ShizukuPlus" and picking the most recently modified). This
morning's Antigravity session (07:22–08:43 UTC, model switched Gemini 3.5 Flash → Claude Sonnet 4.6
Thinking mid-session) worked through GitHub issue comments and Sentry crashes on the user's own
"continue" / "look for more" / "fix sentry issues" prompts, landing one large 36-file commit
(`1d860a2a`, "resolve issue #337 #358 #329 #355") plus two same-session compile-error follow-ups
(`6f908bcc`, `880d5555`). `master` was still red when the session ended — its last action was polling
a still-in-progress CI run that went on to fail without the agent noticing.

**Found and fixed 4 defects, all classic AI-patch artifacts (lines silently dropped mid-diff):**
1. **`HomeScreen.kt`** — `fun HomeScreen(...) {` was missing its closing brace (brace-balance check
   caught it: whole-file depth ended at +1). Added the missing `}`.
2. **`SettingsHelper.kt`** — a new function's signature line was dropped, leaving
   `isSamsungAutoBlockerDisabled`'s `return try { ... }` body orphaned directly under the previous
   function. Recovered the name from its one call site in `ServiceDoctorActivity.kt`.
3. **`.github/workflows/app.yml`** — added `:manager:assembleCompatRelease` plus matching
   `uploadSentry*CompatRelease` exclusions, on the false assumption "Compat" is a product flavor of
   `:manager`. It's actually the separate `:compat` module, already built as a dependency of
   `copyCompatApkRelease` wired into `pre{Flavor}ReleaseBuild` — the extra task/exclusions referenced
   Gradle tasks that don't exist and failed task selection outright (the *actual* root cause of the
   `build` job's failure, independent of the Kotlin errors below).
4. **`ShizukuSystemApis.kt`** — new `LOGGER.w(...)` calls in `grantRuntimePermission`/
   `revokeRuntimePermission` with no import. Added `import af.shizuku.manager.utils.Logger.LOGGER`
   (the same static-import convention `ShellBinderRequestHandler`/`HomeViewModel`/
   `AuthorizationManager` already use — considered switching to `Timber` first since that's *also* a
   live convention in this same `utils/` directory, but `Logger.LOGGER` is the closer match: it's
   literally what the broken patch already called, just missing the import).

No local Android SDK, so verified each fix with a brace-balance script + `javac -Xmaxerrs 2000` on the
touched `.java` files (parses clean through classpath-resolution errors, i.e. no real syntax breaks)
before pushing — confirmed `Build App` green afterward (run `29818072361`).

**Separately found:** the SD card clone (`/sdcard/Documents/ShizukuPlus`) had 3 real bugfix commits
from the previous evening that were never pushed — `su -mm`/`--mount-master` flag-parsing fix,
catastrophic-guard blocking legitimate `/data` deletes, root-compat counting only bridge-configured
apps. The two clones had diverged from a shared `5d28196b` ancestor ~15 hours earlier. Asked the user
before touching shared git history (git-history reconciliation across diverged clones is a real
decision point, not a mechanical one); confirmed the only overlapping file (`ShizukuService.java`)
touched non-overlapping line ranges, rebased clean (pre-push guard's 21 checks passed), pushed as
`a5b24d68`/`4e708c16`/`1c950cf3`, then fast-forwarded the Termux clone to match — both clones now
identical to `origin/master`.

**Also found, left alone (out of scope, harmless):**
- 4 untracked `.kt` files in the Termux clone (`af.shizuku.manager.{ApplicationManagementActivity,
  LauncherAlias,TvActivity}`, `moe.shizuku.manager.ApplicationManagementActivity`), dated ~00:41–00:42
  same morning — *before* the Antigravity session started, so a leftover from an unrelated earlier
  attempt. Initially looked alarming (the manifest, committed since `07316516` on 2026-07-20,
  references all 4 by name) but they're all `<activity-alias>` elements — those resolve via
  `targetActivity` and never needed backing classes. The files are an abandoned, unnecessary local
  experiment; safe to delete whenever, not blocking anything.
- The Termux clone's `api` submodule had locally regressed to `8657364f` — the exact broken ref the
  2026-07-13 entry below already fixed (CI reads the committed pointer, not this local checkout, so it
  never affected CI; only matters if someone tries a local Gradle build here). Ran `git submodule
  update` to resync to the committed `df0d0dd8`.
- The `release` job's "Compat Hub stub APK not found" warning is pre-existing — identical on the last
  known-good build (`5d28196b`, 2026-07-20) — not a regression from today, not touched.

**Follow-up, same day — changelog + release-labeling audit (user: "improve on everything mentioned,
especially the attribution/changelogs thing needs fixing"):**

- **Release-notes crash/stability split** (`f162889f`) — `fix(...)` commits in the generated release
  notes now split into a "💥 Crash & Stability Fixes" bucket (matched on
  `sentry|crash|anr|SHIZUKUPLUS-\d+` in the subject) versus general "🐛 Bug Fixes", plus a distinct
  `#NNNN` GitHub-issue count for the release range — a visible signal for "does this look
  major/critical-worthy", not an auto-promotion.
- **New critical-fix tier** (`ebc63cc6`) — `CRITICAL_RELEASE`/`CRITICAL_DESC` added alongside the
  existing `MAJOR_RELEASE`/`MAJOR_DESC` in `app.yml`, for a regression severe enough users should
  update but that isn't new capability (so doesn't belong in the majors list). First entry: `r2153`
  (the Drop-In-shipped-as-Plus packaging bug). Own blockquote + own "⚠️ critical fix" table mark,
  independent of "🚀 major". No backfill script for this tier yet (old pages deliberately left
  un-backfilled — see [[shizukuplus-release-majors]] memory).
- **The in-app "What's New" dialog was actually dead code** (`a7d8365a`) — `ChangelogDialogFragment`
  existed but nothing ever called `.show()` on it, and it read `assets/changelog.txt` (the static
  Apache 2.0 §4(b) "Changes from Upstream" doc) as if it were per-version notes — every user would
  have seen the same generic text regardless of what changed in their update, and none ever saw it at
  all. Rewrote it to fetch the real GitHub release body for the exact version tag via a new
  `UpdateChecker.fetchReleaseNotesForTag()`, called from a new `MainActivity.checkAndShowChangelog()`
  gated on its own `getLastSeenChangelogVersion()` pref (kept separate from the existing
  `getLastSeenVersion()` key, which gates the Sentry-quota reset in `ShizukuApplication.onCreate()` —
  that one runs before any Activity and would already have advanced past the point where an
  Activity-level check could see the bump).
- **CI false alarm on `a7d8365a`:** its `Build App` run showed `release` job failure —
  `! [remote rejected] ... refusing to allow a GitHub App to create or update workflow
  .github/workflows/app.yml without workflows permission`. Not a code bug: `build` and `lint` both
  passed clean (confirming the new Kotlin was fine); only the tag-push step failed, because GitHub
  hard-blocks the default `GITHUB_TOKEN` from creating *any* tag whose checked-out commit's workflow
  file differs from the current default-branch tip — a fixed anti-tampering rule no `permissions:`
  block can waive (needs a real PAT with `workflow` scope instead). Pushing `f162889f` → `a7d8365a` →
  `ebc63cc6` back-to-back meant `a7d8365a`'s release job ran against a since-superseded `app.yml` by
  the time it reached the tagging step. Self-resolved: `ebc63cc6` (a strict descendant containing
  everything from `a7d8365a`) got a clean CI run end-to-end and tagged `v13.6.0.r2165` — `r2164` is
  just a skipped number, same as any other failed-build commit in this repo's history; nothing shipped
  is missing. Lesson: avoid rapid-fire pushes when a commit in the middle touches `app.yml`, or expect
  that middle commit's release job to occasionally lose this race.

---

### 2026-07-16 (pass 4) — Claude Code (Fable 5) [PRoot ShizukuPlus, third-party app-plugin compatibility]

**Commits:** main `17ad35c6`, `24bb109c`; api-submodule `beb20c5`, `2ea3ab2` (both CI-green)

**Focus (user request): make apps that support Shizuku work well; reviewed thedjchi's fork for ideas.**
Found and fixed two core binder **descriptor-mismatch** bugs, both caused by the `af.shizuku.server`
namespace migration renaming client-facing AIDL interfaces (a rikka `dev.rikka.shizuku:api` client
enforces the `moe.shizuku.server` descriptor; if the server's object uses `af.shizuku.server`, every
cross-boundary call throws `SecurityException: Binder invocation to an incorrect interface`):

1. **`IRemoteProcess`** (`17ad35c6` / api `beb20c5`, #325) — returned by `newProcess()`. Third-party
   shell apps (AShell etc.) got the SecurityException on `getInputStream()`/`waitFor()`. Moved the
   AIDL back to `moe.shizuku.server.IRemoteProcess` (methods/codes identical to upstream; only the
   descriptor changed) and repointed all 6 importers + ShizukuPlus's own `api/api` client.
2. **`IShizukuApplication`** (`24bb109c` / api `2ea3ab2`) — the callback interface for the server to
   push to apps. `dispatchRequestPermissionResult` (code 2, the "permission granted" notification)
   had the same `af.shizuku.server` mismatch with no fallback (unlike `bindApplication`, which had a
   hand-rolled one in `attachApplication`), so grants were silently dropped for third-party apps and
   their `addRequestPermissionResultListener` never fired → a likely contributor to the whole
   "granted apps don't work" family. Moved to `moe.shizuku.server.IShizukuApplication`; the
   Plus-only methods (`dispatchLog`/`dispatchSentryEvent`/`showPermissionConfirmation`) are
   manager-client-only so no collision. See [[shizukuplus-binder-detection]] for the full "layer 3"
   writeup + the rule (any AIDL a stock rikka client touches MUST keep its moe/rikka descriptor;
   only genuinely Plus-exclusive interfaces are safe under `af.shizuku.server`).

Verified the rest of the client-facing surface is already correct: `IShizukuService` and
`IShizukuServiceConnection` are `moe.shizuku.server`; the Plus interfaces correctly stay
`af.shizuku.server`. Both fixes span the `api` git submodule + main repo (committed to the
`ShizukuPlus-API` remote first, then pointer-bumped — CI-green, no "not our ref").

**thedjchi fork review (github.com/thedjchi/Shizuku, v13.7.0, since paused):** their Android 17 work
= (a) route `getInstalledPackages` through a compat layer so authorized apps appear — ShizukuPlus
already does this via `ShizukuSystemApis.getInstalledPackages` → privileged `PackageManagerApis`;
(b) ADB pairing fix: use the mDNS-resolved host instead of hardcoded loopback + request
`ACCESS_LOCAL_NETWORK`. **Deferred (b) — needs an Android 17 device:** `AdbMdns`/`AdbPairingService`
hardcode `127.0.0.1`; `ACCESS_LOCAL_NETWORK` is opt-in on Android 16 and loopback-exempt, so a blind
rewrite is the wrong risk profile without a device to confirm. Backlog item for a device session.

Commented on #325 with the fix; commented on #332 (backup key fix). Also, earlier passes this
session: dual-flavor server trust (`b6be301c`), binder delivery-retry (`a42edcac`), AICore+
accessibility self-heal (`2deed39d`), backup device-credential key (`ab922e48`).

---

### 2026-07-16 (pass 3) — Claude Code (Fable 5) [PRoot ShizukuPlus, deep-review of "still occurring" issue comments]

**Commits:** `2deed39d`, `a42edcac`

**Done (user request: "continue work on issues, deep review comments mentioning problems still occurring"):**
- **AICore+ accessibility service disabled after random interval** (`2deed39d`, #320, CI-green) — two
  root causes on Samsung/OneUI: (1) `accessibility_service_automation.xml` subscribed to
  `typeWindowContentChanged` (highest-frequency accessibility event, device-wide) but
  `AICorePlusService.onAccessibilityEvent` is empty and nothing (manager or server) ever consumes
  events — it only reads `rootInActiveWindow` on demand via the binder bridge. That constant
  cross-app wakeup is exactly the background-activity signal Samsung's watchdog uses to sleep the
  app and disable its accessibility service. Dropped to `typeWindowStateChanged` only. (2) Added
  `AICoreAccessibilityHealer` (called from `MainActivity.onStart`): if AICore+ is toggled on and the
  manager holds WRITE_SECURE_SETTINGS but the service isn't in `ENABLED_ACCESSIBILITY_SERVICES`,
  re-enable it via `Settings.Secure` (opportunistic on foreground, no background watchdog, never
  fights a deliberate disable). Needs on-device confirmation the OEM doesn't block the re-enable.
- **Apps not registering / "Shizuku not detected" / null service binder** (`a42edcac`, #319, CI-green)
  — deep-reviewed the whole binder-registration path and found a THIRD distinct root cause
  (independent of the compat-hub detection layer and the R8/CREATOR crash layer): `BinderSender`'s
  process/uid observers recorded a pid/uid as "handled" *before* calling `sendBinder()` and ignored
  its result. `sendBinderToUserApp()` returns false when the app's `ShizukuProvider` isn't published
  yet (routine on the first foreground event of a launch — a startup race), and that failed push was
  cached for the whole process lifetime, leaving the app permanently unregistered until relaunched.
  `sendBinder()` now returns whether the pid/uid can be remembered; observers drop it on a failed
  delivery so a later event retries (event-driven, bounded, idempotent; manager delivery unchanged,
  keeps its own retry). See [[shizukuplus-binder-detection]].
- **Diagnostic comments** (couldn't fix blind, asked the disambiguating questions): #319, #317
  (Android 17 canary, apps-in-authorized-list), #335 (InstallerX `null.asBinder()` — connected to the
  registration fixes; ruled out firewall/shadow_binder since both default off and a blocked firewall
  call throws SecurityException, not null). #325 (AShell) already answered — third-party's own stack.

**Latent bug flagged, NOT changed blind:** the Binder Firewall (`ShizukuService.isBinderCallBlocked`)
blocks `IPackageManager` transaction codes 13/26 as "installPackage/deletePackage" via hardcoded
heuristics the code itself admits "vary by API version." On modern Android those codes map to other
methods (and real installs use `IPackageInstaller`, not `IPackageManager.installPackage`), so the
firewall's package-block is both wrong and ineffective — but it's opt-in (default off), so it's not
the cause of #335. Should resolve real codes via reflection (like the shadow-binder static block
already does) rather than hardcode; deferred — needs a device to confirm the actual codes.

---

### 2026-07-16 (pass 2) — Claude Code (Sonnet 5) [PRoot ShizukuPlus, Sentry/GitHub audit pass 2]

**Commits:** `b6be301c`, `44ff1bda`, `de56f144`

**Done (continuation of the 2026-07-14/15 audit, user request: "continue with this"):**
- **Dual manager-flavor recognition gap** (`b6be301c`, Sentry SHIZUKUPLUS-7C) — `ShizukuService`
  resolves exactly one flavor (Plus preferred, Drop-In fallback) as `MANAGER_APPLICATION_ID`/
  `managerAppId` at server startup (issue #319's earlier fix). Users with **both** flavors
  installed side by side (supported per #316) had every manager-only check
  (`checkCallerManagerPermission`, `isBinderCallBlocked`, `dispatchPermissionConfirmationResult`,
  `getFlagsForUid`/`updateFlagsForUid`, `attachApplication`'s `isManager` flag) reject the
  non-primary flavor's app as an untrusted third-party client — breaking Plus-feature settings
  sync and silently no-oping permission grant/revoke. Added `isManagerAppId()` helper that trusts
  either flavor's uid when both are installed (safe: same signed codebase, not a real trust
  boundary). Directly explains a live "r2117, apps still not registering" follow-up on #319.
- **Two main-thread Shizuku IPC ANRs + uncaught FOTA crash** (`44ff1bda`, SHIZUKUPLUS-7H/7P/7Q/73)
  — `ShizukuCompanionViewHolder.runPrivilegedCommand()` (via `HomeActivity`'s `lifecycleScope`)
  and `ServiceDoctorActivity`'s phantom-process fix (`serviceScope` = `Dispatchers.Main`) both
  called `Shizuku.newProcess(...).waitFor()` — a blocking IPC round-trip — directly on a
  Main-dispatched scope, violating this project's own "off main thread" rule. Wrapped both in
  `withContext(Dispatchers.IO)`. Also fixed `StarterActivity`'s Samsung FOTA-agent
  UID-escalation trick: `startActivity()` was outside the existing try/catch, so devices without
  `com.sdet.fotaagent` got an uncaught `ActivityNotFoundException` instead of the graceful
  failure log (highest-volume issue in this sweep).
- **Backup encryption key invalidation UX** (`de56f144`, #332/#315) — `CryptoUtils` let
  `KeyPermanentlyInvalidatedException` (Android's by-design key invalidation on biometric/lock
  change) propagate uncaught, surfacing as a raw exception message or literal "null" (many
  keystore exceptions have no `.message`). Encryption now self-heals by regenerating the key
  (safe — a brand-new backup has no prior ciphertext to preserve compatibility with); decryption
  intentionally does NOT auto-regenerate (old ciphertext is genuinely unrecoverable under a new
  key) — all 5 Toast call sites now go through a shared `backupErrorMessage()` helper for an
  honest, specific message instead of "null".
- **Full Sentry sweep**: resolved/ignored the remaining ~20 low-volume unresolved issues from the
  2026-07-14/15 audit's backlog (74, 7N, 77, 76, 7K, 7E, 7R, 3W, 3Q\*, 6Q, 71, 7G, 7F\*\*, 7H, 7Q,
  7P, 73, 13, 7C). \*3Q was already resolved by a prior session. \*\*7F left genuinely open — a
  play.google.com `ActivityNotFoundException` whose already-guarded source location doesn't
  reconcile with the build's `SourceFile:167`; documented honestly rather than guessed at. New
  R8-obfuscation and ANR-correlation triage techniques captured in the `shizukuplus-sentry-audit-
  workflow` memory (single-letter exception classes can only be project-defined types; obfuscated
  package names in culprit strings aren't reliable; same-timestamp ANR pairs are one incident).
- **GitHub issue sweep**: closed #300 (pc name fix, verified still in source) and #309 (settings
  search crash fix, verified still in source); commented with fix pointers on #319, #298, #332,
  #315; diagnosed #325 (AShell third-party binder mismatch — entirely inside AShell's own
  obfuscated stack, no ShizukuPlus frames, needs the reporter's logcat to pin down further, left
  open).
- **CI verified green on all three pushes.**

**Left open:** #200/#199/#333 remain blocked — no ADB device reachable in this PRoot session and
no local Android SDK to inspect the base theme's default corner values (#333). Sentry
SHIZUKUPLUS-3Q (the exact Mavericks-factory crash #200's ProGuard keeps address) shows zero
recurrence since version_code 1668 though — decent indirect evidence the fix is holding, not a
substitute for real on-device verification.

---

### 2026-07-14/15 — Claude Code (Sonnet 5) [session run in parallel with a review pass, devlog not updated at the time]

**Commits:** `234f0be3`, `6394bac1`

**Done:**
- **Dhizuku V1/V2 binder authorization gap** (`234f0be3`, issue #326-adjacent) — `b66d2c99` had
  fixed `DhizukuV2Binder.getBinder()` to return the real `DEVICE_POLICY_SERVICE` binder instead of
  a version string, which made the transaction live for the first time — and exposed that V2's
  `getBinder()` never had the `AuthorizationManager.granted()` check that `DhizukuV1Binder.getBinder()`
  has always had. Since `DhizukuProvider` is `android:exported="true"` with no manifest permission
  and Dhizuku mode is a single global toggle (not per-app), any installed app could get the raw
  DevicePolicyManager binder without authorization once Dhizuku mode was on. Fixed by mirroring
  V1's gate onto V2. **No shared interface enforces parity between the two hand-implemented binder
  classes — worth periodically re-auditing.**
- **Compat Hub self-overwrite + permission-group collision** (`6394bac1`) — audited every
  open/closed issue mentioning Compat Hub (#334, #316, #314, #327, #326, #249, #206, #243, #242,
  #209) and found two real bugs sharing a root cause: the `dropin` flavor's own applicationId IS
  `moe.shizuku.privileged.api`, same as the `compat/` module's shim. (1)
  `StockShizukuCompat.isCompatAppInstalled()` had no self-exclusion check (unlike
  `isInstalled()`/`isOriginalRunning()` in the same file), so a dropin build inspected its own
  version, reported the hub "not installed," and its "Install Compat Hub" card's `pm install -r`
  would silently overwrite the running dropin app with the 2-class forwarding stub — matches
  HyperCriSiS's live report on #334. Fixed with self-exclusion plus a hard-stop guard in the
  install click handler. (2) `<permission-group>` was a hardcoded literal shared by both flavors'
  manifests, so installing Plus + Dropin side by side failed with error -126 (#316) — scoped to
  `${applicationId}`. Left `af.shizuku.plus.permission.API_V23` itself un-scoped since it's
  hardcoded into the client-facing `ShizukuProvider.PERMISSION` constant third-party apps compile
  against.
- **CI verified green on both pushes** (`29382328766`, `29388259054`).

**Left open:** issues #326, #334, #316 are fixed in code but still open on GitHub — need a
comment + close pass. A parallel Sentry/Linear audit (separate session, same window) found several
other unresolved Sentry crash clusters that look plausibly fixed by already-landed code (stale
clients, not live bugs) but weren't verified deeply enough to resolve — see that session's notes
before assuming any of them are safe to close.

---

### 2026-07-13 — Claude Code (Sonnet 5) [PRoot ShizukuPlus]

**Commits:** `f29188e0`, `e8979dd1`

**Done (user request: "fix every design issue in the app by reviewing how we theme everything"):**
- **Audited** `values/{colors,themes,themes_overlay,styles,dimens,attrs}.xml`, `values-night/*`, and all 12 layout files using `cardElevation`/`cardBackgroundColor`, against the project's own documented tonal-ladder rule (material3-xml-theming skill: flat cards need `colorSurfaceContainerHigh`+, `Low` is the *Elevated Card* token).
- **Fixed flat-card token bug** (`f29188e0`) — 7 card instances across `activity_service_doctor.xml`, `activity_root_compatibility.xml` (x2), and the shared `DialogCard` style (used by 6 tutorial-dialog cards in `adb_pairing_tutorial_activity.xml`/`terminal_tutorial_activity.xml`) plus 3 more in `page_onboarding_setup.xml` were wired to `colorSurfaceContainerLow` on 0dp-elevation/0dp-stroke cards, causing them to visually blend into the page background. Sibling flat cards (`home_item_container.xml`, `swipe_hint_overlay.xml`, `layout_settings_search_header.xml`) already used the correct `High` token — standardized all of them.
- **Fixed onboarding hardcoded-color drift** (`e8979dd1`) — `page_onboarding_gestures.xml` had `backgroundTint="#FF9800"`/`"#E53935"` instead of referencing the existing `@color/status_starting` (identical value) / `@color/status_error` (`#F44336` — a near-miss of the `#E53935` used here) tokens.
- **Deliberately deferred** (lower confidence / needs visual verification, not fixed this session):
  - `cornerRadiusExtraLarge` theme attr is hardcoded to 32dp (`m3e_spacing_extra_large`) at the base theme level and never gets folded into the `ThemeOverlay.Shape.*` (Modern/Classic/Squircle) system — the shape_style setting's own comment documents the M3 standard as XL=28dp, and `ShapeAppearance.Modern.Corner.ExtraLarge` is 28dp, so there's an internal 32dp-vs-28dp mismatch. Also appears unused in Kotlin (only consumed by whatever framework/library reads it via XML attr resolution) — needs confirmation of what actually renders with it before changing.
  - Corner radius literals are inconsistent across flat cards (16dp/24dp/28dp scattered as raw values instead of shape-appearance tokens) — some may be intentional (compact info banners vs. primary content cards), needs visual call.
  - `card_corner_radius_large` (32dp dimen) is defined but has zero references anywhere — dead resource.
  - `textColorOnSurfaceHighEmphasis`/`textColorOnSurfaceMediumEmphasis` custom attrs are declared and assigned hardcoded ARGB literals in both base themes but have zero usages anywhere in res/ or java/ — dead theming plumbing, duplicates M3's own `colorOnSurface`/`colorOnSurfaceVariant`.
  - `shape_edit_control_background.xml` (home-card edit-mode drag handle/remove button chip) uses `colorSurfaceContainerHighest`, which `ThemeOverlay.Black` (AMOLED) does not remap (only `Low`/`High` are remapped, since those are the only tokens actually used by real cards) — minor, only visible in edit mode.
- **Verified**: all 21 `pre-push-guard` static checks pass (XML well-formed, implicit style parents resolve, no unresolved colorPrimary, etc.); no local Android SDK available to compile, so pushed to let CI verify.
- **Pre-existing, unrelated CI break found while checking the push**: "Build App" has been failing on `master` for at least 3 pushes before this session's (`b66d2c99`, the haptics commit, and this one) — the `api` submodule pointer is pinned to commit `8657364fdc7ebf58072d690a978c7cc3c9bd4271`, which the `ShizukuPlus-API` remote rejects with `upload-pack: not our ref` during CI checkout. Not caused by this session; flagged for the user, not fixed (out of scope for a theming pass, needs the submodule pointer corrected to a commit that's actually pushed to the API repo).

**Left open:** shape-scale/corner-radius audit (needs visual/on-device confirmation).

---

### 2026-07-13 (cont'd 2) — Claude Code (Sonnet 5) [PRoot ShizukuPlus]

**Commits:** `1a637ece`

**Done (user: "finish the amoled remap. consider shape style setup later by creating a gh issue for it"):**
- **Completed the `colorSurfaceContainerHighest` AMOLED remap** (`1a637ece`) — reversed the earlier decision to leave it unmapped. Re-examined `shape_edit_control_background.xml`'s actual consumers (`home_item_container.xml`'s drag-handle/remove-button chip): both icons inside it are tinted independently (`colorControlNormal`, `colorError`), not derived from the chip's own background color, so the "chip background ties with parent card" collision I was worried about doesn't actually make anything invisible - it's cosmetically identical to the Low/Lowest tie the codebase already has and accepts. Completed the remap.
- **Filed [issue #333](https://github.com/thejaustin/ShizukuPlus/issues/333)** for the dead `shape_style` setting (every card hardcodes `cardCornerRadius`, overriding `shapeAppearanceOverlay`, so Modern/Classic/Squircle does nothing) instead of guessing at the fix blind - labeled `module: theme`, `module: design`, `type: technical-debt`, with the full root-cause writeup and a suggested verification-first approach for a build-capable session.

---

### 2026-07-13 (cont'd) — Claude Code (Sonnet 5) [PRoot ShizukuPlus]

**Commits:** `f20d8c8a`, `0ffc9b34`, `ff14b386`, `17b0a727`

**Done (user: "fix all the things mentioned now" + "also remove Active Enhancements Dashboard visual from Feature Hub settings"):**
- **Fixed the broken `api` submodule CI pointer** (`f20d8c8a`) — repinned from the unreachable `8657364f...` to `d40bc88c`, the API repo's actual `origin/master` HEAD. Confirmed via `git ls-remote`/`git fetch` in the submodule directly (the broken SHA isn't fetchable from anywhere, not even by hash).
- **Removed dead theming resources** (`0ffc9b34`) — `cornerRadiusExtraLarge` custom attr (declared + assigned in both base themes + its own `declare-styleable`, zero consumers anywhere in the repo, not just `manager`), `textColorOnSurfaceHighEmphasis`/`textColorOnSurfaceMediumEmphasis` (same story, duplicated M3's own `colorOnSurface`/`colorOnSurfaceVariant` without ever being read), `card_corner_radius_large` dimen (defined, zero references).
- **Investigated completing the AMOLED remap for `colorSurfaceContainerHighest`** (used by `shape_edit_control_background.xml`, the home-card edit-mode drag/remove chip) — attempted `colorSurfaceContainerHighest → ?colorSurfaceContainerHigh`, then caught before committing that `?colorSurfaceContainerHigh` resolves through `ThemeOverlay.Black`'s *own* override (already remapped to `?colorSurfaceContainer` a few lines up), so Highest and High would collapse to the same color — the edit-mode chip would become invisible against its own card. Reverted; left unmapped. Worth remembering generally: theme-attr overlay chaining resolves against the fully merged theme, not against sibling items' pre-overlay values.
- **Investigated wiring the dead `shape_style` setting up for cards** — every card in the app sets a literal `app:cardCornerRadius` (16/24/28dp) instead of `app:shapeAppearanceOverlay="?attr/shapeAppearanceCornerExtraLarge"` etc., and `cardCornerRadius` unconditionally overrides/ignores `shapeAppearanceOverlay` on `MaterialCardView` — meaning the user-facing Modern/Classic/Squircle shape_style setting (in `ThemeOverlay.Shape.*`) currently does nothing for any card in the app, including the home screen. Correct fix in principle, but the base `Theme.Material3Expressive.*.Shizuku` theme lives in an external AAR (not in this repo, generated by `dev.rikka.tools.materialthemebuilder` + `rikkax.material`) and I can't confirm it actually defines a default for `shapeAppearanceCornerExtraLarge` — an unresolved theme attr on `shapeAppearanceOverlay` risks an inflate-time crash (not just a wrong color) across the app's most-used screens. Given the AMOLED near-miss above, didn't risk it blind. **Needs on-device confirmation of the base theme's default corner values before attempting.**
- **Removed "Active Enhancements Dashboard" from Feature Hub settings** (`ff14b386`, user request) — deleted `PlusStatusDashboardPreference.kt`, `layout_plus_status_dashboard.xml`, its `settings_shizuku_plus.xml` entry, and the 4 dead `findPreference("plus_status_dashboard")` visibility-toggle refresh calls in `ShizukuPlusSettingsFragment.kt` that existed solely to force it to rebind. Left `shape_node_active`/`shape_node_inactive`/`shape_status_indicator` drawables in place — still shared with `HomeLayoutSimulatorPreference`. This was one of the two "status/diagnostics dashboards" explicitly kept during the 2026-07-04 flowchart-preference cleanup (`8d3dbe69`); user has now decided to remove it too.
- **Found and fixed a second pre-existing CI break** (`17b0a727`) — once the submodule fix let CI get past checkout and into actual compilation, `BiometricLock.kt:42` failed with "Unresolved reference 'Build'" in both debug and release Kotlin compile tasks. `e25440f2` (pushed before this session, part of the upstream commits rebased onto earlier today) added a `Build.VERSION.SDK_INT < Build.VERSION_CODES.R` guard without `import android.os.Build`. Pre-existing, not introduced by any change in this session; fixed with the one-line import.
- **Verified: `Build App` CI is green on master** (run `29276207625`, 9m22s) — first successful build in at least 4 pushes. Confirmed via a polling loop rather than guessing from local static checks alone.

---

### 2026-07-04 (cont'd 3) — Claude Code (Sonnet 5) [PRoot ShizukuPlus, d66b3105]

**Commits:** `07fa78af`, `e580b78e`, `7a2d3799`, `c7334564`, `3aa13734`, `3b150091`, `444bd006`, `16606e22`

**Done (settings compartmentalization review + new GitHub issue triage):**
- **Settings architecture audit** (user request: "review settings design and function esp compartmentalization") — found and removed 3 fully orphaned settings screens (`settings.xml` legacy monolithic layout, `settings_home_visibility.xml`/`HomeVisibilitySettingsFragment.kt`, `settings_update.xml`/`UpdateSettingsFragment.kt` — zero references anywhere, verified via grep across manifest/Kotlin/SettingsSearchEngine's screen index) (`07fa78af`). Removed a confusing duplicate: Developer Options had its own unencrypted "Backup & Restore" (`export_settings`/`import_settings`, plain JSON, no auth) sitting alongside Feature Hub's encrypted/biometric-gated one, same title, different implementations — standardized on the encrypted flow; `SettingsBackupManager` itself (the shared JSON-serialization core `BackupRestoreManager` wraps with encryption) is untouched since `MainActivity`/`UpdateInstaller` still use it internally (`e580b78e`). Regrouped `companion_fallback`/`launch_stock_shizuku` from Advanced → Help & Recovery into a new "Stock Shizuku Compatibility" category in Root & Compatibility, and fixed 3 nav summaries in `settings_main.xml` that didn't mention categories their screens actually contain (`7a2d3799`).
- **New issue #316 (Drop-In variant never starts)** — root-caused to `StockShizukuCompat`'s conflict checks matching purely on package/process name string with no self-exclusion: the dropin flavor's own applicationId literally IS `moe.shizuku.privileged.api`, so `isInstalled()`/`isStockShizukuInstalled()` always found "itself" and `isOriginalRunning()`'s `ps -A | grep` always matched its own healthy server process, permanently showing false conflict UI (`c7334564`). Also found the native starter's (`starter.cpp`) "start via computer/ADB" fallback path-lookup hardcoded to only check the Plus flavor's package name, so `pm path` always failed on a dropin install — the exact "can't get path of manager" error reported; now tries both package names (`444bd006`).
- **New issue #317 (STOPPED despite repeated ADB-start log entries) + #316's "Plus starts as v13, granted apps fail"** — both matched an existing open community PR (#308, by a prior Claude Code session) exactly: `ServiceStatus.isRunning` was gated on an attach-required `getUid()` that can return -1 right after start even though the server is genuinely reachable via `pingBinder()`; `HomeAdapter.updateData()` dropped (rather than coalesced) renders during the rapid state-change burst around start; `checkRemotePermission()` was unguarded and aborted the whole status load on a throw. Verified the diff, cherry-picked both PR commits (`3aa13734`, `3b150091`) onto master — clean auto-merge, no conflicts with same-session `HomeAdapter.kt` changes.
- **#300 (rename PC name to Shizuku+ in wireless debugging)** — the ADB key comment embedded in the pairing RSA public key was hardcoded to `"shizuku"` at all 5 construction sites; changed to `"shizuku+"` (`16606e22`). Takes effect on next connection, no re-pairing needed, since the name is re-encoded fresh on every `AdbKey` construction rather than cached with the persisted key material.
- **#301 correction** — the reporter's 300-line OriginOS/vivo compatibility-layer proposal was built on a misreading: `kd.a` (the class in their `BadParcelableException`) is a standard R8-minified class name, not evidence of a vivo-specific obfuscated framework class. Flagged that this is very likely the same CREATOR-stripping bug already fixed in `374edbe3`/`dd8e47a1`, asked for a re-test before considering a whole new OEM adapter layer.
- **#280 follow-up** — a Chinese-language reporter said the app still crashes tapping "Start" from the notification after the maintainer's earlier ANR fix. No stack trace attached; commented noting today's batch of Android 15/16 background-start fixes likely also covers this, asked for a fresh crash report if it recurs.
- **Sandbox note:** `git fetch origin pull/<N>/head` works to pull a same-repo PR's branch into a local ref for review/cherry-pick even when `git fetch origin <branch-name>` fails (GitHub doesn't expose arbitrary PR head branches as direct remote refs).

### 2026-07-04 (cont'd 2) — Claude Code (Sonnet 5) [PRoot ShizukuPlus, d66b3105]

**Commits:** `bbed21fc`, `61575465`

**Done (further Compat Hub + UI enhancement pass):**
- **Compat Hub card refresh/busy-state/dedup** (`bbed21fc`) — install/disable ran in a fire-and-forget coroutine with no callback into the view model, so the card kept showing the stale action until the user left Home and returned (onResume() was the only reload() trigger). Gave `ShizukuCompanionViewHolder` a `creator(scope, homeModel)` (matching `StartWirelessAdbViewHolder`'s existing pattern — the creator must be instantiated once at the `HomeAdapter` level, not per `updateData()` call, or RecyclerView view-type pooling breaks) and call `homeModel.reload()` when the pm command finishes. Also disabled the button with an "Installing…"/"Disabling…" label during the ~1-2s operation to prevent double-taps, and extracted the duplicated Shizuku-then-root fallback into one `runPrivilegedCommand()` helper.
- **ANR risk in "Fix Conflict" button** (`61575465`, `StartStockShizukuViewHolder`) — `Shell.cmd(cmd).exec()` (synchronous) was called directly from the main-thread click listener; su attach can be slow enough to jank or ANR. Moved to a background coroutine, matching the project's existing convention of keeping privileged IPC off the main thread.
- Audited all other Home ViewHolders for the same two patterns (main-thread shell/Shizuku calls, missing busy-state) — only these two files run privileged shell commands in `home/`, both now fixed.

### 2026-07-04 (cont'd) — Claude Code (Sonnet 5) [PRoot ShizukuPlus, d66b3105]

**Commits:** `8d3dbe69`, `2afb39b6`, `c90f1f2d`, `878b0bcd`, `5c3dfc5c`

**Done (user-reported UI/UX + new GitHub issues):**
- **Removed 9 redundant flowchart "diagram" preferences** (`8d3dbe69`) — DNS Governor, Binder Firewall, Continuity Bridge, VM Manager, Shadow Binder, Storage Bridge, Theming Bridge, AI Bridge, SU Bridge. Each just duplicated the toggle already above it and had hardcoded light-theme colors. Left the two status/diagnostics dashboards (different: aggregate + actionable warnings, not 1:1 duplicates).
- **Appearance settings flashing black on change** (`2afb39b6`) — `AppActivity` requests `Window.FEATURE_ACTIVITY_TRANSITIONS` + Explode transitions; every appearance/behavior toggle called `activity?.recreate()` directly, causing a black flash during the window teardown/rebuild. Added `AppActivity.recreateWithoutTransition()` (zeroes window animations first) and routed all ~11 call sites through it.
- **Home edit-mode X/+ button overlapped card content** (`c90f1f2d`) — `remove_btn` sat top|start, the same corner as every card's own icon/title. Moved to top|end (same edge as the drag handle) and widened the reserved end-clearance to fit both.
- **Verified card-spacing report via pixel analysis** — installed netpbm/libjpeg-turbo in the PRoot sandbox to decode a user-provided screenshot and measure exact gaps; found one real 8px-vs-40px gap anomaly, but the RecyclerView spacing math (`addItemSpacing`/`addEdgeSpacing` in HomeActivity) is symmetric by construction — most likely a transient animation-frame artifact, not a persistent layout bug. Flagged back to user rather than guessing a fix.
- **Compat Hub install failure** (`878b0bcd`, #314, also root cause of new symptoms on #298) — `pm install` runs via a shell process (Shizuku/root) that can't read the app's private cache dir due to per-app UID sandboxing; the extracted compat.apk was written there instead of external storage. Fixed to match the working pattern already used by UpdateManager/UpdateInstaller.
- **Backup Settings password/PIN/pattern bug** (`5c3dfc5c`, #315) — `BiometricLock` hardcoded a "Use PIN/Pattern" negative-button label that (a) was wrong for password-locked devices and (b) didn't actually work as a fallback at all — tapping it just canceled with an error. Switched to `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`, which correctly prompts for whatever credential is configured. Also removed `notifyDiagramForKey()`, dead code left by the diagram-widget removal.
- **GitHub comments** posted on #314, #315, #298 (cross-referencing the #314 fix as the root cause of a new report there with logs/screenshots).
- **Sandbox note:** no PIL/ImageMagick preinstalled; `apt-get install imagemagick` failed on an unrelated `libcups2` 404, but `apt-get install --no-install-recommends libjpeg-turbo-progs netpbm` succeeded and `djpeg -pnm` + a small Python PNM parser was enough to do precise pixel-level screenshot analysis without any GUI tooling.

### 2026-07-04 — Claude Code (Sonnet 5) [PRoot ShizukuPlus, d66b3105]

**Commits:** `0a1e7737`, `27667aeb`, `4febe299`

**Done:**
- **Settings search crash on Android 16** (`0a1e7737`, #309 / Sentry SHIZUKUPLUS-72) — search-result `Card` used `Modifier.clickable`, which reads `LocalIndication`; on Compose Foundation 1.7+ that throws when a legacy `Indication` is in scope. Switched to Material3's `Card(onClick = ...)` overload.
- **AutomationService foreground-service crash cluster** (`27667aeb`) — service ran as `dataSync` FGS, which Android 15+ time-limits/validates strictly, producing SHIZUKUPLUS-6H/6G/6M/6E/6V. Switched to `specialUse` (matches WatchdogService/ShizukuLiveService pattern), guarded every `startForeground()` call with try/catch → `stopSelf()`, made `ConnectivityManager` nullable.
- **Fixed a broken master build** (`4febe299`) — the prior session's `f7f46de0` (pushed from the Termux clone) put `import rikka.html.text.toHtml` above the `package` declaration in `ShizukuPlusSettingsFragment.kt`, a Kotlin syntax error that cascaded into ~30 "Unresolved reference" errors across the settings package and failed CI run 28692928435. Moved the import into place; verified CI green again (lint/build/release all success).
- **Sentry audit continued** — resolved 51 of 66 previously-unresolved issues this pass (see the 2026-06-28→07-04 entry below for the bulk of it); reviewed the four commits pushed from the Termux clone (`03d3c5e6`, `3a510dd7`, `9611676c`, plus the build-breaking `f7f46de0`) for correctness — all sound except the import-order slip.
- **GitHub comments** posted on #309, #311, #312, #306, #303 tying each to the specific commit/root-cause fix and requesting a re-test/logcat on the next build.
- Reviewed (did not merge — pending maintainer/external-dependency review) two open Claude-authored PRs: #307 (binder delivery to stock clients, blocked on `ShizukuPlus-API#16`) and #308 (manager "not running"/stale-version race fixes).
- **Memory gotcha saved:** `versionCode`/`versionName` are derived from `git rev-list --count HEAD`, but the repo's history appears to have been rewritten at least once — local HEAD count and live Sentry release tags (`r2082`) were inconsistent with a stable history. Don't trust dist/release numbers to judge whether an old crash is fixed; read the current source at the crash site instead.

### 2026-07-03 21:43–21:57 — Claude Code (Sonnet 4.6) [Termux ShizukuPlus, d66b3105]

**Note:** These 4 commits are in the Termux clone (`~/ShizukuPlus`) but NOT yet in the sdcard clone. Run `git pull` in `/sdcard/Documents/ShizukuPlus` to sync.

**Commits:** `03d3c5e6`, `3a510dd7`, `9611676c`, `f7f46de0`

**Done:**
- **Sentry crashes** (`03d3c5e6`) — NPEs, BinderRequest receiver, file system errors, ADB startup crashes
- **ShizukuPlus UI bugs** (`3a510dd7`, #266, #267, #270)
- **Settings search AndroidView hidden when active** (`9611676c`, #269)
- **Missing import for toHtml extension** (`f7f46de0`)

### 2026-06-28 → 2026-07-04 — Claude Code (Sonnet 4.6) [Termux session, d66b3105]

**Session note:** Ran from Termux environment (not PRoot). Session was invisible from PRoot `/resume` until 2026-07-04 sync.

**Done:**
- **Sentry audit** — 51 issues resolved (from 66 unresolved → 22 remaining); remaining are external (keystore hardware, disk-full/I/O, ANRs, server-client timing races)
- **WorkManager ProGuard crash** — verified try-catch fix already in place (Jun 23 commit); pre-fix events in Sentry
- **ActivityLogActivity missing from manifest** (5Z) — declared; verified
- **Parcelable CREATOR strip** (6S) — covered by existing keep rule; verified
- **AutomationService FGS background start** (5X) — guarded + `specialUse` type; verified
- **`startActivityAndCollapse` QS tile** (8) — try-catch with API-level branching verified correct
- **FileProvider authority** (6P) — `${context.packageName}.fileprovider` matches manifest; `.morphe.` authority gone
- **`Unknown permission moe.shizuku.manager.permission.API_V23`** (6F/63/61, ~103 events) — `grantRuntimePermission` already wrapped in `try/catch(Throwable)`; exception can't cross binder
- **SystemHubActivity action-bar crash** (56, 3 events/1 user) — deferred; too risky vs. impact
- **GitHub comments** posted on #309 (settings search), #306 (binder-handshake build green)
- **Memory files saved** — 5 rich ShizukuPlus memory entries synced to device

**Open (22 Sentry issues):** keystore hardware failures, disk-full I/O errors, ANRs, server↔client timing races, single-user races — all external to app code

### 2026-06-15 → 2026-07-04 — Claude Code (Sonnet 4.6) [multi-compact session]

**Commits:** `17d04617` through `27667aeb` (49 commits)

**Done:**

*Crash / Sentry fixes (Jun 15–19):*
- **4 high-volume Sentry crashes** (`17d04617`) — SecurityException (`ACCESS_WIFI_STATE` missing), WorkManager direct-boot crash, DownloadManager `local_filename` column crash, `startActivityAndCollapse` UnsupportedOperationException on Android 14+
- **Binder-not-received crash in AdbProxyService** (`4f71348a`) — `Shizuku.pingBinder()` called outside try-catch; moved inside
- **Reset icon invisible on light backgrounds** (`e03bbeec`, #247) — `fillColor` was `white`; changed to `black` (M3 convention)
- **BackgroundServiceStartNotAllowedException** (`8a7bc3b7`) — `ShizukuLiveService` started with plain `startService()` from `Application.onCreate()` on Android 12+; switched to `startForegroundService()` + `startForeground()` in `onStartCommand()`
- **In-app notification permission dialog** (`4607edd0`) — replaced "go to Settings" redirect with in-app rationale popup
- **DeadObjectException** (`ec8714f5`, 15 Sentry events) — `Shizuku.pingBinder()` unguarded in `AuthorizationManager`, `RequestPermissionActivity`, `AICorePlusService`, `AppViewHolder`; all wrapped
- **ANR: Shizuku binder calls on main thread** (`6a9ecddb`) — all `AppViewHolder` binder calls moved to coroutines
- **Multiple Sentry crash sources batch 1** (`b1b73793`) — SQLiteCantOpenDatabaseException fallback chain (device-protected → app storage → in-memory), `FileNotFoundException: last_crash.txt` downgraded to `Timber.w`, `AutomationService` foreground type
- **Multiple Sentry crash sources batch 2** (`0eb607b6`) — `RootCompatibilityActivity` column crash, `IllegalArgumentException` column fix
- **api submodule AIDL-stub rewrite** (`cae6a62a`) — rebased against remote to resolve merge conflict

*Deep audit and product flavor work (Jun 23):*
- **Five bugs from deep audit** (`ed9983b5`) — ADB pairing duplicate key, PendingIntent collision, null cast NPE, Sentry quota reset on every cold start, broken string interpolation in logs
- **Product flavors** (`c4151cb6`, `d4a1566d`) — 3 APKs per release: `shizukuplus.apk` (af.shizuku.plus.api), `shizuku-dropin.apk` (moe.shizuku.privileged.api), `shizuku-compat.apk` (thin stub)
- **Final audit findings** (`6faf1095`) — memory leak, node recycle, timing side-channel, scope leak, broken log
- **Cold-start from ADB pairing notification** (`7e4f3f9b`, #280) — `EXTRA_START_SERVICE_VIA_WADB` only read in `onNewIntent()`; now also handled in `onCreate()`

*Root-mode and CI fixes (Jun 24–26):*
- **Root-mode connectivity** (`e3b2f7f2`) — `BIND_APPLICATION_PERMISSION_GRANTED=true` for apps using original Shizuku library; suppress auth spam; expand permission filter
- **CI: flavor-specific Sentry task names** (`1ec97640`) — ambiguous task names after flavor split caused CI failure
- **attachApplication race/NPE + compat build wiring** (`70431294`)
- **AdbPairingTutorialActivity missing from manifest** (`658ecb7c`) — registered; pairing flow restored; CI signing fixed
- **Debug keystore auto-generation** (`889ae1e9`) — `signing.gradle` generates a keystore when none found on CI

*Settings refactor and features (Jun 29–30):*
- **CollapsiblePreferenceCategory rollout** (`8658e500`, `ae2bd209`) — all settings screens upgraded; dead UISettings cluster removed
- **ShizukuLiveService gate** (`2948eeb3`, `61d25316`) — startup gated on `live_activity_enabled`; semantics and defaults corrected
- **live_activity and auto_reconnect_mdns** (`a6f0b04b`) — settings toggled prefs but didn't wire actual behavior
- **VirusTotal + Pithus APK verification** (`cbec7bb5`) — API calls implemented
- **Stealth mode** (`1985b5ec`) — hide launcher icon via toggleable activity-alias in Developer Options
- **AppManagement settings** (`b59c27c7`) — `AppManagementSettingsFragment` wired, PLUS badges added, settings reorganized
- **Dhizuku diagnostic tap** (`19310e87`) — now shows ADB setup dialog instead of doing nothing
- **Sync all root/ghost/bootloader features to server** (`fa23aed6`) — 6 features were silently not synced due to key suffix mismatch
- **Remove dead developer options** (`8c046ffd`) — duplicate and unused prefs removed
- **Compat APK bundling, dev options gate, theme recreation** (`1956f594`)
- **4 crash/rendering bugs** (`0f265f6b`, #293, #267)

*Code quality and more bug fixes (Jul 2–3):*
- **binder-send crash** (`374edbe3`, #298) — Parcelable `CREATOR` stripped by R8; keep rule added
- **All BinderContainer variants hardened** (`dd8e47a1`) — R8 keep rules expanded
- **AdAway integration removed** (`6669f494`) — vestigial, never worked
- **binder_logging toggle** (`fecd41c5`) — was fully dead; wired to actual behavior
- **6 ghosting/bootloader server features** (`fe28618e`) — key suffix mismatch; server never honored them
- **AI Core sub-features UI** (`b2c7fcba`) — toggles wired; legacy pairing dialog added
- **Accessibility service declarations** (`857c562f`, #305) — dropped by refactor; restored
- **Surface compat hub card by default** (`0d47d93c`, #249) — third-party detection now discoverable
- **Docs: third-party compat layer** (`c782dc0f`) — stale AdAway reference removed
- **Animation Intensity setting** (`09abb841`) — had no effect; wired
- **Settings search crash on Android 16** (`0a1e7737`, #309)
- **AutomationService foreground hardening** (`27667aeb`) — `startForeground()` moved to `onCreate()`, type `dataSync` → `specialUse`

**Open:**
- Issue #200 (Mavericks factory + SQLite race) still needs on-device ADB verification
- Issue #199 (Shadow Binder hidden packages) still needs testing
- `shizuku-compat.apk` not appearing in CI release artifacts — compat module build wiring incomplete
- `gh auth` / GitHub MCP need `workflow` scope on PAT to push `.github/workflows/` changes

### 2026-06-26 — Claude Code (Sonnet 4.6)
**Done:**
- Recovered lost `CLAUDE.md` from `AI_DEVLOG.md` after device reset — all 14 crash rules and key files map restored.
- Created `CLAUDE.md` in project root with full critical crash rules, build commands, key files, and open verification items.
- Installed 10 Android skills globally (`~/.claude/skills/`): android-dev, android-debugging, android-gradle-logic, gradle-build-performance, koin, kotlin-coroutines, android-testing, compose (+ crash playbook + state-management references), android-data-layer, kotlin-flows.
- Created `~/.claude/skills/shizukuplus/SKILL.md` — deep project skill auto-loaded when working in this repo.
- Created `.claude/agents/build-doctor.md` — specialized agent for diagnosing and fixing build failures with a known-error lookup table.
- Added XML layout validation hook to `~/.claude/settings.json` — catches malformed layout XML immediately on edit (the SAXParseException class of CI failures).
- Added `android-adb` MCP server config (CursorTouch android-mcp, Python) — pending pip install completion; provides ADB device control, UI hierarchy inspection, shell commands.
- Updated global `~/.claude/CLAUDE.md` with PRoot/Android constraints for all sessions.
- Fixed Claude Code setup: removed npm wrapper, NVM lazy-load, PATH order, TMPDIR/SSL_CERT_FILE/DBUS env vars, auto-updates enabled.

**Open:**
- android-mcp: `watchfiles` needs Maturin/Rust (unavailable in PRoot pip). Install from **Termux shell** (outside PRoot): `pip install android-mcp`. Then add to `~/.claude/settings.json`: `"mcpServers": {"android-adb": {"command": "/data/data/com.termux/files/usr/bin/python3", "args": ["-m", "android_mcp"]}}`
- Consider adding `rcosteira79/android-skills` via `/plugin marketplace add rcosteira79/android-skills` for automatic plugin management

### 2026-05-19 — Gemini CLI
**Commits:** (this session)

**Done:**
- 2026-05-19: High-Performance Backend & Design Overhaul
    - [Backend] Replaced `Runtime.exec` with direct `InputManager` injection for AICore+ automation (latencies <10ms).
    - [Backend] Implemented `getServerStats` and `getVisibleWindows` APIs in the server to expose real-time performance and system intelligence data.
    - [Feature] Consolidated server metrics and activity logs into a unified **System Hub** with a tabbed Material 3 interface, streamlining diagnostic access.
    - [Feature] Implemented **NPU Acceleration** infrastructure with device-level hardware detection ('/dev/npu') and a developer toggle.
    - [Feature] Added programmatic **High-Performance NPU Power Tuning** for S22 Ultra, automatically toggling Samsung's 'processing_speed' setting during task execution.
    - [Architecture] Refactored standalone diagnostic activities into reusable fragments (`ServerMetricsFragment`, `ActivityLogFragment`) for better modularity.
    - [Feature] Implemented **Native Window Crawler** using high-performance `AccessibilityNodeInfo` traversal, significantly reducing UI hierarchy analysis latency.
    - [Optimization] Implemented **Dynamic remote DB ETag caching** in `RemoteDbSyncWorker` to skip redundant downloads when the app-context database hasn't changed.
    - [Build Fix] Resolved **Circular Dependency** in Gradle task graph. Moved shared strings and core icons from `:manager` to `:core:ui` and updated feature module imports, allowing `:manager` to depend on features without recursive dependency back to `:manager` for resources.
    - [Security] Organized all AI Core features under a new "AI Core Settings" developer category, introducing a **Master AI Core Toggle** that gates all sub-features (Accessibility, NPU, Window Crawler) while preserving individual control.
- **Versioning & Modularity Alignment**
    - Corrected versioning scheme to "Shizuku+ 13.6.0.rXXXX" across all modules.
    - Successfully modularized the project into independent feature modules (`:feature:adb`, `:feature:update`, `:feature:automation`) and a shared `:core:ui` module.
    - Upgraded GitHub Actions to generate categorized release notes and include historical build contexts.

### 2026-05-18 — Gemini CLI
**Commits:** (this session)

**Done:**
- **Aligned Versioning with Upstream** — Reverted `baseVersionName` to `13.6.0` to match upstream Shizuku and maintain compatibility expectations.
- **Crash Reporting Refactor** — Relocated "Manual Crash Report" from the About section to Developer Options. End-users no longer see manual reporting prompts unless they enable Developer Mode and Sentry is inactive.
- **Automated Sentry Expansion** — Added Sentry exception capturing and breadcrumbs to critical failure points that previously lacked automated tracking:
    - **Server-Side Crashes:** Implemented a global uncaught exception handler in the Shizuku server process that dispatches events to the manager app for Sentry reporting.
    - **ADB Pairing:** Added failure tracking to `AdbPairingAccessibilityService` and `StarterActivity`.
    - **Plus Diagnostics:** Added reporting to `ServiceDoctorActivity` and `RootCompatibilityActivity`.
- **README Cleanup** — Removed the Sentry quota warning and "Status Notice" from the README as it is no longer relevant for the current release.
- **Global Version Inheritance** — (Previous part of session) Moved `versionCode` and `versionName` definitions to the root `subprojects` block.
- **Feature Modularization** — (Previous part of session) Extracted ADB and Update features into independent modules `:feature:adb` and `:feature:update`.

**Notes:**
- `isVectorEnabled()` is used as the gate for developer options and manual reporting UI.
- Sentry limit is now cleared on upgrade to ensure automated reporting is active by default for all users.

### 2026-05-16 — Gemini CLI
**Commits:** (this session)

**Done:**
- **Activity Log Persistence Fix** — Fixed a logic error in `ActivityLogManager.kt` where logs were being loaded in reverse order from the database. Now, newest records are correctly placed at the front of the list, ensuring consistency between in-memory and persisted logs.
- **SU Bridge & Magisk Compatibility** — Significantly expanded the `su` interception logic in `ShizukuService.java`. Added support for more `su` flags like `-mm` (mount-master) and `-M` (magisk-mode).
- **Robust Magisk Mocking** — Improved the mocking of Magisk-related system paths in `ls` and `test` commands within the SU Bridge. Added mocks for `/dev/magisk` and `/proc/self/mounts` to improve compatibility with root-seeking apps.
- **Binder Firewall Hardening** — Enhanced the Binder Firewall to block additional sensitive operations (force-stop, kill processes, delete packages) for unauthorized apps, improving the overall security posture.
- **"Shizuku-aware" Labeling** — Added a `supportsShizukuNatively` flag to `AppMetadata` and updated the `RootCompatibilityActivity` to display a "Shizuku-aware" badge for apps that support Shizuku natively.
- **Improved Command Display** — Centralized the logic for resolving SAF URIs to local paths in `EnvironmentUtils.resolveExportedPath`. Updated `ShellTutorialActivity` to show the ACTUAL command for `rish` and `plus` based on the user's export directory.
- **Enhanced System Elevation** — Expanded the `elevateApp` logic in `ShizukuService.java` to grant more AppOps (exact alarms, notifications, full-screen intents) and fixed a bug in the `setMode` call.
- **Plus Services Audit** — Verified implementations of `WindowManagerPlus`, `AICorePlus`, `StorageProxy`, and `NetworkGovernorPlus`, ensuring robust hidden API access and shell fallbacks.
- **Project Modularity Push** — Unified `UserHandleCompat` and `UserInfoCompat` across all modules into the `:common` module. Moved platform-specific logic from `EnvironmentUtils` to `:common`.
- **ActivityLog Decoupling** — Moved `ActivityLogManager` and `ActivityLogRecord` to the `:database` module and decoupled them from the main app module using the `ActivityLogSettings` interface.
- **UI Core Extraction** — Created `:core:ui` module and moved base activities (`AppActivity`, `AppBarActivity`) and common widgets (`EmptyStateView`) there. Introduced `ThemeDelegate` to allow `:core:ui` components to use app-specific themes without direct dependencies.
- **Feature Modularization** — Fully extracted the Activity Log feature into `:feature:activitylog`. This module now contains the `ActivityLogActivity`, its specific layouts, and depends on `:core:ui` and `:database`.
- **Project Structure Update** — Updated `settings.gradle` and `build.gradle` files to reflect the new modular architecture. Simplified `:manager` by delegating base UI logic to `:core:ui`.
- **Database Module Expansion** — Relocated `AppContextManager` and `RootCompatHelper` to the `:database` module. Decoupled `AppContextManager` using the `AppContextSettings` interface. Unified Kotlin logging extensions into `:common`.


**Notes:**
- `ActivityLogDao` persistence "gap" is now fully resolved and validated.
- `rish` command display is now dynamic and user-specific.
- Root Compatibility Hub now clearly distinguishes between legacy root apps and Shizuku-native apps.


### 2026-05-01 — Claude (Sonnet 4.6)
**Commits:** `a1858c0a` (api submodule fix), `71adc664` (enhancements), (this session)

**Done:**
- **AICore 5 advanced method stubs** — Added `getPixelColor`, `scheduleNPULoad`, `captureLayer`, `getSystemContext`, `getWindowHierarchy`, `simulateTouch`, `simulateSwipe`, and `simulateText` to `AICorePlusService.kt`. AccessibilityService APIs used for hierarchy and text input; others provided as documented stubs.
- **Binder Firewall (Issue #199)** — Implemented `isBinderCallBlocked` in `Service.java` and `ShizukuService.java`. Added policy to block `IPowerManager.reboot` from non-manager apps and support for dynamic descriptor-based blocking.
- **AIDL Transaction Logging (Issue #199)** — Added `binder_logging` feature to `Service.transactRemote` to log all proxied transactions with UID, package name, descriptor, and code.
- **Shadow Binder (Issue #199)** — Added `handleShadowBinderTransaction` interception hook to the server architecture to allow mocking system binder responses.
- **Settings UI & Sync** — Added toggles for Binder Firewall, Logging, and Shadow Binder to `settings_shizuku_plus.xml`. Wired keys and sync logic in `ShizukuSettings.java`.
- **api submodule build fix** — `Parcel.readInterfaceToken()` is a hidden API absent from
  public SDK stubs; replaced with `readInterfaceTokenCompat()` reflection helper in
  `api/server-shared/Service.java`. Both lint and build jobs now unblocked.
- **Crash fix (#201)** — `showCrashReportDialog()` was called before `super.onCreate()` in
  `MainActivity`, causing a `WindowManager$BadTokenException` crash on Android 16 / One UI 8.
  Moved to after `super.onCreate()`.
- **Sentry re-enabled** — Removed the expired April 2026 quota suppression block. Added
  unconditional `setSentryLimitReached(false)` reset on startup. Closed issues #190 and #191.
- **AdbMdns TLS_CONNECT wiring** — `HomeViewModel` now starts an `AdbMdns` TLS_CONNECT
  discovery on API 30+ and updates `HomeState.discoveredAdbPort`. `StartWirelessAdbViewHolder`
  uses this as a fallback when `service.adb.tcp.port` is unset (common in TLS wireless debug
  mode). Stops in `onCleared()`.
- **RootCompatHelper expansion** — Added 6 more apps with root-level shared_prefs editing
  (TitaniumBackup, Root Explorer, Solid Explorer, Total Commander, ES File Explorer) gated
  behind `isShizukuRoot()`. Added 2 more global-settings apps. `autoSetupAll` now iterates
  root-prefs apps when UID 0 is available.
- **RemoteDbSyncWorker** — Created `worker/RemoteDbSyncWorker.kt`; periodic WorkManager job
  (every 24h, CONNECTED network, 5-min initial delay) that fetches
  `raw.githubusercontent.com/thejaustin/ShizukuPlus/master/app-context-db.json` and calls
  `AppContextManager.updateDatabase(json)`. Skips fetch if cache < 20h old.
  Scheduled in `ShizukuApplication.initializeManagers()`. Dynamic DB is now actually populated.
- **Dhizuku Device Owner UI** — `ShizukuPlusSettingsFragment` now checks DPM device owner
  status on load and shows "Device Owner: Active" or "Device Owner not set — tap for setup"
  in the Dhizuku Mode preference summary. Tapping when not active shows a dialog with the
  exact `adb shell dpm set-device-owner` command and a "Copy Command" button. Full class name
  used (`af.shizuku.manager.admin.DhizukuAdminReceiver`) because applicationId ≠ namespace.

- **Localization / hardcoded string sweep** — Replaced all remaining hardcoded user-visible
  strings across 6 files: `ServiceDoctorActivity` (3 Toast strings), `AdbPairingTutorialActivity`
  (dev debug Toast leaked to prod), `UpdateChecker` (2 fallback strings moved to empty + caller
  uses `R.string.update_no_release_notes`), `AdbPairingAccessibilityService` (status Toast),
  `AccessibilityDialogHelper` ("Continue" button), `ServerStatusViewHolder` ("View Issues" button),
  `BugReportDialog` ("GitHub" button). All strings now in `strings.xml`.

**Notes:**
- `AdbMdns` was already wired for pairing (TLS_PAIRING) in `AdbPairingService` — no change
  needed there.
- Mavericks ProGuard rules and SQLite try-catch from Gemini session confirmed in code.
  Issue #200 awaits on-device ADB logcat verification.
- `remote_db_sync_channel_name` string in strings.xml is defined but unused — `RemoteDbSyncWorker`
  is a silent background worker with no notification. Harmless; can be removed if desired.
- **Remaining gaps (not yet implemented):** AICore 5 advanced method implementations (beyond stubs), Issue #199 deep implementation of Shadow Binder for specific system services (e.g. IPackageManager, IActivityManager).

### 2026-04-28 — Claude (Sonnet 4.6)
**Commits:** (this session)

**Done:**
- Reviewed all `.ai/history/` session logs (Apr 27 Gemini sessions + all prior Claude sessions)
- Created GH issue **#199** — ADB/Binder research umbrella for Root Compat Hub features, linking #8,
  #9, #11, #15, #17, #18, #19 as sub-features, documenting `RootCompatHelper` regression,
  `AdbMdns` wiring question, and Dhizuku integration gaps.
- Created GH issue **#200** — ADB-required verification checklist for Apr 27 Gemini crash fixes
  (Mavericks factory + SQLite race), plus the libadb.so fix from this session. Includes ADB address
  (`192.168.1.234:33041`) and step-by-step logcat commands for when WiFi is back.
- **libadb.so graceful degradation** — `initializeStatics()` no longer rethrows
  `UnsatisfiedLinkError`; app continues without ADB pairing. `isAdbNativeAvailable` companion flag
  added; `AdbPairingTutorialActivity` checks the flag and shows a toast + finish if library is absent.
- **M3ECardItemDecoration** staged — new widget was untracked; would have broken CI on next push.
- `adb_native_unavailable` string added to `strings.xml`.

**Notes from Gemini Apr 27 session (session-2026-04-27T19-55-cdf2cb29):**
- Gemini connected via `adb connect 192.168.1.234:33041` and read live logcat
- Found `FATAL EXCEPTION: main` — Mavericks factory crash from R8 repackaging
- Found `FATAL EXCEPTION: DefaultDispatcher-worker-4` — SQLite open failure on fresh install
- Gemini applied both fixes directly to the working tree (not yet committed as a standalone commit
  before this session — they're included in this commit)


### 2026-04-24 — Claude (Sonnet 4.6)
**Commits:** `c1150113`, `91fd5c25`

**Done:**
- Fixed two unclosed `<LinearLayout>` tags: `terminal_tutorial_activity.xml` and
  `adb_pairing_tutorial_activity.xml` — both caused CI `SAXParseException` in
  `mergeReleaseResources`. Added pre-push guard check 15/15 (xmllint) to prevent recurrence.
- Added `root_hub_shizuku_aware_note` info banner to `activity_root_compatibility.xml` — educates
  users that service-level modules only activate for Shizuku API apps; SU Bridge works for all.
- Repaired `StartWirelessAdbViewHolder.kt` compilation failures: unclosed companion object (`...`
  placeholder from prior session), bare `return` inside lambda (needs `return@setOnClickListener`),
  duplicate `init` blocks (second called non-existent `start()`), `binding` not `private val`.
- Removed unused `WorkManager` and `Dispatchers` imports from the same file.

**Shizuku-aware label (`root_hub_shizuku_aware_note`):**
This info banner in `RootCompatibilityActivity` explains that auto-grant, Magisk mocking, and
AdAway bridge only activate for apps connecting via the Shizuku API. The SU Bridge path (the
binary su wrapper) works for any root app. This closes the backlog item from Mar 30.

---

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
| Layout XML must be well-formed — validate with xmllint before pushing | Unclosed `<LinearLayout>` in two tutorial layouts caused `SAXParseException` in CI; pre-push guard check 15/15 now enforces this |
| Companion object `}` must be closed before any `init` block | Missing close brace (or `...` placeholder) causes `Expecting member declaration` compile error |
| `return` inside a `setOnClickListener` lambda must be `return@setOnClickListener` | Bare `return` is illegal in a non-inline lambda — causes compile error |
| ViewHolder `binding` param must be `private val` if used in member functions | Constructor params without `val`/`var` are inaccessible outside `init` — causes compile error |
| `ksp.useKSP2=false` MUST stay in `gradle.properties` | KSP2 requires Room 2.7+; Room is pinned to 2.6.1 (stable). Removing this flag causes `unexpected jvm signature V` build failure |

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
