<div align="center">

# ShizukuX

[简体中文](./README.md) · [English](./README.en.md) · **繁體中文** · [日本語](./README.ja.md) · [Deutsch](./README.de.md) · [Français](./README.fr.md) · [Español](./README.es.md) · [Português](./README.pt-PT.md) · [Italiano](./README.it.md) · [한국어](./README.ko.md) · [Português (Brasil)](./README.pt-BR.md)

**面向進階使用者的 Android 特權程序管理員**

ShizukuX 是 [Shizuku](https://github.com/RikkaApps/Shizuku) 的社群增強分支，在 [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) 與 [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus) 的基礎上發展而來，繼承了原專案的全部能力，並針對使用者進行了本地化與體驗最佳化。

Shizuku 可以讓一般應用程式借助 **ADB / Root** 啟動的特權程序，直接呼叫系統級 API。ShizukuX 在保持 100% 相容的同時，為進階使用者與開發者加入了更多實用能力。

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)
[![License](https://img.shields.io/github/license/qianyumeng0228/ShizukuX?style=for-the-badge&color=30bf60&labelColor=208040)](LICENSE)

> **繼承說明**：ShizukuX 由 ShizukuPlus（qianyumeng0228）改名並中文本地化而來，是 ShizukuPlus 的更名延續；ShizukuPlus 本身是 thejaustin/ShizukuPlus 與 thedjchi/Shizuku 的分支，最終追溯至 RikkaApps 的 Shizuku 專案。ShizukuX 是一個獨立維護的社群分支，與 **RikkaApps/Shizuku、thedjchi/Shizuku、thejaustin/ShizukuPlus 及其維護者均無任何隸屬、背書或合作關係**。全部上游版權與署名聲明均完整保留（見 [LICENSE](LICENSE)、[NOTICE](NOTICE)、[CHANGES.md](CHANGES.md)）。

</div>

## 📑 目錄

- [🚀 快速開始](#quickstart)
- [⬇️ 下載](#download)
- [✨ 核心功能](#features)
- [🛡️ 穩定性與安全](#security)
- [🚀 Extra API 增強能力](#extra-api)
- [🔌 第三方應用程式相容性](#compat)
- [☑️ 系統需求](#requirements)
- [📱 開發者指南與文件](#developer-guide)
- [🗂️ 倉庫結構](#repo-layout)
- [📦 近期更新](#releases)
- [🙏 致謝與授權](#credits)

<a name="quickstart"></a>
## 🚀 快速開始

1. 在 [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) 下載最新 **標準版** APK 並安裝（獨立包名 `xyz.shizuku.extra.api`，可與原版 Shizuku 共存；**Drop-In 版**以原版包名安裝、用於替換，不可共存）。
2. 開啟應用程式，依提示用 **無線偵錯 / 電腦 ADB / Root** 之一啟動服務（啟動約 9 秒）。
3. 在應用程式內授權需要 Shizuku 權限的應用程式，即可開始使用。

> 詳細的分步說明、常見錯誤與故障排除見[中文文件](docs/zh-CN/index.md)。

<a name="download"></a>
## ⬇️ 下載

請在 [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) 取得最新版本。每個版本均提供以下三個 APK：

| 版本 | 包名 | 適用情境 |
|---|---|---|
| **標準版** | `xyz.shizuku.extra.api` | 全新安裝，獨立包名，可與其他 Shizuku 版本共存 |
| **Drop-In 版** | `moe.shizuku.privileged.api` | 直接替換原版 Shizuku，相容所有使用 Shizuku API 的應用程式（請勿與原版 Shizuku 同時安裝） |
| **Compat-Hub** | `moe.shizuku.privileged.api` | 相容性伴生模組，讓只偵測原版包名的第三方應用程式辨識 ShizukuX（隨標準版內建，可在應用程式內安裝/解除安裝） |

- 正式版以 **`r` 系列**標識（如 `v13.7.0.r2002`），預發布以 **`k` 系列**標識（如 `v13.7.0.k2016`），均包含上述三個 APK。
- 各版本的具體更新內容見對應發布說明，完整歷史見 [CHANGELOG.md](CHANGELOG.md)。

<a name="features"></a>
## ✨ ShizukuX 核心功能

*   **通用權限提供器**：一套介面統一接入 **Root**、**ADB Shell** 與 **Dhizuku（裝置擁有者）** 三種權限來源。
*   **OneUI 8+ 主題修復**：讓 Hex Installer、Substratum 等主題引擎在 Android 16/17 與 OneUI 8+ 上繼續正常運作。
*   **Dhizuku 模式**：將系統 Device Owner Binder 共享給任意持有 Shizuku 權限的應用程式——透過 ADB 即可設定，無需 Root；獨立「權限等級」頁統一管理 Shell / Root / DO 三種等級。
*   **細粒度權限管理員**：按應用程式批量授予 / 回收 Shizuku 權限的獨立介面，與首頁卡片連動。
*   **裝置控制**：連線、顯示、音訊、電源與系統設定五大類 18 項操作（飛航模式、自動亮度、自動旋轉、動畫縮放、重新啟動 / 關機等），ADB 模式下危險項目自動置灰並提示。
*   **備份與還原**：SAF 匯出到公用目錄、批量備份 / 還原（含 OBB 資料）、內部資料（`run-as` + tar）還原，還原前自動快照與還原歷史記錄。
*   **可自訂手勢**：支援左滑、右滑、長按等操作，可針對單一應用程式分別設定。
*   **應用程式內更新日誌**：更新後無需離開應用程式即可查看新內容。
*   **批量管理**：多選應用程式，一鍵授予/回收權限或隱藏應用程式。
*   **活動日誌**：即時稽核 API 呼叫與 `su` 橋接命令，附帶應用程式圖示並即時重新整理。
*   **Root 相容中心**：為舊版 Root 應用程式提供儀表板，支援細粒度模組控制（Magisk 偽裝、自動授權、檔案攔截等）。
*   **通用 SU 自動化**：一鍵「魔法設定」，將所有已安裝的 Root 應用程式指向 ShizukuX SU 橋。
*   **服務診斷**：診斷並修復服務啟動問題（含三星 Auto Blocker），診斷結果可一鍵匯出崩潰堆疊。
*   **Stealth Mode（隱藏痕跡）**：攔截 `IPackageManager` 查詢，從系統應用程式清單中隱藏 ShizukuX 自身。
*   **個人化控制**：加粗排版、擴展標頭、圓角覆蓋，動效與觸感可跟隨系統開關。
*   **AMOLED 純黑主題**：補齊全 9 個 surface token，純黑模式下卡片不再發灰。
*   **電池最佳化即時感知**：從系統設定返回即重新檢查，MIUI / HyperOS「省電策略」也統一判定，彈窗不再殘留。
*   **內建功能引導**：每個增強功能都帶有說明圖示，用通俗語言解釋其作用。
*   **快捷設定磁貼**：在通知欄即可查看並切換服務狀態（含飛航模式、動畫、亮度、旋轉等裝置控制磁貼）。

<a name="security"></a>
## 🛡️ 穩定性與安全

*   **第三方用戶端相容（AIDL 交易碼偏移）**：相容官方 v13.7+ 與 LSPatch 等使用偏移 AIDL 交易碼的第三方用戶端，授權請求不再被誤判為其他呼叫。
*   **ADB 代理握手鑑權**：15555 連接埠本地 ADB 代理驗證 RSA 簽名（即持有私鑰）並綁定呼叫方 UID，偽造公鑰無法繞過，第三方用戶端線協定無感。
*   **崩潰復原鏈路加固**：無線偵錯自啟前先做前提探測，未設定 TCP 連接埠時回退 root 啟動，兩者皆不可用時明確通知；連續崩潰降級非永久，15 分鐘冷卻後自動重新武裝自動重新啟動。
*   **isRooted 授權探測修復**：相容 KernelSU / SukiSU 的 SELinux Enforcing 環境——除檔案 stat 外結合 libsu 授權記錄（5 分鐘 TTL），不再把已 Root 裝置誤判為無 Root。
*   **HyperOS / MIUI 套件可見性修復**：應用程式清單經伺服器端 IPC 取得（shell-uid PackageManager），解決澎湃 OS 套件可見性限制導致的應用程式清單空白。
*   **伺服器端加固**：shell 注入防護（入口統一包名正規表示式驗證，拒絕 `/`、`..`、空格）、APK Signature Scheme v2 簽名正確性修復、大 APK 串流式簽名避免 OOM、凍結套件集合快取（`isAppFrozen` 為 O(1)）。
*   **啟動加速**：無線偵錯直連 `service.adb.tls.port` 連接埠，不再空等 mDNS 探索，啟動流程從約 2 分鐘降至約 9 秒。
*   **手動停止不再自啟**：停止服務時一併停掉 starter supervisor，不再出現「點了停止又復活」。
*   **耗電最佳化**：動畫跟隨系統動效開關、指標輪詢降頻、下載通知按百分比節流、非執行狀態不再駐留程序。

<a name="extra-api"></a>
## 🚀 Extra API 增強能力

ShizukuX 提供原版 Shizuku **沒有**的專屬系統介面，供進階自動化與工具使用：

*   **AICore Extra 自動化橋**：面向 AI 工具的無 Root 特權 UI 自動化（層級轉儲、點擊/滑動）。（[引入記錄](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **AVF（虛擬機器）管理員**：執行帶 GPU 加速的隔離 Linux/Microdroid 虛擬機器。（[引入記錄](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6)）
*   **特權儲存代理**：對受限路徑（`/data/data/`、`/data/app/`）提供鑑權存取，用於備份與檔案管理。（[引入記錄](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6)）
*   **裝置偽裝**（設定中的 *Spoof Device Identity*）：向系統呈現不同的裝置資訊，繞過針對特定機型的限制。（[引入記錄](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44)）
*   **智慧橋**（*AI Core Extra*）：特權 NPU 排程與螢幕上下文智慧。（[引入記錄](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **Window Manager Extra**：強制自由視窗縮放、管理懸浮氣泡列與彈性覆蓋層。（[引入記錄](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **系統主題橋**（*Overlay Manager Extra*）：無 Root 的 Overlay 特權管理，用於主題化（如 Hex Installer）。（[引入記錄](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **網路與 DNS 治理器**：管理私有 DNS 與防火牆路由，支撐無 Root 的廣告攔截。（[引入記錄](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **深度程序控制**（*Activity Manager Extra*）：讓程序管理員更激進地結束應用程式、設定待機分組。（[引入記錄](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **連續性橋**：在多個 ShizukuX 裝置之間安全傳遞狀態與任務。（[引入記錄](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7)）

## 🛠️ 反向移植與效能最佳化

無需修改任何程式碼，ShizukuX 即可讓一般 Shizuku 應用程式更快、更相容：

*   **透明 Shell 攔截器**：將常見的 `pm`、`am`、`settings` 命令路由到更快的原生 API。
*   **本地 ADB 代理**：在 15555 連接埠模擬 ADB 伺服器，讓舊應用程式在無線偵錯關閉後仍能使用 Shizuku。
*   **SU 橋**：為支援自訂 Root 路徑的無 Root 應用程式，提供基於 Shizuku 的 `su` 替代方案。
*   **`extra` 命令列工具**：可在 `rish` 內使用的特權命令列工具。
*   **動態應用程式資料庫**：從 GitHub 即時同步應用程式描述與建議，保持介面資訊最新。

## ⚙️ 模組化控制

ShizukuX 的一切能力都是可選的。在設定的 **Extra 功能**分類中可以開關：

*   透明 Shell 攔截
*   各項 Extra API（AVF、儲存、智慧等）
*   首頁卡片顯示
*   活動日誌

<a name="compat"></a>
## 🔌 第三方應用程式相容性

ShizukuX 以獨立包名（`xyz.shizuku.extra.api`）安裝，可與原版 Shizuku 共存。由於多數 Shizuku 應用程式只會偵測 `moe.shizuku.privileged.api` 這個包名，ShizukuX 內建了一個輕量級 **相容中心（Compat Hub）**——一個註冊該包名的微型伴生應用程式，負責將 binder/權限請求轉發給 ShizukuX。

**如果第三方應用程式偵測不到 ShizukuX：**

1. 先啟動 ShizukuX 服務（ADB 或 Root 方式）。
2. 在首頁使用 **相容中心** 卡片安裝伴生應用程式（已隨應用程式打包；安裝過程依賴正在執行的服務，所以請先啟動服務）。
3. 重新開啟第三方應用程式——它現在應該能偵測到 Shizuku 並收到服務 binder。

也可以安裝 **直接替換版（drop-in）**，它直接以 `moe.shizuku.privileged.api` 註冊（注意不要與原版 Shizuku 同時安裝）。

<a name="requirements"></a>
## ☑️ 系統需求

**最低：Android 7+ · 完整支援至 Android 17（SDK 37）**

- **Root 模式**：需要已 Root 裝置（相容 Magisk / KernelSU / SukiSU / APatch 等）
- **無線偵錯模式**：Android 11+ 及所有 Android TV
- **電腦 ADB 模式**：所有裝置
- **開機自啟**：僅無線偵錯或 Root 模式可用

在 **Android 16+** 上，ShizukuX 會申請新的本地網路保護權限，以保證無線偵錯的探索與配對功能正常；在 **Android 17** 上，它透明地處理隱藏 API `deviceId` 變更，使已授權應用程式依然可見、權限授予依然生效。

<a name="developer-guide"></a>
## 📱 開發者指南與文件

關於專屬 Extra API 的文件，見 [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API)（本倉庫的 `api` 子模組即指向該倉庫）。維護 Shizuku 分支並希望遷移到 ShizukuX 的開發者，可參考 [FORK_MIGRATION.md](FORK_MIGRATION.md)。

面向使用者的完整中文文件（服務連線、知識庫、權限說明、Dhizuku 模式等）見 [docs/zh-CN/index.md](docs/zh-CN/index.md)。

<a name="repo-layout"></a>
## 🗂️ 倉庫結構

| 目錄 | 說明 |
|---|---|
| `manager/` | 主應用程式：首頁卡片、設定、權限管理、裝置控制、備份/還原、Watchdog、更新等 |
| `server/` | 特權服務程序：ShizukuService 與全部 Extra API 實作 |
| `core/` | 共享核心庫（UI 元件、主題、字串資源） |
| `api/` | [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) 子模組 |
| `compat/` | 相容中心（Compat Hub）伴生應用程式 |
| `shell/` | `rish` / `extra` 特權命令列工具 |
| `starter/` | 服務啟動器（ADB / Root / supervisor 拉起鏈路） |
| `common/` / `app-process/` / `database/` | 公用程式碼、獨立程序封裝、資料庫層 |
| `docs/` | 中文文件（應用程式內說明與更新說明指向這裡） |

<a name="releases"></a>
## 📦 近期更新

| 版本 | 類型 | 發布日期 | 主要更新 |
|---|---|---|---|
| **v13.7.0.k2016** | 預發布 | 2026-10-26 | 手動停止後不再自啟、root 啟動與授權狀態連動（isRooted 實測兜底）、啟動加速（約 2 分鐘→9 秒）、終端教學頁匯出與命令修正 |
| v13.7.0.k2015 | 預發布 | 2026-10-25 | 全倉 5.3 萬行審查 13 項修復、無線偵錯自啟 / 崩潰復原鏈路加固、設定頁按「一般/權限/服務/診斷」重排 |
| v13.7.0.k2014 | 預發布 | 2026-10-07 | 第三方用戶端 AIDL 交易碼偏移相容、裝置控制 / 備份全套、Stealth Mode、AMOLED 主題修復、相容中心一鍵解除安裝 |
| v13.7.0.r2002 | 正式版 | 2026-09-22 | v13.7.0 正式發版線 |

更完整的變更記錄見 [CHANGELOG.md](CHANGELOG.md) 與各版本 [Release Notes](https://github.com/qianyumeng0228/ShizukuX/releases)。

<a name="credits"></a>
## 🙏 致謝與授權

ShizukuX 是社群驅動的增強分支，繼承關係與免責聲明見文首「繼承說明」。

感謝以下上游貢獻者與專案，是他們的工作讓 ShizukuX 成為可能：

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — 奠定 Shizuku 基礎專案與優雅的 API 設計。
- **[thedjchi](https://github.com/thedjchi)** — 中間分叉與體驗改進，並承擔了 **Android 17（SDK 37）相容性**工作，ShizukuX 的 A17 支援由此移植而來。
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — Android 17 隱藏 API `deviceId` 相容方案（`Android17Compat` / `InstalledPackagesCompat` 反射層）與本地網路保護處理的源頭。
- **[LandonMoran](https://github.com/LandonMoran)** — 將 Nightzuku 的 Android 17 支援移植進 thedjchi 分支，並在**真機 Android 17 裝置上端對端驗證**（配對、服務啟動、已授權應用程式清單），為 ShizukuX 的移植提供了實機驗證基礎。
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — aShell You 程式碼庫，啟發了終端與 Shell 自動化功能。
- **[iamr0s](https://github.com/iamr0s)** — 提供 Dhizuku，實現統一的 Device Owner 權限模式；以及用於獨立 Java 程序執行的 AndroidAppProcess。
- **[pascua28](https://github.com/pascua28)** — 原生三星 System UID 1000 提權整合。
- **[kerneldroid](https://github.com/kerneldroid)** — Nightzuku 分支，啟發了我們的 Android 16/17（SDK 37）隱藏 API 韌性（處理 `deviceId`）與介面現代化。
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — 提出超越常規限制的系統級 UID 1000 提權概念。

### 上游專案

| 專案 | 作者 | 授權 | 角色 |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | 基礎特權程序架構 |
| [Shizuku（分支）](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | 帶體驗改進的中間分支；承載 ShizukuX 所適配的 Android 17 相容工作 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 17 隱藏 API `deviceId` + 本地網路保護相容方案來源 |
| [Shizuku（分支）](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | 三星 UID 1000 系統執行方案 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 16/17 API 韌性與介面現代化 |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | ShizukuExt 團隊 | Apache 2.0 | 系統 UID 提權概念 |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Device Owner binder 共享（Dhizuku 模式） |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | 獨立高特權 Java 程序封裝 |

### 開源庫

本專案使用了以下主要開源庫（完整清單與各庫授權文本見 [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) 與 [NOTICE](NOTICE)）：AndroidX Jetpack、Material Components、Kotlin / Coroutines、RikkaX Libraries、Mavericks、Lottie、Coil、Koin、Timber、libsu、AndroidHiddenApiBypass、Gson、LeakCanary、AboutLibraries、Bouncy Castle、Sentry、SQLite 等。

## 📃 授權

[Apache 2.0](LICENSE)

### 致謝
- 特別感謝 **AkayamiShurui42** 積極主動的安全研究與穩定性修補（參考：#239）。
- 感謝 **AlexeiCrystal** 定位 MIUI 崩潰問題，並提出用於舊應用程式的 Compat Hub 相容方案（#241、#242）。
- 感謝 **ddnexus** 與 **kai-bash** 指出 Device Owner 恢復出廠設定陷阱與 Google 備份衝突（#237）。
- 感謝 **Kevinco1** 對 Root 相容應用程式偵測問題的回饋（#243）。
- 感謝 **aragortsantiago6-beep**、**Scoop2389**（Pixel 9a）與 **ConversionRituals**（小米）的實機 Android 16/17 測試、崩潰報告與日誌，推動了 SDK 37 隱藏 API 與本地網路保護相容性修復（#317、#323）。
- 感謝 **gmm96** 多輪 logcat 偵錯，最終定位了 Cached Apps Freezer 的 binder 投遞 bug（#371）。
- 感謝 **[odorizzioficial](https://github.com/odorizzioficial)** 提供完整的巴西葡萄牙語翻譯（#409），以及三星 "Sleeping apps" 看門狗凍結的詳細報告（#415）。

## 🤝 貢獻與支援

- 提交問題與建議：[GitHub Issues](https://github.com/qianyumeng0228/ShizukuX/issues)
- 參與開發：見 [CONTRIBUTING.md](CONTRIBUTING.md)
- 安全漏洞回報：見 [SECURITY.md](SECURITY.md)
- 完整更新歷史：[CHANGELOG.md](CHANGELOG.md) · [CHANGES.md](CHANGES.md)
