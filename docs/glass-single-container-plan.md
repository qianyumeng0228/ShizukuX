# 单容器底栏重构（第二套 UI · 液态玻璃）

目标：首页 / 应用 / 模块 / 终端 / 设置五个目的地放进**同一个容器**，切页是 pager 横向滚动
（手指跟手、松手吸附），底栏只有一条悬浮条，高亮胶囊随 `currentPage + offset` 连续移动，
不再出现"Activity 跳转 + 胶囊重新落位"。

本文记录**已落地的实现**与验收口径。早期版本曾按 "Fragment 逐页迁移" 设计，实施时改为
"Controller + 直接 setContent"（见「与初版计划的差异」），现已全部接通，launcher 直入容器。

## 现状（k2121 起）

```
LauncherAlias ──▶ MainActivity ──▶ HomeActivity : GlassShellActivity
                                   └─ 容器本体，setContent
                                      ├─ HorizontalPager(pageCount = 5, beyondViewportPageCount = 1)
                                      │  ├─ 0 HomePage      (Compose, 复用 HomeController)
                                      │  ├─ 1 AppsPage      (Compose Scaffold + AndroidView(pageView))
                                      │  ├─ 2 ModulesScreen (Compose, ModulesController)
                                      │  ├─ 3 TerminalPage  (AndroidView: terminal_tutorial_activity.xml)
                                      │  └─ 4 SettingsScreen(Compose, SettingsController)
                                      └─ GlassBottomBar(selected, positionFraction)  ← 唯一一条，悬浮
```

- 入口链：`LauncherAlias` 指向 `.MainActivity`，`MainActivity : HomeActivity : GlassShellActivity`，
  所以冷启直接落在容器首页，不再先起一个"首页 Activity"再切。
- 单一底栏：五页统一 `showTabBar = false`，底栏只由容器画一次；`GlassTabBar` 仅服务旧独立页。
- 跟手口径：`positionFraction = pagerState.currentPage + pagerState.currentPageOffsetFraction`，
  底栏胶囊按该浮点值定位，松手后由 pager 的吸附带动胶囊。
- 页序常量：`GlassTabs.INDEX_HOME/APPS/MODULES/TERMINAL/SETTINGS = 0..4`；
  外部入口用 `GlassTabs.EXTRA_START_PAGE` 指定首屏，容器 `onNewIntent` 读该值并 `animateScrollToPage`。
- 内容底部留白 138dp（遮罩高度），保证最后一张卡能滚出底栏之上。

### 容器状态（GlassShellActivity）

| 组成 | 实现 |
| --- | --- |
| pager | `rememberPagerState(initialPage = requestedPage, pageCount = { 5 })`，`HorizontalPager(beyondViewportPageCount = 1)` |
| 外部跳页 | `LaunchedEffect(requestedPage)` + `animateScrollToPage`；底栏点击直接 `scope.launch { animateScrollToPage }` |
| 主题 | `AppTheme(darkTheme = isSystemInDarkTheme(), isBlackNightTheme = ThemeHelper.isBlackNightTheme(this), isOneUi = …, forceGlass = ShizukuSettings.isGlassUi())` |
| 底部遮罩 | 高 138dp；浅色 `0xFFDDE4F6`，深色 `Color.Black`（black-night）或 `0xFF080B12`；渐变 `0f→透明 / 0.22f→0.34 / 0.55f→0.88 / 1f→全色` |

> 主题判定必须用 `isSystemInDarkTheme()`（宿主的 Compose 环境），black-night 用
> `ThemeHelper.isBlackNightTheme(this@GlassShellActivity)`。在 `Box {}` 作用域里 `this` 会解析成
> `BoxScope`，必须显式写 `this@GlassShellActivity`；`GlassTabBar.kt` 同口径（见提交 `34c9a7af`）。

## 应用页的横滑手势桥

应用页是 `AndroidView` 承载的 XML 列表，行控件与顶部「切换全部」卡都会消费 `ACTION_MOVE`，
导致从这些区域起手的横向拖动到不了 pager（实测：y=600 不翻页，y=800 翻页）。

修复（`GlassShellActivity` 文件末尾 `Modifier.horizontalSwipeToPager(state, scope)`，提交 `d4b15c1c`）：

1. `awaitEachGesture` + `awaitFirstDown(pass = PointerEventPass.Initial)`，并在 `Initial` pass
   持续监听——该 pass 在 Compose 树中先于 interop view 派发，先手即可抢到事件。
2. 判定门槛：累计 `|dx| > touchSlop` 且 `|dx| > |dy| * 1.2` 才 claim。未达门槛前**不消费**，
   所以竖向列表滚动、点按、长按（多选入口）、开关点击全部保持原行为。
3. claim 后用 `change.consume()` + `state.dispatchRawDelta(-stepX)` 驱动 pager，页面跟手。
   这里必须用同步的 `dispatchRawDelta`：`awaitEachGesture` 给的是受限挂起作用域，
   `state.scrollBy` / `animateScrollToPage` 在内部调用会报
   "Restricted suspending functions can invoke member or extension suspending functions only on
   their restricted coroutine scope"。
