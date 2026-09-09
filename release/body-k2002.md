# v13.7.0.k2002

## 版本更新

### ✨ 新功能

1. **黑阈（Brevent）中继授权（外部中转授权扩展）**
   - 黑阈不声明 Shizuku 权限，无法通过普通授权列表授权；其激活本质是 shell 执行 `/data/local/tmp/brevent.sh` 启动常驻守护进程
   - 外部中转授权界面新增「黑阈（Brevent）中继授权」卡片：由 ShizukuX 服务端 shell 权限直接执行激活脚本，**不再需要无线调试 / 电脑**，重启手机后一键恢复
   - 完整流程：Shizuku 运行检查 → 黑阈安装检查 → 激活脚本存在检查（未生成则提示先打开一次黑阈）→ 已运行检测（不重复拉起）→ 后台启动 → 守护进程验证 → 结果弹窗

### 🐛 Bug 修复

2. **修复软件内更新"降级安装"问题（版本比较双轨脱节）**
   - 更新检查此前只比较 k/r 后缀数字，13.7.0 用户会被提示"更新"到 13.6.0.k2014（后缀 2014 > 2002），实际安装时被系统以"版本过低"拒绝
   - 改为双键比较：先比主版本号（13.7.0 > 13.6.0），同主线内再比 k/r 后缀；同主线正式版 r 优先于开发版 k（r/k 计数独立，13.6.0.r2002 即由 k2014 改名而来）
   - 新增安装前防御：解析下载 APK 的真实 manifest versionCode，低于已装版本时直接拦截并弹出"更新无法安装"说明，不再白装失败

3. **修复自动配对在 Android 7–12 上静默失效**
   - 无障碍配对服务的广播注册误用了 API 33+ 才有的带 flag 重载，低版本抛 `NoSuchMethodError`（被 runCatching 吞掉），自动配对 / 自动开启无线调试从未生效
   - 改用 `ContextCompat.registerReceiver` 兼容注册

4. **修复无障碍节点泄漏（配对 / 自动无线 / AI 核心）**
   - 配对服务每次无障碍事件泄漏整棵窗口树节点；AI 核心 `dumpHierarchy` 每次泄漏根节点；截图的 HardwareBuffer 长期不释放
   - 全链路统一回收 + 截图 buffer close

5. **修复黑阈 / Scene 中转激活在 OPPO 上误报失败**
   - 移除黑阈三处 `waitFor()`：OPPO / Android 16 上 `ShizukuProcess.exitValue()` 抛异常且 rikka 不捕获，导致真机上误报"脚本缺失 / 激活失败"
   - Scene 已运行时补授权（清数据重装后授权列表不再缺 Scene）；授权调用移出主线程防 ANR

6. **修复假 adb shell 会话挂死**
   - 伪 adb 服务 `waitFor()` 无异常保护，OPPO 上抛异常杀死线程，客户端永远收不到关闭信号、进程句柄泄漏

7. **修复开机自动恢复无权限时无限重试**
   - 未授予 `WRITE_SECURE_SETTINGS` 时恢复任务永远失败，WorkManager 指数退避无限重试、持续唤醒设备；现改为直接放弃

8. **修复 AdbMdns 停止后自动重连失效**
   - 停止时取消协程作用域导致重连标记永久卡死，同一实例后续自动重连永不触发

9. **外部中转自动激活日志加 256KB 上限**
   - 长时间运行的无障碍会话不再无限增长诊断日志文件

### 📝 说明

- 本版本为**预发布版本**（k 前缀），欢迎测试反馈
- 黑阈激活后如需自启，可在黑阈设置中开启 root 模式（需 root 环境）
- **标准版与 Drop-In 版同步更新**：两版共用同一份源码，全部修复均已包含

## 下载选择

| 版本 | 包名 | 适用场景 | 下载 |
|------|------|----------|------|
| **标准版** | `xyz.shizuku.extra.api` | 全新安装，独立包名，不影响其他 Shizuku 版本 | [ShizukuX-v13.7.0.k2002.apk](https://github.com/qianyumeng0228/ShizukuX/releases/download/v13.7.0.k2002/ShizukuX-v13.7.0.k2002.apk) |
| **Drop-In 版** | `moe.shizuku.privileged.api` | 替换原版 Shizuku，兼容所有使用 Shizuku API 的应用 | [ShizukuX-Drop-In-v13.7.0.k2002.apk](https://github.com/qianyumeng0228/ShizukuX/releases/download/v13.7.0.k2002/ShizukuX-Drop-In-v13.7.0.k2002.apk) |
| **Compat-Hub** | `moe.shizuku.privileged.api` | 兼容性增强模块，让只检测原版 Shizuku 包名的第三方应用识别 ShizukuX | [ShizukuX-Compat-Hub-v13.7.0.k2002.apk](https://github.com/qianyumeng0228/ShizukuX/releases/download/v13.7.0.k2002/ShizukuX-Compat-Hub-v13.7.0.k2002.apk) |

**包名为**：
- 标准版：`xyz.shizuku.extra.api`
- Drop-In 版：`moe.shizuku.privileged.api`（与原版 Shizuku 一致，可直接覆盖安装）

## 📦 Recent Releases

| 版本 | 类型 | 发布日期 | 主要更新 |
|------|------|----------|----------|
| **v13.7.0.k2002** | 预发布 | 2026-09-09 | 更新"降级安装"修复（双键版本比较+安装前拦截）+ 自动配对低版本兼容 + 无障碍节点泄漏全链修复 + 黑阈/Scene OPPO 兼容 + 假 adb 会话修复 + 开机恢复无限重试修复 + AdbMdns 重连修复 |
| v13.7.0.k2001 | 预发布 | 2026-09-07 | Android 17 适配（getInstalledPackages 签名兼容 + MATCH_ALL 6 处）+ 无线调试权限拒绝态 UI + V3_SUPPORT/code 9 服务端兼容 |
| v13.6.0.k2014 | 预发布 | 2026-09-07 | 内置 Compat-Hub + Dhizuku 日志修复 + 无线配对流程系列修复（自动配对/窗口隔离/诊断可见） |
| v13.6.0.k2013 | 预发布 | 2026-09-07 | 修复 Hail Shizuku 停用模式无法解冻（code=90 拦截移除 + root 上下文条件化） |
| v13.6.0.k2012 | 预发布 | 2026-09-06 | Dhizuku 官方协议完整兼容 + 请求式授权弹窗（Hail 授权流程修复）+ 软件内更新下载卡0%修复（fd加速通道） |
| v13.6.0.k2011 | 预发布 | 2026-09-06 | 上游r2319-r2434大合并：应用备份+App Profiles+QS磁贴选项+9个服务端实现类+7个AIDL接口+ShizukuXAPI完整合入+11项bug修复 |
| v13.6.0.k2010 | 预发布 | 2026-09-06 | Dhizuku 状态自动刷新 + ShizukuX 内激活 Dhizuku + 自定义 RootSU 多策略兼容 SKRoot |
| v13.6.0.k2009 | 预发布 | 2026-09-06 | 外部中转授权独立管理界面 + Dhizuku 应用管理修复 + 更新内容随版本动态下发 + 检查更新弹窗修复 |
| v13.6.0.k2008 | 预发布 | 2026-09-06 | 外部中转授权功能 + Scene ADB 中转授权支持 |
