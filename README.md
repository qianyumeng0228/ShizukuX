<div align="center">

# ShizukuX

**简体中文** | [English](./README.en.md)

**面向高级用户的 Android 特权进程管理器**

ShizukuX 是 [Shizuku](https://github.com/RikkaApps/Shizuku) 的社区增强分支，在 [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) 与 [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus) 的基础上发展而来，继承了原项目的全部能力，并针对国内用户进行了本地化与体验优化。

Shizuku 可以让普通应用借助 **ADB / Root** 启动的特权进程，直接调用系统级 API。ShizukuX 在保持 100% 兼容的同时，为高级用户与开发者加入了更多实用能力。

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)
[![License](https://img.shields.io/github/license/qianyumeng0228/ShizukuX?style=for-the-badge&color=30bf60&labelColor=208040)](LICENSE)

> **继承说明**：ShizukuX 由 ShizukuPlus（qianyumeng0228）改名并中文本地化而来，是 ShizukuPlus 的更名延续；ShizukuPlus 本身是 thejaustin/ShizukuPlus 与 thedjchi/Shizuku 的分支，最终追溯至 RikkaApps 的 Shizuku 项目。ShizukuX 是一个独立维护的社区分支，与 **RikkaApps/Shizuku、thedjchi/Shizuku、thejaustin/ShizukuPlus 及其维护者均无任何隶属、背书或合作关系**。全部上游版权与署名声明均完整保留（见 [LICENSE](LICENSE)、[NOTICE](NOTICE)、[CHANGES.md](CHANGES.md)）。

</div>

## 📑 目录

- [🚀 快速开始](#quickstart)
- [⬇️ 下载](#download)
- [✨ 核心功能](#features)
- [🛡️ 稳定性与安全](#security)
- [🚀 Extra API 增强能力](#extra-api)
- [🔌 第三方应用兼容性](#compat)
- [☑️ 系统要求](#requirements)
- [📱 开发者指南与文档](#developer-guide)
- [🗂️ 仓库结构](#repo-layout)
- [📦 近期更新](#releases)
- [🙏 致谢与许可证](#credits)

<a name="quickstart"></a>
## 🚀 快速开始

1. 在 [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) 下载最新 **标准版** APK 并安装（独立包名 `xyz.shizuku.extra.api`，可与原版 Shizuku 共存；**Drop-In 版**以原版包名安装、用于替换，不可共存）。
2. 打开应用，按提示用 **无线调试 / 电脑 ADB / Root** 之一启动服务（激活约 9 秒）。
3. 在应用内授权需要 Shizuku 权限的应用，即可开始使用。

> 详细的分步说明、常见错误与故障排查见[中文文档](docs/zh-CN/index.md)。

<a name="download"></a>
## ⬇️ 下载

请在 [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) 获取最新版本。每个版本均提供以下三个 APK：

| 版本 | 包名 | 适用场景 |
|---|---|---|
| **标准版** | `xyz.shizuku.extra.api` | 全新安装，独立包名，可与其他 Shizuku 版本共存 |
| **Drop-In 版** | `moe.shizuku.privileged.api` | 直接替换原版 Shizuku，兼容所有使用 Shizuku API 的应用（请勿与原版 Shizuku 同时安装） |
| **Compat-Hub** | `moe.shizuku.privileged.api` | 兼容性伴生模块，让只检测原版包名的第三方应用识别 ShizukuX（随标准版内置，可在应用内安装/卸载） |

- 正式版以 **`r` 系列**标识（如 `v13.7.0.r2002`），预发布以 **`k` 系列**标识（如 `v13.7.0.k2016`），均包含上述三个 APK。
- 各版本的具体更新内容见对应发布说明，完整历史见 [CHANGELOG.md](CHANGELOG.md)。

<a name="features"></a>
## ✨ ShizukuX 核心功能

*   **通用权限提供器**：一套接口统一接入 **Root**、**ADB Shell** 与 **Dhizuku（设备所有者）** 三种权限来源。
*   **OneUI 8+ 主题修复**：让 Hex Installer、Substratum 等主题引擎在 Android 16/17 与 OneUI 8+ 上继续正常工作。
*   **Dhizuku 模式**：将系统 Device Owner Binder 共享给任意持有 Shizuku 权限的应用——通过 ADB 即可配置，无需 Root；独立「权限等级」页统一管理 Shell / Root / DO 三种等级。
*   **细粒度权限管理器**：按应用批量授予 / 回收 Shizuku 权限的独立界面，与首页卡片联动。
*   **设备控制**：连接、显示、音频、电源与系统设置五大类 18 项操作（飞行模式、自动亮度、自动旋转、动画缩放、重启 / 关机等），ADB 模式下危险项自动置灰并提示。
*   **备份与恢复**：SAF 导出到公共目录、批量备份 / 恢复（含 OBB 数据）、内部数据（`run-as` + tar）恢复，恢复前自动快照与恢复历史记录。
*   **可定制手势**：支持左滑、右滑、长按等操作，可针对单个应用分别配置。
*   **应用内更新日志**：更新后无需离开应用即可查看新内容。
*   **批量管理**：多选应用，一键授予/回收权限或隐藏应用。
*   **活动日志**：实时审计 API 调用与 `su` 桥接命令，附带应用图标并实时刷新。
*   **Root 兼容中心**：为旧版 Root 应用提供仪表盘，支持细粒度模块控制（Magisk 伪装、自动授权、文件拦截等）。
*   **通用 SU 自动化**：一键"魔法设置"，将所有已安装的 Root 应用指向 ShizukuX SU 桥。
*   **服务诊断**：诊断并修复服务启动问题（含三星 Auto Blocker），诊断结果可一键导出崩溃堆栈。
*   **Stealth Mode（隐藏痕迹）**：拦截 `IPackageManager` 查询，从系统应用列表中隐藏 ShizukuX 自身。
*   **个性化控制**：加粗排版、扩展头部、圆角覆盖，动效与触感可跟随系统开关。
*   **AMOLED 纯黑主题**：补齐全 9 个 surface token，纯黑模式下卡片不再发灰。
*   **电池优化实时感知**：从系统设置返回即重新检查，MIUI / HyperOS「省电策略」也统一判定，弹窗不再残留。
*   **内置功能引导**：每个增强功能都带有说明图标，用通俗语言解释其作用。
*   **快捷设置磁贴**：在通知栏即可查看并切换服务状态（含飞行模式、动画、亮度、旋转等设备控制磁贴）。

<a name="security"></a>
## 🛡️ 稳定性与安全

*   **第三方客户端兼容（AIDL 事务码偏移）**：兼容官方 v13.7+ 与 LSPatch 等使用偏移 AIDL 事务码的第三方客户端，授权请求不再被误判为其他调用。
*   **ADB 代理握手鉴权**：15555 端口本地 ADB 代理验证 RSA 签名（即持有私钥）并绑定调用方 UID，伪造公钥无法绕过，第三方客户端线协议无感。
*   **崩溃恢复链路加固**：无线调试自启前先做前提探测，未配置 TCP 端口时回退 root 启动，两者皆不可用时明确通知；连续崩溃降级非永久，15 分钟冷却后自动重新武装自动重启。
*   **isRooted 授权探测修复**：兼容 KernelSU / SukiSU 的 SELinux Enforcing 环境——除文件 stat 外结合 libsu 授权记录（5 分钟 TTL），不再把已 Root 设备误判为无 Root。
*   **HyperOS / MIUI 包可见性修复**：应用列表经服务端 IPC 获取（shell-uid PackageManager），解决澎湃 OS 包可见性限制导致的应用列表空白。
*   **服务端加固**：shell 注入防护（入口统一包名正则校验，拒绝 `/`、`..`、空格）、APK Signature Scheme v2 签名正确性修复、大 APK 流式签名避免 OOM、冻结包集合缓存（`isAppFrozen` 为 O(1)）。
*   **激活提速**：无线调试直连 `service.adb.tls.port` 端口，不再干等 mDNS 发现，激活流程从约 2 分钟降至约 9 秒。
*   **手动停止不再自启**：停止服务时一并停掉 starter supervisor，不再出现「点了停止又复活」。
*   **耗电优化**：动画跟随系统动效开关、指标轮询降频、下载通知按百分比节流、非运行态不再驻留进程。

<a name="extra-api"></a>
## 🚀 Extra API 增强能力

ShizukuX 提供原版 Shizuku **没有**的专属系统接口，供高级自动化与工具使用：

*   **AICore Extra 自动化桥**：面向 AI 工具的无 Root 特权 UI 自动化（层级转储、点击/滑动）。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **AVF（虚拟机）管理器**：运行带 GPU 加速的隔离 Linux/Microdroid 虚拟机。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6)）
*   **特权存储代理**：对受限路径（`/data/data/`、`/data/app/`）提供鉴权访问，用于备份与文件管理。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6)）
*   **设备伪装**（设置中的 *Spoof Device Identity*）：向系统呈现不同的设备信息，绕过针对特定机型的限制。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44)）
*   **智能桥**（*AI Core Extra*）：特权 NPU 调度与屏幕上下文智能。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **Window Manager Extra**：强制自由窗口缩放、管理悬浮气泡栏与弹性覆盖层。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **系统主题桥**（*Overlay Manager Extra*）：无 Root 的 Overlay 特权管理，用于主题化（如 Hex Installer）。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **网络与 DNS 治理器**：管理私有 DNS 与防火墙路由，支撑无 Root 的广告拦截。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **深度进程控制**（*Activity Manager Extra*）：让进程管理器更激进地结束应用、设置待机分组。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **连续性桥**：在多个 ShizukuX 设备之间安全传递状态与任务。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7)）

## 🛠️ 反向移植与性能优化

无需修改任何代码，ShizukuX 即可让普通 Shizuku 应用更快、更兼容：

*   **透明 Shell 拦截器**：将常见的 `pm`、`am`、`settings` 命令路由到更快的原生 API。
*   **本地 ADB 代理**：在 15555 端口模拟 ADB 服务端，让旧应用在无线调试关闭后仍能使用 Shizuku。
*   **SU 桥**：为支持自定义 Root 路径的无 Root 应用，提供基于 Shizuku 的 `su` 替代方案。
*   **`extra` 命令行工具**：可在 `rish` 内使用的特权命令行工具。
*   **动态应用数据库**：从 GitHub 实时同步应用描述与建议，保持界面信息最新。

## ⚙️ 模块化控制

ShizukuX 的一切能力都是可选的。在设置的 **Extra 功能**分类中可以开关：

*   透明 Shell 拦截
*   各项 Extra API（AVF、存储、智能等）
*   首页卡片显示
*   活动日志

<a name="compat"></a>
## 🔌 第三方应用兼容性

ShizukuX 以独立包名（`xyz.shizuku.extra.api`）安装，可与原版 Shizuku 共存。由于多数 Shizuku 应用只会检测 `moe.shizuku.privileged.api` 这个包名，ShizukuX 内置了一个轻量级 **兼容中心（Compat Hub）**——一个注册该包名的微型伴生应用，负责将 binder/权限请求转发给 ShizukuX。

**如果第三方应用检测不到 ShizukuX：**

1. 先启动 ShizukuX 服务（ADB 或 Root 方式）。
2. 在首页使用 **兼容中心** 卡片安装伴生应用（已随应用打包；安装过程依赖正在运行的服务，所以请先启动服务）。
3. 重新打开第三方应用——它现在应该能检测到 Shizuku 并收到服务 binder。

也可以安装 **直接替换版（drop-in）**，它直接以 `moe.shizuku.privileged.api` 注册（注意不要与原版 Shizuku 同时安装）。

<a name="requirements"></a>
## ☑️ 系统要求

**最低：Android 7+ · 完整支持至 Android 17（SDK 37）**

- **Root 模式**：需要已 Root 设备（兼容 Magisk / KernelSU / SukiSU / APatch 等）
- **无线调试模式**：Android 11+ 及所有 Android TV
- **电脑 ADB 模式**：所有设备
- **开机自启**：仅无线调试或 Root 模式可用

在 **Android 16+** 上，ShizukuX 会申请新的本地网络保护权限，以保证无线调试的发现与配对功能正常；在 **Android 17** 上，它透明地处理隐藏 API `deviceId` 变更，使已授权应用依然可见、权限授予依然生效。

<a name="developer-guide"></a>
## 📱 开发者指南与文档

关于专属 Extra API 的文档，见 [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API)（本仓库的 `api` 子模块即指向该仓库）。维护 Shizuku 分支并希望迁移到 ShizukuX 的开发者，可参考 [FORK_MIGRATION.md](FORK_MIGRATION.md)。

面向用户的完整中文文档（服务连接、知识库、权限说明、Dhizuku 模式等）见 [docs/zh-CN/index.md](docs/zh-CN/index.md)。

<a name="repo-layout"></a>
## 🗂️ 仓库结构

| 目录 | 说明 |
|---|---|
| `manager/` | 主应用：首页卡片、设置、权限管理、设备控制、备份/恢复、Watchdog、更新等 |
| `server/` | 特权服务进程：ShizukuService 与全部 Extra API 实现 |
| `core/` | 共享核心库（UI 组件、主题、字符串资源） |
| `api/` | [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) 子模块 |
| `compat/` | 兼容中心（Compat Hub）伴生应用 |
| `shell/` | `rish` / `extra` 特权命令行工具 |
| `starter/` | 服务启动器（ADB / Root / supervisor 拉起链路） |
| `common/` / `app-process/` / `database/` | 公共代码、独立进程封装、数据库层 |
| `docs/` | 中文文档（应用内帮助与更新说明指向这里） |

<a name="releases"></a>
## 📦 近期更新

| 版本 | 类型 | 发布日期 | 主要更新 |
|---|---|---|---|
| **v13.7.0.k2016** | 预发布 | 2026-10-26 | 手动停止后不再自启、root 启动与授权状态联动（isRooted 实测兜底）、激活提速（约 2 分钟→9 秒）、终端教程页导出与命令修正 |
| v13.7.0.k2015 | 预发布 | 2026-10-25 | 全仓 5.3 万行审查 13 项修复、无线调试自启 / 崩溃恢复链路加固、设置页按「常规/权限/服务/诊断」重排 |
| v13.7.0.k2014 | 预发布 | 2026-10-07 | 第三方客户端 AIDL 事务码偏移兼容、设备控制 / 备份全套、Stealth Mode、AMOLED 主题修复、兼容中心一键卸载 |
| v13.7.0.r2002 | 正式版 | 2026-09-22 | v13.7.0 正式发版线 |

更完整的变更记录见 [CHANGELOG.md](CHANGELOG.md) 与各版本 [Release Notes](https://github.com/qianyumeng0228/ShizukuX/releases)。

<a name="credits"></a>
## 🙏 致谢与许可证

ShizukuX 是社区驱动的增强分支，继承关系与免责声明见文首「继承说明」。

感谢以下上游贡献者与项目，是他们的工作让 ShizukuX 成为可能：

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — 奠定 Shizuku 基础项目与优雅的 API 设计。
- **[thedjchi](https://github.com/thedjchi)** — 中间分叉与体验改进，并承担了 **Android 17（SDK 37）兼容性**工作，ShizukuX 的 A17 支持由此移植而来。
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — Android 17 隐藏 API `deviceId` 兼容方案（`Android17Compat` / `InstalledPackagesCompat` 反射层）与本地网络保护处理的源头。
- **[LandonMoran](https://github.com/LandonMoran)** — 将 Nightzuku 的 Android 17 支持移植进 thedjchi 分支，并在**真机 Android 17 设备上端到端验证**（配对、服务启动、已授权应用列表），为 ShizukuX 的移植提供了实机验证基础。
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — aShell You 代码库，启发了终端与 Shell 自动化功能。
- **[iamr0s](https://github.com/iamr0s)** — 提供 Dhizuku，实现统一的 Device Owner 权限模式；以及用于独立 Java 进程执行的 AndroidAppProcess。
- **[pascua28](https://github.com/pascua28)** — 原生三星 System UID 1000 提权集成。
- **[kerneldroid](https://github.com/kerneldroid)** — Nightzuku 分支，启发了我们的 Android 16/17（SDK 37）隐藏 API 韧性（处理 `deviceId`）与界面现代化。
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — 提出超越常规限制的系统级 UID 1000 提权概念。

### 上游项目

| 项目 | 作者 | 许可证 | 角色 |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | 基础特权进程架构 |
| [Shizuku（分支）](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | 带体验改进的中间分支；承载 ShizukuX 所适配的 Android 17 兼容工作 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 17 隐藏 API `deviceId` + 本地网络保护兼容方案来源 |
| [Shizuku（分支）](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | 三星 UID 1000 系统执行方案 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 16/17 API 韧性与界面现代化 |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | ShizukuExt 团队 | Apache 2.0 | 系统 UID 提权概念 |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Device Owner binder 共享（Dhizuku 模式） |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | 独立高特权 Java 进程封装 |

### 开源库

本项目使用了以下主要开源库（完整清单与各库许可证文本见 [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) 与 [NOTICE](NOTICE)）：AndroidX Jetpack、Material Components、Kotlin / Coroutines、RikkaX Libraries、Mavericks、Lottie、Coil、Koin、Timber、libsu、AndroidHiddenApiBypass、Gson、LeakCanary、AboutLibraries、Bouncy Castle、Sentry、SQLite 等。

## 📃 许可证

[Apache 2.0](LICENSE)

### 致谢
- 特别感谢 **AkayamiShurui42** 积极主动的安全研究与稳定性补丁（参考：#239）。
- 感谢 **AlexeiCrystal** 定位 MIUI 崩溃问题，并提出用于旧应用的 Compat Hub 兼容方案（#241、#242）。
- 感谢 **ddnexus** 与 **kai-bash** 指出 Device Owner 恢复出厂设置陷阱与 Google 备份冲突（#237）。
- 感谢 **Kevinco1** 对 Root 兼容应用检测问题的反馈（#243）。
- 感谢 **aragortsantiago6-beep**、**Scoop2389**（Pixel 9a）与 **ConversionRituals**（小米）的实机 Android 16/17 测试、崩溃报告与日志，推动了 SDK 37 隐藏 API 与本地网络保护兼容性修复（#317、#323）。
- 感谢 **gmm96** 多轮 logcat 调试，最终定位了 Cached Apps Freezer 的 binder 投递 bug（#371）。
- 感谢 **[odorizzioficial](https://github.com/odorizzioficial)** 提供完整的巴西葡萄牙语翻译（#409），以及三星 "Sleeping apps" 看门狗冻结的详细报告（#415）。

## 🤝 贡献与支持

- 提交问题与建议：[GitHub Issues](https://github.com/qianyumeng0228/ShizukuX/issues)
- 参与开发：见 [CONTRIBUTING.md](CONTRIBUTING.md)
- 安全漏洞报告：见 [SECURITY.md](SECURITY.md)
- 完整更新历史：[CHANGELOG.md](CHANGELOG.md) · [CHANGES.md](CHANGES.md)