4. 抬手后用 `|dx| > size.width * 0.18f` 决定是否落页，目标 `currentPage ± 1` 并
   `coerceIn(0, pageCount - 1)`；不足阈值则回弹当前页。落位动画通过
   `scope.launch { animateScrollToPage(target) }`（`launch` 非挂起，可在受限作用域触发）。

## 撤销条统一（应用管理的隐藏 / 取消隐藏）

容器与独立 `ApplicationManagementActivity` 走同一条 `utils/GlassUndoBar`（提交 `f76ba396`）：

- `GlassUndoBar.show(owner, activity, anchor, message, actionLabel, action)` / `dismiss(owner)`；
  `WeakHashMap` 管条与超时，锚点为空降级 Toast，10s 自动收起。
- 位置：`TAB_BAR_INSET_DP = 138`，`bottomMargin = (138 + 12)dp`，左右 12dp，`minimumHeight 52dp`，
  圆角 6dp，底 `0xFF2E2F38`，动作色 `0xFFB9A7FF`，文字 `0xFFECECF1`，`elevation 6dp`。
- 单条：`onHideApp` 按 `filterState == HIDDEN` 决定隐藏 / 取消隐藏，撤销做反向。
- 批量：`onOptionsItemSelected` 的 itemId 13（隐藏所选）/ 14（取消隐藏所选）快照选中集合后
  执行、`exitSelectionUi()`、`load()`、弹条，撤销逐条反向。`exitSelectionUi()` 统一清选择、
  退多选、关返回回调、恢复标题、`invalidateOptionsMenu()`、`notifyDataSetChanged()`，
  itemId 11/12 与 `android.R.id.home` 也走它（避免批量动作后标题停在「已选择 N 项」）。
- 文案：`app_management_unhidden`（`应用已恢复`）、`app_management_bulk_unhide`
  （`取消隐藏所选应用`），仅 `values` 与 `values-zh-rCN`，其余语言回退英文；
  容器 Compose 菜单与独立页菜单同用后者。

## 应用管理的选中集语义（可见即作用域）

`AppsAdapter` 的选中集是按包名存的 `HashSet<String>`（`selectedPackages`），DiffUtil 重建列表
时不会自动清理它。于是"长按选中一行 → 切到另一个筛选"会出现列表里没有任何勾选、顶栏仍写
「已选择 N 项」，而批量动作会作用到用户看不见的应用（`k121_z2_granted.png` 为实证）。

修法：筛选/搜索后的数据集一确定就裁剪选中集，语义定为**勾选只作用于当前可见集合**，
与 `selectAll()` 读 `viewModel.packages.value.data` 的口径一致。

- 容器：`AppsController` 的 `packages.observe` SUCCESS 分支在 `adapter.updateData(data)` 之后
  调 `pruneSelectionToVisible(data)`；同一筛选数据集上 `retainAll(visible)`，裁空则
  `exitSelection()`，否则更新 `selectionCount` + `notifyDataSetChanged()`。
- 独立页：`ApplicationManagementActivity` 有同语义的一份（`exitSelectionUi()` 之后），
  因为该页不共用 `AppsController`，它自己维护多选与标题。
- 两侧都只在多选态下生效；非多选态直接返回，不做多余通知。

## 批量授权 / 撤销的守卫（「切换全部」卡）

`app_list_toggle_all.xml` 把 `MaterialCardView` 整体 `clickable=true`，内层
`LinearLayout` / `TextView` / `MaterialSwitch` 全部 `clickable=false`，所以**整张卡就是热区**；
旧实现 `onClick()` 直接 `areAllEnabled()` → `setAllEnabled()`，没有确认、没有重入保护，
一次误触就会把当前筛选列表里的所有应用批量授权或批量撤销。

- 事故实证：20:20 前后在「已授权」筛选（5 个应用）上误触，20:21 首页即显示
  「已授权 0 个应用」（`k122_g_home.png`），20:24「已授权」筛选为空态
  （`k122_i_granted_now.png`）。同一次误触无法从界面看出发生过什么。
- 守卫一：`busy` 门。`onClick` 先取快照再置 `busy`，IO 算完目标状态后回主线程置回，
  确认框与真正执行各自再置一次，避免连点叠加。
- 守卫二：二次确认。`confirmToggleAll(items, enabled)` 按目标状态选文案并带条目数
  （`app_management_toggle_all_grant_message` / `..._revoke_message`，en + zh-rCN），
  点「确定」才 `applyToggleAll()` 在 IO 执行，完成后更新开关与
  `notifyItemRangeChanged(0, itemCount)`；点「取消」什么都不做。
  实测：已授权筛选下点卡片弹「撤销当前列表中全部 5 个应用的 Shizuku 权限？」，
  取消后 5 行仍全部保持开启。
- 守卫三：header 的统计要跟着列表走。DiffUtil 把任意两个 `HeaderMarker` 判为
  `areContentsTheSame`，切筛选时 header 不会重绑，开关会停在上一份列表的聚合值
  （日志实证 `refresh items=14 enabled=false`，而当时可见列表是 5 项全已授权）。
  修法：`AppsAdapter.updateData()` 在 `dispatchUpdatesTo` 之后，若首项是 `HeaderMarker`
  就补一次 `notifyItemChanged(0)`，强制按新列表重算。

