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

## 未决与遗留

1. **多选期间 `load()` 重排**：`onResume` / `ShizukuStateMachine` 触发的刷新会让选中随行位置漂移
   （`k108_undo_ok.png` 曾有实证）。k2119/k2120 未复现，但目前没有抑制逻辑。
2. **应用页行内左右滑（原 `app_info`）在容器内等同失效**：横向拖动现在优先归 pager。
   是否需要替代入口（行菜单项 / 长按菜单）尚未拍板。
3. **`TerminalPage` 的中间层 binding** 在 pager 回收时没有清理逻辑。
4. **浅色玻璃不透明度 95%** 与设计稿 §3.1（`.55 / .42`）不一致，未调。
5. 仍是 `strokeWidth="0dp"` 的卡片：`activity_root_compatibility.xml`(3)、
   `page_onboarding_setup.xml`(3)、`app_list_toggle_all.xml`(1)、`home_banner_status.xml`(1)、
   `home_item_container.xml`(1)、`swipe_hint_overlay.xml`(1)。
6. **设置页搜索态是否隐藏底栏**未确认；当前搜索覆盖层会盖到底栏高度以上。
7. 深色下「设置页最后一张卡下缘压到底栏上方」判为滚动内容透出，未逐像素核对。
8. 30+ 次提交均未 push / tag / Release（对外动作需明确指令）。

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
