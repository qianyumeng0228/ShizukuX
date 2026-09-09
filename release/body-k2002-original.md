# v13.7.0.k2002

## 版本更新

### ✨ 新功能

1. **黑阈（Brevent）中继授权（外部中转授权扩展）**
   - 黑阈不声明 Shizuku 权限，无法通过普通授权列表授权；其激活本质是 shell 执行 `/data/local/tmp/brevent.sh` 启动常驻守护进程
   - 外部中转授权界面新增「黑阈（Brevent）中继授权」卡片：由 ShizukuX 服务端 shell 权限直接执行激活脚本，**不再需要无线调试 / 电脑**，重启手机后一键恢复
   - 完整流程：Shizuku 运行检查 → 黑阈安装检查 → 激活脚本存在检查（未生成则提示先打开一次黑阈）→ 已运行检测（不重复拉起）→ 后台启动 → 守护进程验证 → 结果弹窗

### 🐛 Bug 修复

2. **修复黑阈守护进程检测失效**
   - 黑阈守护进程的真实进程名（comm）为 `brevent` / `main`，`pgrep -x brevent_daemon` 永远匹配不到，导致"已在运行"检查失效、重复执行激活并误报"激活失败"
   - 改为匹配 `ps` 输出的 argv[0]（`brevent_daemon` / `brevent_server`）并解析 PID 列，实测正确识别

### 📝 说明

- 本版本为**预发布版本**（k 前缀），欢迎测试反馈
- 黑阈激活后如需自启，可在黑阈设置中开启 root 模式（需 root 环境）

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
| **v13.7.0.k2002** | 预发布 | 2026-09-07 | 黑阈（Brevent）中继授权（外部中转授权扩展，免无线调试激活）+ 黑阈守护进程检测修复 |
| v13.7.0.k2001 | 预发布 | 2026-09-07 | Android 17 适配（getInstalledPackages 签名兼容 + MATCH_ALL 6 处）+ 无线调试权限拒绝态 UI + V3_SUPPORT/code 9 服务端兼容 |
| v13.6.0.k2014 | 预发布 | 2026-09-07 | 内置 Compat-Hub + Dhizuku 日志修复 + 无线配对流程系列修复（自动配对/窗口隔离/诊断可见） |
| v13.6.0.k2013 | 预发布 | 2026-09-07 | 修复 Hail Shizuku 停用模式无法解冻（code=90 拦截移除 + root 上下文条件化） |
| v13.6.0.k2012 | 预发布 | 2026-09-06 | Dhizuku 官方协议完整兼容 + 请求式授权弹窗（Hail 授权流程修复）+ 软件内更新下载卡0%修复（fd加速通道） |
| v13.6.0.k2011 | 预发布 | 2026-09-06 | 上游r2319-r2434大合并：应用备份+App Profiles+QS磁贴选项+9个服务端实现类+7个AIDL接口+ShizukuXAPI完整合入+11项bug修复 |
| v13.6.0.k2010 | 预发布 | 2026-09-06 | Dhizuku 状态自动刷新 + ShizukuX 内激活 Dhizuku + 自定义 RootSU 多策略兼容 SKRoot |
| v13.6.0.k2009 | 预发布 | 2026-09-06 | 外部中转授权独立管理界面 + Dhizuku 应用管理修复 + 更新内容随版本动态下发 + 检查更新弹窗修复 |
| v13.6.0.k2008 | 预发布 | 2026-09-06 | 外部中转授权功能 + Scene ADB 中转授权支持 |