## 首页「应用管理」卡的授权计数

卡片的标题由 `ManageAppsViewHolder` 决定：`count == null` 退回通用「应用管理」
（`HomeState.grantedAppCount` 注释写明 null = 尚未加载，避免冷启先闪一个 0），
拿到数字才拼 `home_app_management_authorized_apps_count`。

容器首页走 XML 路径（`HomePage` → `homeCtl.provider` → `HomeAdapter` → `ManageAppsViewHolder`），
Compose 版 `HomeCards` 的 `grantedResource` 与容器无关。计数来源是
`AppsViewModel.grantedCount`：`HomeController.attach()` 登记观察，SUCCESS 时
`homeModel.updateGrantedAppCount(...)` 再 `adapter.updateData()`。

排查与结论（三轮试探，全部以真机 logcat 取证）：

- k2126 / k2127 先按"没人 load"和"两个模型实例"两个方向试探，均无效：
  应用页明明有数据，首页仍 `render granted=null`。
- k2128 在 `load()` 成功路径与观察回调各加一行日志，得到
  `HomeGrant: status=SUCCESS data=5` 紧接 `HomeAdapter: render granted=null`——
  说明数据已经到达首页，断在 state 传递这一环。
- k2129 在 `updateGrantedAppCount()` 之后立刻 `withState` 回读，打印
  `after set=null model=…`，且与 `HomeAdapter` 打印的 model hash 相同：
  `setState { copy(grantedAppCount = 5) }` 没有反映到同一个模型的 `withState`。

修法（k2130）：

- `HomeAdapter.updateData()` 改为 `val grantedCount = homeGrantedCount ?: appsModel.grantedCount.value?.data`，
  以 LiveData 当前值为准、Mavericks state 作兜底，绕开上面那次失效的 state 写回。
- `HomeController.attach()` 在登记观察后补一次 `appsModel.load(onlyCount = true)`：
  应用页是 pager 惰性挂载，用户不打开它就没人算过计数（只算聚合值，一遍包列表）。
- `AppsController` 增加 `sharedModel` 构造参数，容器传 `appsModel`，列表刷新与首页观察
  落在同一个 LiveData 上（此前两边各自解析一次）。

验证：k2130 冷启，`render granted=5`，卡片标题「已授权 5 个应用」（`k130_a_home.png`）。
未测：授权 / 撤销之后**不离开首页**时计数是否即时刷新（两侧现在共用一个 LiveData，
按链路应该会，但没有实测帧）。

## 服务停止时容器不能自我关闭

用户报的症状是"取消激活（停掉 Shizuku 服务）之后，再点图标一闪就退回桌面，打不开"。
不是崩溃：`logcat -b crash` 干净，`ActivityTaskManager` 记录进程还在后台
（`The Process xyz.shizuku.extra.api Already Exists in BG`），只是没有 Activity——Activity 自己
`finish()` 了。

根因是一条为独立管理页写的退出规则被搬进了容器：

- `AppsController` 的 `stateListener` 原本是 `if (ShizukuStateMachine.isDead()) host.finish()`
  （服务死了，应用管理页整页内容已无意义，该退出）。
- `ShizukuStateMachine.addListener()` 在**注册时就用当前状态回调一次**（`listener(state.get())`），
  不是只等后续变化。
- 容器 `onResume()` 会 `appsCtl.refresh()`，`appsCtl` 是 `by lazy`，首次访问就构造
  `AppsController` 并在 `attach()` 里注册该监听。

于是服务已停止时冷启：pager 一摸到应用页 → 注册监听 → 立刻回放 `STOPPED` → `isDead()` 为真
→ `host.finish()`，首页第一帧还没画出来，整个容器就没了。运行中停服同理，容器也一起退出。

修法：`AppsController` 增加第四个构造参数 `exitOnDead: Boolean = true`（KDoc 写明两种宿主语义），
监听改为 `if (exitOnDead && isDead && !host.isFinishing) host.finish()`；容器构造时传
`exitOnDead = false`（`GlassShellActivity`），独立页 `ApplicationManagementActivity` 保持原语义。

容器不退出之后还有两处必须跟着改，否则"能打开"只是能打开：

- 停服态的应用页原本显示「没有找到结果 / 没有应用符合当前的筛选条件」，把服务不可用说成筛选
  没命中。新增 `apps_empty_not_running_title` / `apps_empty_not_running_description`，
  `applyEmptyState()` 按 `ShizukuStateMachine.isDead()` 二选一。
- `Status.ERROR` 分支原本也是 `host.finish()`；容器里改为清空列表 + 显示未运行空态 + Toast。
- 运行中停服，`stateListener` 在容器侧清空列表、隐藏 RecyclerView、切到未运行空态——否则旧的
  行和开关还画在那里，用户以为还能操作。

验证（k2132/k2133/k2134，服务用 `su 0 kill -9 <shizuku_plus_server pid>` 制造死亡）：

