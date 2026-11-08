# 单容器底栏重构方案（第二套 UI · 液态玻璃）

目标：把首页 / 应用 / 模块 / 终端 / 设置五个目的地放进**同一个容器**，横向滑动切页，
底栏高亮胶囊随手指在五个按钮之间连续移动，而不是每页各挂一条、跨页时重新落位。

当前状态（k2090）：五个页面各挂同一条 `GlassBottomBar`（XML 页经 `GlassTabBar.attach`，
Compose 页经 `Scaffold.bottomBar`），已做到"切页底栏不消失"；但仍是 5 个同级 Activity。

## 容器结构

分页容器用 **Compose `HorizontalPager`**（`androidx.compose.foundation` 自带），不引入
`androidx.viewpager2`（当前依赖树里没有，`libs.versions.toml` 只有 fragment-ktx，加依赖等于给
整个 module 多一个传递依赖面）。`HorizontalPager` 天然给 `currentPage` +
`currentPageOffsetFraction` 连续进度，正好喂给底栏的 `positionFraction`。

```
GlassShellActivity (ComponentActivity, edge-to-edge, setContent)
└─ Box
   ├─ HorizontalPager(state = rememberPagerState(pageCount = 5), beyondViewportPageCount = 2)
   │  ├─ page 0  HomePage      (Compose: HomeScreen)
   │  ├─ page 1  AppsPage      (AndroidView 包 AppListRecycler，或迁移成 Compose)
   │  ├─ page 2  ModulesPage   (Compose: ModulesScreen)
   │  ├─ page 3  TerminalPage  (AndroidView 包 terminal_tutorial_activity.xml)
   │  └─ page 4  SettingsPage  (Compose: SettingsScreen)
   └─ GlassTabStrip(selectedIndex, positionFraction, onSelectTab)  ← 悬浮，底部 20dp
```

- `positionFraction` = `pagerState.currentPage + pagerState.currentPageOffsetFraction`，
  随手指实时更新；点击按钮 `animateScrollToPage(index)`。
- 每页内容底部留 116dp（66 条 + 20 抬升 + 呼吸），与 `GlassTabBar.CONTENT_BOTTOM_INSET` 一致。
- 滑动与内部横向手势冲突：应用页左滑返回、设置页搜索态需 `requestDisallowInterceptTouchEvent`，
  或对这两页禁用 pager 滑动（只能点按钮切页）。
- 已落地的前置条件：`GlassBottomBar` / `GlassTabStrip` 已支持 `positionFraction`（本版已提交）。

## 逐页迁移清单

顺序按"依赖少 → 多"，每页迁移后单独构建装机验证，全部通过再切主入口。

### 1. ModulesPagerFragment（最简单，纯 Compose）
- 来源：`ModulesActivity`（约 700 行）的 11 个 `mutableStateOf` 状态 + 5 个操作方法
  （`load()` / `install(uri)` / `refreshHealth()` / `toggleModule()` / `requestDelete()`）。
- 做法：把状态与操作方法整体搬进 Fragment（`by mutableStateOf` 在 Fragment 上同样可用），
  `ModulesScreen(...)` 原样调用；`installLauncher`（`registerForActivityResult`）改注册在 Fragment。
- 顶栏：`ModulesScreen` 自带的 `TopAppBar` 保留在页内，容器不再提供 ActionBar。
- 迁移点：`ModulesScreen` 的 `showBack` 传 `false`（容器内无需返回箭头）。

### 2. SettingsPagerFragment（纯 Compose + 子页跳转）
- 来源：`SettingsActivity`（约 145 行）+ `SettingsScreen`（约 340 行）。
- 做法：`SettingsScreen` 的 `onNavigateToSetting` 仍跳各自子 Activity（`StealthModeActivity`、
  `ServiceDoctorActivity` 等），这一段保持不变；`hasInnerPage` / `showBack` 逻辑在容器内不需要
  （容器不显示返回箭头），子页仍是独立 Activity，返回栈不受影响。
- 风险：搜索态覆盖层 `fillMaxSize` 会盖住底栏 —— 需要把搜索 overlay 的高度减去 116dp，
  或让底栏在搜索态隐藏（推荐后者：搜索是一个明确的模态态）。

### 3. TerminalPagerFragment（XML）
- 来源：`ShellTutorialActivity`（约 285 行）+ `terminal_tutorial_activity.xml`。
- 做法：`onCreateView` 里 `inflate` 同一份 XML，把 `binding` 相关的初始化与方法（`runConsoleCommand`、
  导出按钮、`consoleOutput`）整体搬进 Fragment；`setInitialPadding` 的 bottom 直接用 116dp。
