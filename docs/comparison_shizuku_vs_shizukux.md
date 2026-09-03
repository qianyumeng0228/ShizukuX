# Shizuku 与 ShizukuX 架构对比

ShizukuX（继承自 Shizuku+）是基础 Shizuku 项目的全面增强版。在保持与现有 Shizuku 应用 100% 兼容的同时，它引入了面向下一代 Android 高级工具设计的现代、模块化架构。

## 1. 核心权限架构

| 特性 | 原版 Shizuku | ShizukuX |
|---------|------------------|----------|
| **主要后端** | Root / ADB Shell | Root / ADB Shell / **Dhizuku（设备所有者）** |
| **服务进程** | `shizuku_server` | `shizuku_plus_server`（优化的原生桥） |
| **Binder 接口** | 标准 IShizuku | 扩展的 IShizukuPlus，带细粒度能力标志 |
| **多用户** | 基础支持 | 稳健的跨用户 Binder 共享（如安全文件夹） |

### 统一后端
与原版主要依赖通过 ADB 启动的 `shizuku_server` 不同，ShizukuX 集成了 **Dhizuku 模式**。这允许 ShizukuX 管理器本身被设置为**设备所有者**，提供一个无需 Root 的、可持续的系统权限锚点（在支持的配置下），重启后依然生效。

### 兼容层（包名检测与 Binder 投递）
ShizukuX 以独立的应用包名（`xyz.shizuku.extra.api`）安装，可与原版 Shizuku 共存；但 Shizuku 应用传统上只会检测固定的 `moe.shizuku.privileged.api` 包名。ShizukuX 通过两个独立层来解决这个问题：

*   **检测**：内置的**兼容中心（Compat Hub）**伴生应用（`compat` 模块，包名 `moe.shizuku.privileged.api`）通过第三方应用的 `isInstalled` 检查，并将旧版 `REQUEST_BINDER` 广播转发给真正的管理器。另一种**直接替换版（drop-in）**则直接以 `moe.shizuku.privileged.api` 安装。
*   **投递**：服务通过 ContentProvider 的 `sendBinder` 调用，将特权 binder 交付给每个已授权应用，携带覆盖三个 API 命名空间（`af.shizuku`、`rikka.shizuku`、`moe.shizuku`）的 `BinderContainer` 数据包。只有兼容中心与这次握手都成功，应用才会认为 ShizukuX 处于"运行中"。

## 2. Plus API 生态

ShizukuX 不只是简单的 Shell 执行，而是向 Android 内部系统服务暴露稳定、与版本无关的桥接接口。

### Overlay Manager Plus（OMP）
*   **问题背景**：Android 14+ 与 OneUI 8+ 对标准 `OverlayManager` 系统服务施加了严格限制，导致无 Root 主题化失效。
*   **解决方案**：ShizukuX 使用自研的 `OverlayManagerTransaction` 桥，让主题（Substratum、Hex Installer）能够在没有完整系统 Root 的情况下安全、持久地应用 Overlay。

### AICore+ 自动化桥
*   **能力**：提供特权 `AccessibilityService`（无障碍服务）代理。
*   **功能**：
    *   `dumpHierarchy()`：将完整 UI 树导出为 XML（比 `uiautomator` 更快）。
    *   `performTap/Swipe()`：在内核/硬件抽象层模拟物理输入。
*   **应用场景**：让 AI 驱动的自动化（如 Tasker + GPT）无需 Root 即可与任意应用交互。

### Root 兼容中心（SU 桥）
ShizukuX 充当旧版应用的转译层。
*   **SU 封装**：提供 `/system/bin/su` 的直接替换实现，将命令路由到 Shizuku binder。
*   **模块伪装**：可让应用误以为已安装 Magisk 或 BusyBox，从而解锁旧工具的高级功能。

## 3. 性能与稳定性改进

### 透明 Shell 拦截器
原版 Shizuku 应用经常执行 Shell 命令（如 `pm install`）。这些命令因为要派生新进程而很慢。
*   **ShizukuX 优化**：拦截这些调用，在既有特权进程内通过直接的 Java/原生 API（如 `IPackageManager`）路由。
*   **效果**：对于 MacroDroid、终端模拟器等重度依赖 Shell 的应用，执行速度最高提升 10 倍。

### 服务诊断
一个实时诊断引擎，监控厂商特有的"杀进程"机制（如三星 Auto Blocker、OPPO 电池保护），并提供一键修复，让 Shizuku 服务保持存活。

### Watchdog 容灾
三层相互独立的恢复机制，各自覆盖其他层无法覆盖的场景：进程内崩溃监听实现快速恢复；周期性 WorkManager 兜底，防止仅 watchdog 服务死亡；以及基于 `AlarmManager` 的外部重臂——即使厂商冻结机制（如三星 One UI 的"休眠应用"）在锁屏时杀掉了**整个**应用进程，也能重启一切。这是进程内部任何代码都无法自行感知的失效模式。详见[服务连接页面](https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/service-connection.md#watchdog)。

## 4. 界面与交互优化
*   **Material 3 Expressive**：采用最新的 M3 设计语言，带弹性动画与自适应形状。
*   **应用内更新日志**：更新后可直接访问发布说明。
*   **批量管理**：面向管理数百个应用的高级用户打造的工业级权限管理。

---
*如需了解如何在自己的应用中集成 Plus API，请参阅 [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API)。如需了解 ShizukuX 请求的每一项权限及其用途，请参阅[权限说明页面](https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/permissions.md)。*
