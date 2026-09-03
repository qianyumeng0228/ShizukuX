<div align="center">

# ShizukuX

**面向高级用户的 Android 特权进程管理器**

ShizukuX 是 [Shizuku](https://github.com/RikkaApps/Shizuku) 的社区增强分支，在 [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) 与 [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus) 的基础上发展而来，继承了原项目的全部能力，并针对国内用户进行了本地化与体验优化。

Shizuku 可以让普通应用借助 **ADB / Root** 启动的特权进程，直接调用系统级 API。ShizukuX 在保持 100% 兼容的同时，为高级用户与开发者加入了更多实用能力。

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)

> **继承说明**：ShizukuX 由 ShizukuPlus（qianyumeng0228）改名并中文本地化而来，是 ShizukuPlus 的更名延续；ShizukuPlus 本身是 thejaustin/ShizukuPlus 与 thedjchi/Shizuku 的分支，最终追溯至 RikkaApps 的 Shizuku 项目。ShizukuX 是一个独立维护的社区分支，与 **RikkaApps/Shizuku、thedjchi/Shizuku、thejaustin/ShizukuPlus 及其维护者均无任何隶属、背书或合作关系**。全部上游版权与署名声明均完整保留（见 [LICENSE](LICENSE)、[NOTICE](NOTICE)、[CHANGES.md](CHANGES.md)）。

</div>

## ⬇️ 下载

请在 [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) 获取最新版本，具体更新内容见各版本的发布说明。

## ✨ ShizukuX 核心功能

*   **通用权限提供器**：一套接口统一接入 **Root**、**ADB Shell** 与 **Dhizuku（设备所有者）** 三种权限来源。
*   **OneUI 8+ 主题修复**：让 Hex Installer、Substratum 等主题引擎在 Android 16/17 与 OneUI 8+ 上继续正常工作。
*   **Dhizuku 模式**：将系统 Device Owner Binder 共享给任意持有 Shizuku 权限的应用——通过 ADB 即可配置，无需 Root。
*   **可定制手势**：支持左滑、右滑、长按等操作，可针对单个应用分别配置。
*   **应用内更新日志**：更新后无需离开应用即可查看新内容。
*   **批量管理**：多选应用，一键授予/回收权限或隐藏应用。
*   **活动日志**：实时审计 API 调用与 `su` 桥接命令，附带应用图标并实时刷新。
*   **Root 兼容中心**：为旧版 Root 应用提供仪表盘，支持细粒度模块控制（Magisk 伪装、自动授权、文件拦截等）。
*   **通用 SU 自动化**：一键"魔法设置"，将所有已安装的 Root 应用指向 ShizukuX SU 桥。
*   **服务诊断**：诊断并修复服务启动问题（含三星 Auto Blocker）。
*   **内置功能引导**：每个增强功能都带有说明图标，用通俗语言解释其作用。
*   **快捷设置磁贴**：在通知栏即可查看并切换服务状态。

## 🚀 Plus API 增强能力

ShizukuX 提供原版 Shizuku **没有**的专属系统接口，供高级自动化与工具使用：

*   **AICore+ 自动化桥**：面向 AI 工具的无 Root 特权 UI 自动化（层级转储、点击/滑动）。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **AVF（虚拟机）管理器**：运行带 GPU 加速的隔离 Linux/Microdroid 虚拟机。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6)）
*   **特权存储代理**：对受限路径（`/data/data/`、`/data/app/`）提供鉴权访问，用于备份与文件管理。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6)）
*   **设备伪装**（设置中的 *Spoof Device Identity*）：向系统呈现不同的设备信息，绕过针对特定机型的限制。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44)）
*   **智能桥**（*AI Core Plus*）：特权 NPU 调度与屏幕上下文智能。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **Window Manager Plus**：强制自由窗口缩放、管理悬浮气泡栏与弹性覆盖层。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **系统主题桥**（*Overlay Manager Plus*）：无 Root 的 Overlay 特权管理，用于主题化（如 Hex Installer）。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **网络与 DNS 治理器**：管理私有 DNS 与防火墙路由，支撑无 Root 的广告拦截。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **深度进程控制**（*Activity Manager Plus*）：让进程管理器更激进地结束应用、设置待机分组。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **连续性桥**：在多个 ShizukuX 设备之间安全传递状态与任务。（[引入记录](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7)）

## 🛠️ 反向移植与性能优化

无需修改任何代码，ShizukuX 即可让普通 Shizuku 应用更快、更兼容：

*   **透明 Shell 拦截器**：将常见的 `pm`、`am`、`settings` 命令路由到更快的原生 API。
*   **本地 ADB 代理**：在 15555 端口模拟 ADB 服务端，让旧应用在无线调试关闭后仍能使用 Shizuku。
*   **SU 桥**：为支持自定义 Root 路径的无 Root 应用，提供基于 Shizuku 的 `su` 替代方案。
*   **`plus` 命令行工具**：可在 `rish` 内使用的特权命令行工具。
*   **动态应用数据库**：从 GitHub 实时同步应用描述与建议，保持界面信息最新。