| 场景 | 结果 |
| --- | --- |
| 服务已停止时冷启（`am start` LauncherAlias） | 停在容器首页，红卡「ShizukuX 未运行 / 已停止」（`k133_a_home_dead.png`） |
| 停服态切到「应用」页 | 留在容器，显示「ShizukuX 未运行 / 启动服务后才能读取应用列表」（`k133_b_apps_dead.png`） |
| 容器在「应用」页开着时 kill 掉 server | 不退出，「应用」页当场切成未运行空态（`k134_c_after_kill.png`） |
| 同上后回首页 | 首页正常，`logcat -b crash` 只有被 kill 的 `shizuku_plus_server` 的 SIGABRT，无 app 记录 |
| 服务在跑（Watchdog 自动重启） | 首页恢复「正在运行」，计数重算（`k134_d_home_after_kill.png`） |

服务重新起来时页面也要自己回到列表：`stateListener` 记录 `serverWasDead`，由死转活且页面已 attach
就 `viewModel.load()`（`load()` 本身在 `Dispatchers.IO` 上跑，任何线程调用都安全），数据回流后由
SUCCESS 分支把空态收起、列表恢复。验证（k2135）：停在应用页不动 → `kill -9` 服务 → 1 秒内切空态
（`k135_g_dead.png`）→ root 重新拉起 server → 6 秒内自动回到完整列表，全程没有切页，也没有点任何东西
（`k135_h_recovered.png`）；首页状态卡同步回到「正在运行 / 已授权 4 个应用」（`k135_i_home_recovered.png`）。
k2136 在同一链路上复核通过（`k136_c_dead.png` → `k136_d_recovered.png`）。

补在 k2136 的还有一处：`GlassShellActivity.onResume()` 现在先 `ShizukuStateMachine.update()` 再刷新
各页。状态机会把上一次的判定持久化（`loadPersistedSettledState()` 读回 `RUNNING`），所以服务被强杀、
没走过 `setDead()` 的进程冷启时，可能以为服务还在运行并画出已经不能操作的列表。容器和 `MainActivity`
一样是 launcher 入口，都得在拿状态之前先 ping 一次 binder。`update()` 在 `STARTING/STOPPING` 期间
保持原状态，只在 ping 失败时把 `RUNNING` 判成 `CRASHED`，不会打断正在进行的启动流程。

### k2137–k2138：用户主动停服后冷启不再被自动拉起（「停服墓碑」四道闸）

**现象**。首页 X → 确定这条主动停服路径本身不复活：确认后 `k136_i_stopped.png` 红卡「ShizukuX 未运行 /
ShizukuX已停止」，`ps` 只剩 app 进程，等 34 秒仍为空，`settings.xml` 写成 `STOPPED`。但紧接着
`am force-stop` + 冷启，3–5 秒内服务必定以 shell 身份回来：`k136_k_cold_dead_home.png` 显示
「正在运行 / 版本 13.7, **ADB** / 已授权 0 个应用」，活动日志库新增 `Service started via ADB on port 40597`，
`settings.xml` 由 `STOPPED` 翻回 `RUNNING`。停服 → `force-stop` → **静置 10 秒不启动 App** 时 `ps` 为空，
再 `am start` 4 秒后服务出现，说明拉起动作发生在**冷启过程中**。

**根因（三条链叠在一起）**：

1. 停止路径不落终态。`confirmStopServer()` 只 `set(STOPPING)` → `killServerProcess()` → `Shizuku.exit()`。
   `STOPPING` 在 `transition()` 里不持久化，`killServerProcess()`/`exit()` 又是我方主动断 binder，
   `OnBinderDeadListener` 不保证回调，于是状态机停在 `STOPPING`、盘上留着的仍是 `RUNNING`。
2. 进程被 force-stop / 冻结 / 回收后冷启，`loadPersistedSettledState()` 读回 `RUNNING`，`update()` 走
   「此前 RUNNING 但 ping 失败」分支判成 `CRASHED`。`WatchdogService` 只对 `CRASHED` 动作，按崩溃处理调
   `ShizukuReceiverStarter.start()` → `LaunchMethod.ADB` 分支 `AdbStartWorker.enqueue()` → 新 ADB server，
   授权集合按新实例重算为 0。
3. 冷启还有独立的自动入口。`BootCompleteReceiver` 在 `LOCKED_BOOT_COMPLETED` 直接
   `LockedBootStartService.start()`（该 Service 内部直接 `AdbArm.arm` → 探端口 → `AdbStarter.startAdb`，
   不查任何「用户已停」条件），`else` 分支与 `ShizukuApplication.onCreate` 的 watchdog 三件套各有一条。
   `events` 缓冲里 `LockedBootStartService` 在冷启 0.2 s 内出现，坐实了这条入口。

**修法**：把「用户要它停」变成一条可读、可清、所有自动入口都必须让路的记录（下称墓碑）。

- `ShizukuSettings.isUserStoppedIntent()` = `last_settled_shizuku_state == "STOPPED"`。该值存在
  device-protected storage（`getSettingsStorageContext`），解锁前也能读，正好覆盖
  `LOCKED_BOOT_COMPLETED` 场景。
