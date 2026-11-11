## 版本更新

### v13.7.0.k2017（最新预发布，2026-11-11）

- **液态玻璃界面与五目的地统一容器**：底栏改为跟随手指滑动的液态透镜，按压缩放带玻璃形变反馈；首页、应用、模块、终端、设置并入同一容器，横向滑动贯通。全部卡片统一 12dp 圆角与 1dp 描边，防火墙、隐藏、自启动、强制深色、模块等子页面统一玻璃皮肤。
- **按应用隐藏**：进程、前台、无障碍、通知监听、VPN、重启六路信号共同判定；「隐藏后是否重启」改为逐应用勾选，新增自身重启窗口（2–15 秒）配合 6 秒静默期，避免把自我重启判成崩溃。
- **按应用网络防火墙 / 自启动 / 强制深色**：防火墙无需常驻 VPN；自启动基于 `RUN_ANY_IN_BACKGROUND` 与名单门闸放行或阻断；强制深色由守护进程独立执行，应用被冻结后仍生效。
- **电池优化白名单 / Wi-Fi 密码 / 终端指令管理**：首页入口卡直达忽略电池优化名单并随系统实时刷新；Wi-Fi 页一次取回连接状态与已保存网络，解析 WPA/EAP 密钥并按状态分组；终端支持保存、重命名、删除常用指令。
- **ADB 模块系统**：接入在线模块商店（多源并发取最高版本，避免单源缓存旧清单）与模块详情页；模块脚本运行前把模块树镜像到 `/data/local/tmp/.shizukux-mod/<id>`，解决 shell（uid 2000）无法读取应用私有目录而报 `No such file or directory` 的问题；每次运行校验 su shim 属主与权限恰为 `shell:700`。
- **备份与恢复改走 root 直读**：root 可用时直接读取应用私有目录打包，不再卸载原应用，并支持直接浏览 `/data`；恢复数据先落盘暂存，经归档校验（ArchiveVetting）判定为安全后才交给 run-as。
- **修复**：SoC 信息、Wi-Fi 列表、VPN 隐藏、首屏弹框、WebUI 权限、卡片间距等真机问题逐项收口；relay 电池行的禁用死按钮改为跳转系统电池设置；配对端口越界不再崩溃；损坏归档保持静默失败。

### v13.7.0.k2016（2026-10-26）

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
- 上游阻断级问题修复与安全加固（ADB token 校验、clearAppData 回调、AICore 存活检查、Android 17 ADB 状态兼容、edl 重启原因移除等）。

### v13.7.0.r2002（正式版，2026-09-22）

- v13.7.0 正式发版线（r 系列）。正式版与预发布（k 系列）均包含标准版 / Drop-In / Compat-Hub 三个 APK。

---

更多历史请见 [CHANGELOG.md](../../CHANGELOG.md) 与 [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases)。
