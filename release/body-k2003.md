# v13.7.0.k2003

## 版本更新

### ✨ 新功能

1. **外部中转自动激活（Scene / 黑阈）**
   - 外部中转授权支持**自动激活**：检测到 Scene / 黑阈打开后自动完成授权激活，无需手动逐项操作；每个服务会话只触发一次，避免重复弹窗
   - Scene 激活链自组装（从 APK 解析组件）+ 激活链独立目录（cp 源≠目标）+ 动态解析 daemon APK 入口（修复 0 字节 daemon）+ daemon 常驻但不监听时自动重启 + 优先使用 Scene 官方外部 up.sh + daemon 方案
   - 授权结果弹窗移至主线程，防 ANR

2. **设备所有者（Dhizuku）无线调试能力**
   - 已设为设备所有者的设备，可通过 Dhizuku 模式直接执行无线调试相关操作；设备所有者尝试预算可跨流程保持（重入流程不丢失）

3. **开机自动恢复开发者选项 / USB 调试 / 无线调试**
   - 开机后自动恢复：开发者选项、USB 调试、无线调试（`WRITE_SECURE_SETTINGS` + Shizuku 路由 `svc wifi enable`，WorkManager 延迟重试 + "无线调试需要 Wi-Fi"通知）
   - 跨厂商加固：`persist.security.adbinput` 检测仅限小米家族（OPPO/vivo/三星不再误报安全设置通知）；`adb_wifi_enabled` 仅 Android 11+ 写入、`adb_allowed_connection_time` 仅 Android 14+ 写入
   - 小米 USB 调试（安全设置）引导：开机后检测 `persist.security.adbinput`（MIUI 重启会重置、shell 无法写入），通知栏一键跳转开发者选项；doctor 检查直接读属性
   - 「恢复开发者选项」与「开机自动恢复」开关统一移入 启动与行为 → 启动与恢复 版块

4. **诊断导出强化**
   - 高级与诊断新增**一键导出诊断**（深度 shell 导出 + Scene daemon 检测加固）
   - 采集系统级日志（远程 OPPO 排障用）+ 设备所有者前置条件报告 + dpm 命令噪音整理（显示账号名）+ server-version doctor 行
   - api 子模块更新（legacy v12 客户端兼容 + getVersion 探测修复）

5. **OPPO 权限引导**
   - 权限缺失弹窗新增设备专属 OPPO 引导说明；OPPO/一加 ADB 权限检查与系统真实权限对齐

### 🐛 Bug 修复

6. **修复软件内更新"降级安装"问题（版本比较双轨脱节）**
   - 更新检查此前只比较 k/r 后缀数字，13.7.0 用户会被提示"更新"到 13.6.0.k2014（后缀 2014 > 2002），实际安装时被系统以"版本过低"拒绝
   - 改为双键比较：先比主版本号（13.7.0 > 13.6.0），同主线内再比 k/r 后缀；同主线正式版 r 优先于开发版 k（r/k 计数独立，13.6.0.r2002 即由 k2014 改名而来）
   - 新增安装前防御：解析下载 APK 的真实 manifest versionCode，低于已装版本时直接拦截并弹出"更新无法安装"说明，不再白装失败

7. **修复自动配对在 Android 7–12 上静默失效**
   - 无障碍配对服务的广播注册误用了 API 33+ 才有的带 flag 重载，低版本抛 `NoSuchMethodError`（被 runCatching 吞掉），自动配对 / 自动开启无线调试从未生效
   - 改用 `ContextCompat.registerReceiver` 兼容注册

8. **修复无障碍节点泄漏（配对 / 自动无线 / AI 核心）**
   - 配对服务每次无障碍事件泄漏整棵窗口树节点；AI 核心 `dumpHierarchy` 每次泄漏根节点；截图的 HardwareBuffer 长期不释放
   - 全链路统一回收 + 截图 buffer close

9. **修复黑阈 / Scene 中转激活在 OPPO 上误报失败**
   - 移除黑阈三处 `waitFor()`：OPPO / Android 16 上 `ShizukuProcess.exitValue()` 抛异常且 rikka 不捕获，导致真机上误报"脚本缺失 / 激活失败"
   - shell 探测一律不再调用 `waitFor()` / `exitValue()`
   - Scene 已运行时补授权（清数据重装后授权列表不再缺 Scene）；授权调用移出主线程防 ANR

10. **修复假 adb shell 会话挂死**
    - 伪 adb 服务 `waitFor()` 无异常保护，OPPO 上抛异常杀死线程，客户端永远收不到关闭信号、进程句柄泄漏

11. **修复开机自动恢复无权限时无限重试**
    - 未授予 `WRITE_SECURE_SETTINGS` 时恢复任务永远失败，WorkManager 指数退避无限重试、持续唤醒设备；现改为直接放弃