- `ShizukuStateMachine.markUserStopped()`：先落 `STOPPED`（墓碑）→ 再 `set(STOPPING)` → 800 ms 后若仍
  是 `STOPPING` 就 `setDead()`（等价 binder-dead 的 STOPPING→STOPPED，包含 USB 调试清理），解决
  「我方断连收不到回调」。
- `transition()` 在 `STARTING` 时把 `STARTING` 落盘，即任何显式启动都会覆盖墓碑；`loadPersistedSettledState()`
  对 `STARTING` 走 else → `STOPPED`，所以「启动没完成就死」既不会被当成活，也不会被当成崩溃。
- 停服入口统一改用它：`GlassShellActivity.confirmStopServer()`、`ShizukuTileService.onClick()`、
  `TileOptionsActivity.stopShizuku()`、`ShizukuReceiverStarter.stop()`。
- 四道自动入口全部加闸：① `ShizukuReceiverStarter.start()` 顶部（`!forceStart && isUserStoppedIntent()`
  直接 return；`forceStart` 时先 `set(STARTING)` 清墓碑）；② `LockedBootStartService.runStart()` 顶部；
  ③ `AdbStartWorker.doWork()` 顶部（取消 1447 通知并 `Result.success()`，挡 WorkManager 冷启重放）；
  ④ `WatchdogService` 的 `CRASHED` 分支（记 `Watchdog: restart suppressed (the user stopped the service)`、
  `set(STOPPED)`、不重启）。
- `ManualStartReceiver` 与 `LocaleActionFireReceiver` 的 START 改为 `forceStart = true`：它们是用户或
  自动化发起的一次显式启动，不该被墓碑拦死。

**验证（k2137 / k2138，均为真机实测）**：

| 场景 | 结果 |
| --- | --- |
| k2137 主动停服 | `k137_d_stopped.png` 红卡「未运行 / 已停止」，`last_settled_shizuku_state=STOPPED`，`shizuku_plus_server` 计数 0 |
| k2137 停服后 `am force-stop` + 冷启 | 10 秒 `NO_SERVER_PROCESS`，22 秒复查仍为 0；`k137_e_cold.png` 红卡。停服前同一台机器冷启必然 3–5 秒复活，本项即为原缺陷的直接反证 |
| k2138 重装（`MY_PACKAGE_REPLACED`） | 安装后 6 秒 `ps` 无服务：替换广播这条自动入口也没复活 |
| k2138 显式启动（`StarterActivity`：检测到 40597 / 已配对 → ADB 启动） | 8874 `libshizuku.so` + 8875 `shizuku_plus_server` 起来，`last_settled_shizuku_state=RUNNING`、`server_started_build=2122`；`k138_e_home_running.png` 显示「正在运行 / ADB / 已授权 4 个应用」——墓碑被清、授权集合保留 |
| k2138 第二轮停服 + 冷启 | 12 秒 `NO_SERVER_PROCESS_ROUND2`，`k138_f_cold2.png` 红卡 |
| 反证：墓碑不误伤真实崩溃 | 显式启动后 `kill -9` 8875/8874，活动日志库第 69 条 `1794155825079 Watchdog: restarting after crash #1` —— watchdog 走的是重启流程，没有出现 `restart suppressed` |

**这轮暴露的遗留（k2139 已修）**：上面那条崩溃重启最终没把服务拉起来。该实例是 ADB 模式、`persist.adb.tcp.port` 为空 →
`EnvironmentUtils.getAdbTcpPort()==0` → `start()` 转 root 探针 → `rootStart()` 失败 →
`recoverFromFailedStart()` 落 `STOPPED`（既有逻辑，与墓碑无关）。同一现象在 k2136 就存在；它在 k2138
上表现为 watchdog 只动作一次就彻底静默，修法见下一节。

### k2139：启动失败不再断自愈，停服墓碑独立成键

k2138 的反证测试暴露出三条互锁的缺陷，其中第二条是墓碑方案自己带出来的：

1. **失败启动落 `STOPPED`**：ADB 模式 + `persist.adb.tcp.port` 为空时 `getAdbTcpPort()==0`，
   `ShizukuReceiverStarter.start()` 转 root 探针；探不动就 `recoverFromFailedStart()` 写 `STOPPED`。
   `WatchdogService` 只监听 `CRASHED`，于是"自动重启失败 → 状态静默 → 再也不会尝试"。
2. **墓碑原本由状态推导**：`isUserStoppedIntent()` 等于 `"STOPPED".equals(last_settled_state)`。
   而失败启动恰好写 `STOPPED` —— **一次失败的自动重启会自己写下墓碑**，把 watchdog、开机路径、
   `AdbStartWorker` 永久锁死，UI 还会把系统故障显示成「ShizukuX 已停止」。这条比第 1 条更隐蔽，
   不修的话只改 `recoverFromFailedStart()` 的目标状态照样会踩。
3. 另有一处同类缺口：`AdbStartWorker` 的 `catch` 里 `update()` 非 `RUNNING` 就 `Result.retry()`，
   而 `update()` 对 `STARTING` 是 sticky 的 → 状态停在 `STARTING`，watchdog 同样不动作。