## ⚙️ 模块化控制

ShizukuX 的一切能力都是可选的。在设置的 **Plus 功能**分类中可以开关：

*   透明 Shell 拦截
*   各项 Plus API（AVF、存储、智能等）
*   首页卡片显示
*   活动日志

## 🔌 第三方应用兼容性

ShizukuX 以独立包名（`xyz.shizuku.extra.api`）安装，可与原版 Shizuku 共存。由于多数 Shizuku 应用只会检测 `moe.shizuku.privileged.api` 这个包名，ShizukuX 内置了一个轻量级 **兼容中心（Compat Hub）**——一个注册该包名的微型伴生应用，负责将 binder/权限请求转发给 ShizukuX。

**如果第三方应用检测不到 ShizukuX：**

1. 先启动 ShizukuX 服务（ADB 或 Root 方式）。
2. 在首页使用 **兼容中心** 卡片安装伴生应用（已随应用打包；安装过程依赖正在运行的服务，所以请先启动服务）。
3. 重新打开第三方应用——它现在应该能检测到 Shizuku 并收到服务 binder。

也可以安装 **直接替换版（drop-in）**，它直接以 `moe.shizuku.privileged.api` 注册（注意不要与原版 Shizuku 同时安装）。

## ☑️ 系统要求

**最低：Android 7+ · 完整支持至 Android 17（SDK 37）**

- **Root 模式**：需要已 Root 设备
- **无线调试模式**：Android 11+ 及所有 Android TV
- **电脑 ADB 模式**：所有设备
- **开机自启**：仅无线调试或 Root 模式可用

在 **Android 16+** 上，ShizukuX 会申请新的本地网络保护权限，以保证无线调试的发现与配对功能正常；在 **Android 17** 上，它透明地处理隐藏 API `deviceId` 变更，使已授权应用依然可见、权限授予依然生效。

## 📱 开发者指南
<a name="developer-guide"></a>

关于专属 Plus API 的文档，见 [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API)（本仓库的 `api` 子模块即指向该仓库）。

## 🙏 致谢与许可证

ShizukuX 是一个社区驱动的增强分支，源自 [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku)（其本身是 [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) 的分支），并在此基础上经 [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus) 延续而来。本项目与原版 RikkaApps 团队**无隶属关系**。

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

| 库 | 作者 | 许可证 |
|---------|--------|---------|
| [AndroidX Jetpack](https://developer.android.com/jetpack) | Google / AOSP | Apache 2.0 |
| [Material Components](https://github.com/material-components/material-components-android) | Google | Apache 2.0 |
| [Material Symbols](https://fonts.google.com/icons) | Google | Apache 2.0 |
| [Kotlin / Coroutines / Serialization](https://github.com/JetBrains/kotlin) | JetBrains | Apache 2.0 |
| [RikkaX Libraries](https://github.com/RikkaApps)（appcompat、material、insets、html、recyclerview、preference、lifecycle、parcelablelist） | Rikka | Apache 2.0 |
| [Hidden API / Refine](https://github.com/RikkaApps/HiddenApiCompat) | Rikka | Apache 2.0 |
| [Mavericks (MvRx)](https://github.com/airbnb/mavericks) | Airbnb | Apache 2.0 |
| [Lottie](https://github.com/airbnb/lottie-android) | Airbnb | Apache 2.0 |
| [Coil](https://github.com/coil-kt/coil) | Coil Contributors | Apache 2.0 |
| [Koin](https://github.com/InsertKoinIO/koin) | Koin Contributors | Apache 2.0 |
| [Timber](https://github.com/JakeWharton/timber) | Jake Wharton | Apache 2.0 |
| [libsu](https://github.com/topjohnwu/libsu) | topjohnwu | Apache 2.0 |
| [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | LSPosed | Apache 2.0 |
| [libcxx](https://github.com/lsposed/libcxx) | LSPosed / LLVM | Apache 2.0 + LLVM Exception |
| [AppIconLoader](https://github.com/zhanghai/AppIconLoader) | Zhang Hai | Apache 2.0 |
| [BoringSSL (NDK)](https://github.com/vvb2060/ndk-boringssl) | vvb2060 / Google | Apache 2.0 / ISC |
| [Gson](https://github.com/google/gson) | Google | Apache 2.0 |
| [LeakCanary](https://github.com/square/leakcanary) | Square | Apache 2.0 |
| [AboutLibraries](https://github.com/mikepenz/AboutLibraries) | Mike Penz | Apache 2.0 |
| [Bouncy Castle](https://www.bouncycastle.org/) | Legion of Bouncy Castle | MIT |
| [Sentry Android SDK](https://github.com/getsentry/sentry-java) | Sentry | MIT |
| [SQLite (C Recovery API / CLI)](https://sqlite.org/) | D. Richard Hipp / SQLite Consortium | Public Domain |

各库的完整许可证文本见：[OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) | [NOTICE](NOTICE)

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
