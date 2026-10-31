## 版本更新

### v13.7.0.k2016（最新预发布，2026-10-26）

- **手动停止后不再自启**：停止链路会一并停掉 starter supervisor（libshizuku.so），重启窗口不再打开，不会出现「点了停止又复活」。
- **root 启动入口与授权状态联动**：主页启动卡按设备的 root 授权状态显示启动方式（未授权走无线调试，授权后切 root）；isRooted 除文件 stat 外新增 `su -c id` 实测兜底，修复 SukiSU / SELinux Enforcing 设备误判无 root。
- **激活提速**：无线调试（TLS 模式）直接读 `service.adb.tls.port` 连接 adbd，不再干等 mDNS 发现（超时 10s→5s）；无 WRITE_SECURE_SETTINGS 权限且非设备所有者时回退手动开启指引。真机激活从约 2 分钟降到约 9 秒。
- **isRooted 探测挪后台**：主页渲染不再同步 spawn su，root 探测全部放后台线程，主页不再被卡住。
- **终端教程页修正**：`rish`、`rish_shizuku.dex`、`extra`、`su` 四个文件全部导出成功；步骤 3 运行命令改为实际路径（`sh /sdcard/rish`、`sh /sdcard/extra [command]`）。

### v13.7.0.k2015（2026-10-25）

- **全仓 5.3 万行审查 13 项修复**：shell 注入防护（服务端 4 个分支统一包名正则）、StorageProxy PFD 泄漏、TileService Toast 崩溃、Watchdog 不阻塞主线程、配对等待锁竞态、rish stderr 缓冲挂死等。
- **无线调试自启 / 崩溃恢复链路加固**：启动前前提探测（TCP 端口属性存在）→ AdbStartWorker；未配置但 root 可用 → rootStart 回退；两边皆不可 → 明确通知。连续崩溃降级非永久，15 分钟长冷却自动重新武装；退避 5s×2ⁿ 封顶 5 分钟。
- **isRooted 探测修复**：KernelSU / SukiSU 上文件 stat 被 SELinux 拦截的问题，改查 libsu 授权记录 + 5 分钟 TTL。
- **设置页重排**：按「常规 / 权限 / 服务 / 诊断」四大板块；Dhizuku 独立「权限等级」页；备份/恢复入口挪进诊断中心。
- **安全**：ADB 代理（15555）握手验签 + 调用方 UID 绑定；rish 授权链路四分支（已授权 / 拒绝 / 正常授权 / 异常兜底）。
- **耗电**：动画跟随系统动效、指标轮询降频、下载通知按百分比节流、非运行态不驻留进程、Sentry 会话检查 60s。
- 翻译补齐：中文 47 条、乌尔都语 698 条、巴西葡语 774 条。

### v13.7.0.k2014（2026-10-07）

- **第三方客户端兼容**：修复官方 v13.7+ / LSPatch 等使用偏移 AIDL 事务码的客户端连不上服务的问题（onTransact 增加 parcel 形状识别 + 按 uid/pid 记录偏移客户端兼容层）。
- **设备控制全套**：IDeviceControlExtra 18 个方法覆盖连接 / 显示 / 音频 / 电源 / 设置；shell 参数白名单校验；ADB 模式下电源操作自动降级置灰。
- **备份 / 恢复全套**：SAF 导出路由、批量备份恢复（含 OBB）、内部数据恢复（run-as + tar）、恢复前自动快照 + 恢复历史。
- **Stealth Mode**：Binder 层拦截 IPackageManager 查询，从系统应用列表隐藏 ShizukuX。
- **AMOLED 纯黑主题修复**：补齐 9 个 surface token，卡片不再发灰。
- **兼容中心一键卸载**；**电池优化提示实时消失**（onResume 重查 + MIUI 省电策略统一判定）。
- 上游 P0 修复与安全加固（ADB token 校验、clearAppData 回调、AICore 存活检查、Android 17 ADB 状态兼容、edl 重启原因移除等）。

### v13.7.0.r2002（正式版，2026-09-22）

- v13.7.0 正式发版线（r 系列）。正式版与预发布（k 系列）均包含标准版 / Drop-In / Compat-Hub 三个 APK。

---

更多历史请见 [CHANGELOG.md](../../CHANGELOG.md) 与 [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases)。