修法：

- `ShizukuSettings` 新增 `KEY_USER_STOPPED_INTENT = "user_stopped_intent"`，`isUserStoppedIntent()`
  改读这个布尔键，**键不存在时回落到旧的 `"STOPPED".equals(getLastSettledState())`**——升级前就已
  停服的机器，用户的停服意图不会丢。新增 `setUserStoppedIntent()`。KDoc 写清了为什么不能再从状态
  推导：`STOPPED` 同时是"用户要它停"和"这次没起来"，而后者是每个自动入口都要读的值。
- `transition()` 的 `STARTING` 分支除落 `STARTING` 外追加 `setUserStoppedIntent(false)`——任何一次
  启动（含 `forceStart`）都清墓碑，且只在启动时清。
- `markUserStopped()` 落 `STOPPED` 后显式写 `true`。
- 新增 `ShizukuStateMachine.markStartFailed()`：`set(CRASHED)` → `update()`（`update()` 会重新 ping，
  真有个活 server 就翻成 `RUNNING`，不留假崩溃）。**明确不写 `STOPPED`**。
- `ShizukuReceiverStarter.recoverFromFailedStart()` 与 `AdbStartWorker` 的 `catch` 都改调它（后者放在
  `Result.retry()` 之前，避免 `STARTING` 粘住；WorkManager 的重试与 watchdog 的退避并存，谁先把
  binder 起来都会翻成 `RUNNING`）。
- 有意保留的一处：`StarterActivity.clearStaleStartingState()` 仍在重试前写 `STOPPED`。它永远跑在
  前台、由用户驱动重试，不需要 watchdog 抢跑；键已经独立，写 `STOPPED` 不再冒充停服意图，所以不动。

**验证（k2139 / Code 2123，真机实测）**：

| 场景 | 结果 |
| --- | --- |
| 主动停服 | `user_stopped_intent=true` + `last_settled_shizuku_state=STOPPED`（新键真正落盘，`k139_d_stopped.png` 红卡）；服务进程计数 0 |
| 停服 → `force-stop` → 冷启 14 秒 | `NO_SERVER_PROCESS`，红卡保持（`k139_e_cold.png`），`user_stopped_intent` 仍 `true` |
| 显式启动（`StarterActivity`） | `user_stopped_intent=false`、`last_settled=RUNNING`，21565/21568 起来，回首页「正在运行 / ADB / 已授权 4 个应用」（`k139_f_starter.png`）——墓碑由 `STARTING` 清掉 |
| 真实崩溃（`kill -9` 双进程）后 28 秒 | 无服务，但 `last_settled_shizuku_state=CRASHED`（**不是 `STOPPED`**）、`user_stopped_intent=false` |
| 再等 55 秒 | 仍 `CRASHED` + `intent=false`：失败既没把自己写成墓碑，也没被 `STARTING` 粘住 |
| 崩溃后冷启一次（40 秒） | 服务被拉起（4360/4361），`last_settled=RUNNING`；活动日志完整链条 71→75：`Service started` → `restarting after crash #1` →（冷启）`restarting after crash #1` → `Service started` → `the server came back after restart #1`，**无 `restart suppressed`**；`k139_g_recovered.png` 恢复 4 个授权 |
| 旧版本停服态（键缺失 + `STOPPED`）冷启 | 30 秒内无 server，prefs 保持 `STOPPED` 且不回写键——升级兼容回落生效 |
| `intent=true` + 旧 `RUNNING` 冷启（无 server） | 旧 `RUNNING` 升 `CRASHED` 后被抑制：无 server，`last_settled` 回写 `STOPPED`，`intent` 保持 `true`，日志落 `Watchdog: restart suppressed (the user stopped the service)` |

对照 k2138：同一操作只产生一条 `restarting after crash #1` 就再无下文；k2139 上失败会落 `CRASHED`，
下一次机会（进程冷启 / `WatchdogAlarmReceiver` / 开机）继续尝试并最终成功。

**两条补充场景的实测**（k2139 装机态，先把 server 与 supervisor 精确杀干净再改 prefs，冷启后回读进程表与持久化值）：

| 场景 | 构造 | 结果 |
| --- | --- | --- |
| ① 升级兼容回落 | `last_settled=STOPPED` 且 `user_stopped_intent` 键被删除（模拟旧版本停服态） | 冷启 6 秒与 30 秒均 `NO_SERVER_PROCESS`；`settings.xml` 保持 `STOPPED` 且仍未写回键——缺键回落判据把全部自动入口拦住 |
| ② 墓碑抑制 | `last_settled=RUNNING`（记录里是运行态）+ `user_stopped_intent=true`，实际无 server | 冷启时 `ShizukuStateMachine.update()` 把旧 `RUNNING` 升为 `CRASHED`，watchdog 读到 `CRASHED` 后抑制；6 秒与 35 秒均无 server，`last_settled` 被回写为 `STOPPED`，`intent` 保持 `true`；活动日志 id 82 落 `Watchdog: restart suppressed (the user stopped the service)` |
| ③ 反向：显式启动清墓碑 | 承接 ② 的 `intent=true` | `su 0 am start …/starter.StarterActivity` 后 26424/26425 起来，`intent=false`、`last_settled=RUNNING` |