- 顶栏：该 Activity 走 `AppBarActivity` 的工具条，容器内需要自建一个页内 TopAppBar 或保留
  `toolbar_container`（建议统一用 `GlassPageHeader` 组合件，避免 5 套顶栏风格漂移）。

### 4. AppsPagerFragment（最重的交互）
- 来源：`ApplicationManagementActivity`（约 515 行）。
- 依赖：`AppListViewModel`（列表/过滤/排序/多选）、`AppListAdapter`、`SwipeRefresh`、
  `M3ECardItemDecoration`、`layout_animation_slide_bottom`、`setupSwipe()` 的左滑返回手势。
- 风险点（必须逐条处理）：
  1. **ActionBar 菜单**（排序、全选、批量授权/拒绝/隐藏）在容器里没有 ActionBar —— 需要把这些
     菜单项搬进页内的 `TopAppBar` 溢出菜单，或用一个页内 `MaterialToolbar`。
  2. **多选模式**：`backCallback` 目前挂在 Activity 的 `onBackPressedDispatcher`，Fragment 需要
     用自己的 `OnBackPressedCallback`，并注意与 pager 的返回优先级（先在页内退出多选，再退出容器）。
  3. **左滑返回手势**与 pager 横滑冲突：候选做法是应用页在 pager 里禁用横滑
     （`isUserInputEnabled = false`，仅按钮切页），保留页内左滑；或两者都禁用改成下拉/按钮。
  4. **RecyclerView 与 pager 的嵌套滚动**：Adapter 已用 `nestedScrollingEnabled`，需要复核。

### 5. HomePagerFragment（逻辑最多）
- 来源：`HomeActivity`（约 770 行）：服务状态机 `ShizukuStateMachine` 的 listener 注册/注销、
  `recyclerViewProvider: (Context, PaddingValues) -> RecyclerView` 动态构建首页卡片列表、
  编辑模式、停止服务对话框、诊断入口、壁纸主题。
- 做法：整体搬进 Fragment，`recyclerViewProvider` 参数改为 Fragment 内的方法引用；
  `addListener` 在 `onStart`、`removeListener` 在 `onStop`（原来绑在 Activity 生命周期）。
- 风险：首页是 `LauncherAlias` 的直接目标，迁移完成前**不能切主入口**，否则外部快捷方式、
  `shortcut_action` extra、`TvActivity` 等入口全部指向旧 Activity，会出现两套并存。

## 入口与返回键

- 主入口 `LauncherAlias` 最终指向 `GlassShellFragmentActivity`，`currentItem` 默认 0（首页）。
- 系统返回：容器内非首页时 `pager.currentItem` 回退一格（0 页时再 `finish`），与现在的
  "tab 之间 finish、首页保留"语义一致，但不再有 Activity 栈抖动。
- 旧的 5 个 Activity **保留**（外部 deep link、快捷方式、TV 变体仍可直达），只是不再从底栏进入；
  等容器稳定后再评估是否把它们改成"单页容器"的薄壳。

## 验收标准（每页迁移后、以及全部接通后各跑一遍）

1. 五个 tab 都能在容器内显示，页内顶栏风格一致，无返回箭头。
2. 手指横滑时底栏胶囊**连续**跟随（不是跳格），松手吸附到最近格。
3. 点按钮切页：内容横滑入场，胶囊同步滑动，无闪烁、无双底栏。
4. 每页内容滚到底时，最后一张卡能完全滚出底栏之上（116dp 留白生效）。
5. 应用页：多选、全选、批量授权/拒绝/隐藏、排序、搜索、左滑返回全部与迁移前一致。
6. 设置页：搜索、四组卡、每项跳转后的子页返回都正常。
7. 终端页：控制台执行、导出文件、外链帮助正常。
8. 首页：服务启停、编辑模式、卡片增删、诊断入口正常；返回键行为与迁移前一致。

## 为什么不一锅端

五个页面各自 300–770 行、并且分别绑着 ActionBar 菜单、返回栈、权限回调、手势与
ViewModel 生命周期。一次性改写会在同一版里同时引入 5 类回归，无法定位是哪一页弄坏的。
按页迁移 + 每页单独验证，是唯一能保证"不破坏已有功能"的路径；容器本身的外壳可以一次成型。