12. **修复 AdbMdns 停止后自动重连失效**
    - 停止时取消协程作用域导致重连标记永久卡死，同一实例后续自动重连永不触发

13. **修复无 WiFi 环境无线调试卡死**
    - 设置页路径不再要求先连上 Wi-Fi（仅需开关可用）；自动开启无线调试会校验系统真实开关状态
    - 修复打开 Wi-Fi 后卡在"正在搜索服务"

14. **授权持久化 + 默认开启 watchdog + 防杀引导**
    - 授权记录持久化保存；watchdog 默认开启；新增防杀（电池/后台限制）引导

15. **外部中转自动激活日志加 256KB 上限**
    - 长时间运行的无障碍会话不再无限增长诊断日志文件

### 📝 说明

- 本版本为**预发布版本**（k 前缀），欢迎测试反馈
- **标准版与 Drop-In 版同步更新**：两版共用同一份源码，全部修复均已包含

## 下载选择

| 版本 | 包名 | 适用场景 | 下载 |
|------|------|----------|------|
| **标准版** | `xyz.shizuku.extra.api` | 全新安装，独立包名，不影响其他 Shizuku 版本 | [ShizukuX-v13.7.0.k2003.apk](https://github.com/qianyumeng0228/ShizukuX/releases/download/v13.7.0.k2003/ShizukuX-v13.7.0.k2003.apk) |
| **Drop-In 版** | `moe.shizuku.privileged.api` | 替换原版 Shizuku，兼容所有使用 Shizuku API 的应用 | [ShizukuX-Drop-In-v13.7.0.k2003.apk](https://github.com/qianyumeng0228/ShizukuX/releases/download/v13.7.0.k2003/ShizukuX-Drop-In-v13.7.0.k2003.apk) |
| **Compat-Hub** | `moe.shizuku.privileged.api` | 兼容性增强模块，让只检测原版 Shizuku 包名的第三方应用识别 ShizukuX | [ShizukuX-Compat-Hub-v13.7.0.k2003.apk](https://github.com/qianyumeng0228/ShizukuX/releases/download/v13.7.0.k2003/ShizukuX-Compat-Hub-v13.7.0.k2003.apk) |

**包名为**：
- 标准版：`xyz.shizuku.extra.api`
- Drop-In 版：`moe.shizuku.privileged.api`（与原版 Shizuku 一致，可直接覆盖安装）

## 📦 Recent Releases

| 版本 | 类型 | 发布日期 | 主要更新 |
|------|------|----------|----------|
| **v13.7.0.k2003** | 预发布 | 2026-09-09 | 外部中转自动激活（Scene/黑阈）+ 设备所有者无线调试 + 开机自动恢复开发者选项/USB/无线调试（跨厂商加固）+ 诊断导出强化 + OPPO 引导 + 更新降级修复 + 配对低版本兼容 + 泄漏修复 + OPPO 中转兼容 + 无 WiFi 卡死修复 + watchdog 默认开启 |
| v13.7.0.k2002 | 预发布 | 2026-09-07 | 黑阈（Brevent）中继授权（外部中转授权扩展，免无线调试激活）+ 黑阈守护进程检测修复 |
| v13.7.0.k2001 | 预发布 | 2026-09-07 | Android 17 适配（getInstalledPackages 签名兼容 + MATCH_ALL 6 处）+ 无线调试权限拒绝态 UI + V3_SUPPORT/code 9 服务端兼容 |
| v13.6.0.k2014 | 预发布 | 2026-09-07 | 内置 Compat-Hub + Dhizuku 日志修复 + 无线配对流程系列修复（自动配对/窗口隔离/诊断可见） |
| v13.6.0.k2013 | 预发布 | 2026-09-07 | 修复 Hail Shizuku 停用模式无法解冻（code=90 拦截移除 + root 上下文条件化） |
| v13.6.0.k2012 | 预发布 | 2026-09-06 | Dhizuku 官方协议完整兼容 + 请求式授权弹窗（Hail 授权流程修复）+ 软件内更新下载卡0%修复（fd加速通道） |
| v13.6.0.k2011 | 预发布 | 2026-09-06 | 上游r2319-r2434大合并：应用备份+App Profiles+QS磁贴选项+9个服务端实现类+7个AIDL接口+ShizukuXAPI完整合入+11项bug修复 |
| v13.6.0.k2010 | 预发布 | 2026-09-06 | Dhizuku 状态自动刷新 + ShizukuX 内激活 Dhizuku + 自定义 RootSU 多策略兼容 SKRoot |
| v13.6.0.k2009 | 预发布 | 2026-09-06 | 外部中转授权独立管理界面 + Dhizuku 应用管理修复 + 更新内容随版本动态下发 + 检查更新弹窗修复 |
| v13.6.0.k2008 | 预发布 | 2026-09-06 | 外部中转授权功能 + Scene ADB 中转授权支持 |