两个场景第一次跑都出现过「服务被拉起」的假象，根因是清理命令自杀：`pkill -f shizuku_plus_server` 会匹配到
执行它的 `sh` 自身（其命令行含目标字符串），返回码 143 即 `pkill` 打死自己、目标进程存活；加上 `force-stop`
后 MIUI 仍会拉活 app 进程，于是旧 prefs 下启动了一次，被误读成「闸门失效」。改用按 `/proc/*/cmdline`
枚举、从脚本文件执行（自身命令行不含目标串）的清理脚本后，两个场景都复现为预期结果。验证工具：
`_sp\killshizuku.sh`、`_sp\set_prefs_and_coldstart.ps1`（含 push 后二次 `force-stop`）。

## 入口与返回键

- 底栏在旧独立页里点击时走 `GlassTabs.open()`：`Intent(MainActivity).putExtra(start_page)`
  + `CLEAR_TOP | SINGLE_TOP` 复用已在运行的容器，然后 `finish()` 自己——切 tab 不是下钻，
  旧屏不该留在返回栈里。
- 旧的 5 个 Activity **保留**（deep link / 快捷方式 / TV 变体仍可直达），只是不再由底栏进入。
- 系统返回：容器内由 pager 与各 Controller 各自处理（应用页多选态先退多选）。

## 验收记录

| 版本 | 范围 | 结果 |
| --- | --- | --- |
| k2114–k2116 | 容器五页横滑、底栏单条、深色三页 | 通过；深色遮罩误用浅色已修（`34c9a7af`） |
| k2116 | launcher 冷启即容器首页、单一底栏；首点各 tab 1.5–5s 内均命中 | 通过 |
| k2119 | 独立页：单条隐藏、批量隐藏、批量取消隐藏、菜单文案、撤销回落 | 全通过，`logcat -b crash` 无新记录 |
| k2120 | 容器「已隐藏」页菜单第四项 = `取消隐藏所选应用`；点后弹「应用已恢复 / 撤销」 | 通过，已隐藏页回空态 |
| k2121 | 应用页 y=600 横滑 → 模块页（原缺陷点） | 通过（`k121_c_sw600.png`） |
| k2121 | y=600 反向 → 应用页；y=800 左滑 → 模块（单页，不跳两页） | 通过 |
| k2121 | 拖动中间帧：页面整体跟手左移、右侧露出模块页 | 通过（`k121_k_middrag.png`） |
| k2121 | 纵向滚动不误翻页、芯片点击、长按多选、行内开关点击 | 通过（列表上滚 / 已选择 1 项 / 开关开→关→开复原） |
| k2121 | 连续链路 应用→模块→终端→设置，胶囊逐页跟随 | 通过 |
| k2121 | 深色五页（首页/应用/模块/终端/设置） | 通过，遮罩与卡片均走深色 |
| k2121 | 独立页（root 启动 `ApplicationManagementActivity`）点底栏「首页」 | 通过：回到容器首页，独立页已出栈（`t-1`），容器实例仍只有一条 |
| k2121 | 独立页点底栏「应用」 | 通过：CLEAR_TOP 复用容器并 `animateScrollToPage(1)`，落到容器应用页（顶部「应用管理」+搜索+芯片+「切换全部」卡完整） |
| k2124 → k2125 | 「切换全部」卡开关在「已授权」筛选下错位 | 通过：`notifyItemChanged(0)` 重绑 header，卡片开关随列表变开（`k125_a_granted.png`） |
| k2125 | 切换筛选后的选中集 | 通过：可见保留、不可见裁剪（`k125_c_allkeep.png` / `k125_e_denied_prune.png`） |
| k2130 | 首页「应用管理」卡授权计数 | 通过：`render granted=5`，标题「已授权 5 个应用」（`k130_a_home.png`） |
| k2132 → k2134 | 停服后打不开（容器自我 finish） | 通过：停服冷启停在容器首页，应用页显示未运行空态（`k133_a_home_dead.png` / `k133_b_apps_dead.png`） |
| k2134 | 运行中停服 | 通过：容器不退出，应用页当场切未运行空态（`k134_c_after_kill.png`） |
| k2135 | 停在应用页时服务死而复生 | 通过：空态 6 秒内自动回列表，未切页、无操作（`k135_h_recovered.png`） |
| k2136 | 同上链路复核 + 容器 onResume 先 ping binder | 通过：`k136_c_dead.png` → `k136_d_recovered.png`；冷启首页与应用页均正常（`k136_a_home.png` / `k136_b_apps.png`） |
| k2137 / k2138 | 主动停服后 `force-stop` + 冷启不得复活（墓碑四道闸） | 通过：两轮均 `NO_SERVER_PROCESS`，红卡保持（`k137_e_cold.png` / `k138_f_cold2.png`）；显式启动可清墓碑并恢复 4 个授权（`k138_e_home_running.png`）；真实崩溃仍走 watchdog 重启流程（日志第 69 条） |
| k2139 | 崩溃自愈链 + 墓碑与状态解耦（独立 `user_stopped_intent` 键、`markStartFailed`、`AdbStartWorker` 收尾） | 通过：停服写 `intent=true`、冷启不复活；显式启动清墓碑并恢复 4 授权；`kill -9` 后失败落 `CRASHED`（非 `STOPPED`）且 `intent=false`，冷启后 watchdog 再次重启成功（日志 71→75，`k139_g_recovered.png`） |

## 未决与遗留

1. **多选期间 `load()` / 筛选重排**：`onResume`、`ShizukuStateMachine` 与筛选切换都会重发数据集
   （`k108_undo_ok.png`、`k121_z2_granted.png` 均有实证）。现在两侧都在数据集落地时按"可见集合"
   裁剪选中集（见〈应用管理的选中集语义〉）。实测（k2125）：在「已授权」筛选长按首行后切「全部」，
   该行仍打勾、标题仍是「已选择 1 项」（`k125_c_allkeep.png`）；切回「已授权」勾选仍在
   （`k125_d_back_granted.png`）；切到「被拒绝」时该行不可见，多选整体退出、标题回到「应用管理」
   （`k125_e_denied_prune.png`）。即"切筛选后仍然可见的勾选按包名保留，不可见即裁剪"。
2. **应用页行内左右滑（原 `app_info` 等动作）在容器内不再由触摸触发**：横向拖动优先归 pager，
   `rowSwipeEnabled = false` 因此在 `AppsController` 里关掉了 `ItemTouchHelper`。动作本身没有丢，
   目前有三条通道：① TalkBack 的无障碍自定义动作（`AppViewHolder` 的
   `accessibility_action_swipe_right/left` → `AppRowHost.onRowSwipe`）；
   ② 多选态下长按已选中行弹出的菜单（`buildEnabledActions`：打开应用 / **应用信息** /
   授权·撤销 / 隐藏 / 冻结）；③ 独立 `ApplicationManagementActivity` 仍按老实现挂着 `ItemTouchHelper`，
   行滑照旧可用。是否需要给容器页补一个显式入口尚未拍板。
   另注：`rowSwipeEnabled` 是常量 `false`，注释里"按宿主切换"的说法尚未实现——目前只有容器构造
   `AppsController`，所以不影响行为，但接手时要按宿主显式传入而不是照抄注释。
3. **`TerminalPage` 的中间层 binding**：已复核（k2139 期间），不构成泄漏。`buildTerminalPage()` 是
   一次性的视图装配函数，把 `binding` 只交给 `setOnClickListener` / `setOnEditorActionListener`
   这类随 View 一起回收的监听器，没有任何单例、长生命周期协程或静态字段持有它；`consoleOutput.post{}`
   在页面销毁后才落地时只对已 detach 的 View 做无害操作。故不再列入待修项。
4. **浅色玻璃不透明度 95%** 与设计稿 §3.1（`.55 / .42`）不一致，未调。
5. 仍是 `strokeWidth="0dp"` 的卡片：`activity_root_compatibility.xml`(3)、
   `page_onboarding_setup.xml`(3)、`app_list_toggle_all.xml`(1)、`home_banner_status.xml`(1)、
   `home_item_container.xml`(1)、`swipe_hint_overlay.xml`(1)。
6. **设置页搜索态是否隐藏底栏**未确认；当前搜索覆盖层会盖到底栏高度以上。
7. 深色下「设置页最后一张卡下缘压到底栏上方」判为滚动内容透出，未逐像素核对。
8. ~~ADB 模式下崩溃重启失败会落 STOPPED~~ **已在 k2139 修掉**：`recoverFromFailedStart()` 与
   `AdbStartWorker` 的 `catch` 改走 `markStartFailed()`（落 `CRASHED` 并重新探测 binder），停服意图
   同时从"状态推导"改为独立键 `user_stopped_intent`（缺键时回落旧判据）。细节与实测见〈k2139〉一节。
9. 30+ 次提交均未 push / tag / Release（对外动作需明确指令）。

## 与初版计划的差异

初版按 "5 个 `*PagerFragment` 逐页迁移" 设计，实施时改为：

- **不用 Fragment**：每页的 Activity 状态、协程、ViewModel 与回调全部提到 `*Controller`
  （`HomeController` / `AppsController` / `ModulesController` / `SettingsController`），
  Compose 页直接由容器 `setContent` 渲染，少了 FragmentManager 与视图重建的一层。
- **不引入新依赖**：沿用 `androidx.compose.foundation` 的 `HorizontalPager`，没有加
  `viewpager2`。
- **不做"每页禁用 pager"**：初版担心应用页左滑返回会与 pager 冲突，实测改为"左滑改用 Initial
  pass 手势桥"，pager 始终可滑，行滑动作让位（见遗留 2）。

"为什么不一锅端"的历史判断仍然成立：五页各自绑着 ActionBar 菜单、返回栈、权限回调、
手势与 ViewModel 生命周期，一次性改写会同时引入多类回归；实际执行也是逐页迁移 + 逐页真机验证。
