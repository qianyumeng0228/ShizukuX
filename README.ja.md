<div align="center">

# ShizukuX

[简体中文](./README.md) · [English](./README.en.md) · [繁體中文](./README.zh-TW.md) · **日本語** · [Deutsch](./README.de.md) · [Français](./README.fr.md) · [Español](./README.es.md) · [Português](./README.pt-PT.md) · [Italiano](./README.it.md) · [한국어](./README.ko.md) · [Português (Brasil)](./README.pt-BR.md)

**上級ユーザー向け Android 特権プロセス管理ツール**

ShizukuX は [Shizuku](https://github.com/RikkaApps/Shizuku) のコミュニティ拡張フォークで、[thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) と [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus) を基盤に発展し、オリジナルプロジェクトの全機能を継承しつつ、ユーザー向けにローカライズと体験の最適化を行っています。

Shizuku は、**ADB / Root** で起動した特権プロセスを通じて、一般アプリがシステムレベルの API を直接呼び出せるようにする仕組みです。ShizukuX は 100% の互換性を保ちながら、上級ユーザーと開発者向けにさらに実用的な機能を追加しています。

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)
[![License](https://img.shields.io/github/license/qianyumeng0228/ShizukuX?style=for-the-badge&color=30bf60&labelColor=208040)](LICENSE)

> **継承について**: ShizukuX は ShizukuPlus（qianyumeng0228）の改名・中国語ローカライズ版であり、ShizukuPlus の継続プロジェクトです。ShizukuPlus 自体は thejaustin/ShizukuPlus と thedjchi/Shizuku のフォークで、最終的には RikkaApps の Shizuku プロジェクトに遡ります。ShizukuX は独立して保守されるコミュニティフォークであり、**RikkaApps/Shizuku、thedjchi/Shizuku、thejaustin/ShizukuPlus およびそのメンテナーとは一切の関係・推薦・提携はありません**。上流の著作権・クレジット表示はすべて完全に保持しています（[LICENSE](LICENSE)、[NOTICE](NOTICE)、[CHANGES.md](CHANGES.md) を参照）。

</div>

## 📑 目次

- [🚀 クイックスタート](#quickstart)
- [⬇️ ダウンロード](#download)
- [✨ コア機能](#features)
- [🛡️ 安定性とセキュリティ](#security)
- [🚀 Extra API 拡張機能](#extra-api)
- [🔌 サードパーティアプリとの互換性](#compat)
- [☑️ システム要件](#requirements)
- [📱 開発者ガイドとドキュメント](#developer-guide)
- [🗂️ リポジトリ構成](#repo-layout)
- [📦 最近のリリース](#releases)
- [🙏 謝辞とライセンス](#credits)

<a name="quickstart"></a>
## 🚀 クイックスタート

1. [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) から最新の **標準版（Standard）** APK をダウンロードしてインストールします（独立パッケージ名 `xyz.shizuku.extra.api` で、オリジナル Shizuku と共存可能。**Drop-In 版**はオリジナルのパッケージ名でインストールされ置き換え用のため、共存できません）。
2. アプリを開き、**ワイヤレスデバッグ / PC の ADB / Root** のいずれかでサービスを起動します（起動は約 9 秒）。
3. アプリ内で Shizuku 権限を付与したいアプリを許可すれば、すぐに利用を開始できます。

> 詳細な手順、よくあるエラー、トラブルシューティングは[中国語ドキュメント](docs/zh-CN/index.md)（現在中国語のみ）を参照してください。

<a name="download"></a>
## ⬇️ ダウンロード

最新版は [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) から入手してください。各リリースには次の 3 つの APK が含まれます：

| バリアント | パッケージ名 | 用途 |
|---|---|---|
| **標準版（Standard）** | `xyz.shizuku.extra.api` | 独立パッケージ名での新規インストール。他の Shizuku ビルドと共存可能 |
| **Drop-In 版** | `moe.shizuku.privileged.api` | オリジナル Shizuku の直接置き換え。Shizuku API を使うすべてのアプリと互換（オリジナル Shizuku との同時インストールは不可） |
| **Compat-Hub** | `moe.shizuku.privileged.api` | オリジナルのパッケージ名のみを検出するサードパーティアプリに ShizukuX を認識させる軽量コンパニオン（標準版に同梱、アプリ内からインストール/アンインストール） |

- 安定版は **`r` シリーズ**（例: `v13.7.0.r2002`）、プレリリースは **`k` シリーズ**（例: `v13.7.0.k2016`）で識別され、いずれも上記 3 つの APK を含みます。
- 各バージョンの詳細はリリースノート、完全な履歴は [CHANGELOG.md](CHANGELOG.md) を参照してください。

<a name="features"></a>
## ✨ ShizukuX コア機能

*   **ユニバーサル権限プロバイダー**: **Root**、**ADB Shell**、**Dhizuku（デバイスオーナー）** の 3 つの権限ソースを 1 つのインターフェースに統合。
*   **OneUI 8+ テーマ修正**: Hex Installer、Substratum などのテーマエンジンを Android 16/17 と OneUI 8+ で引き続き正常に動作させます。
*   **Dhizuku モード**: システムの Device Owner Binder を Shizuku 権限を持つ任意のアプリと共有。ADB で設定可能、Root 不要。専用の「権限レベル」ページで Shell / Root / DO の 3 レベルを一元管理。
*   **きめ細かい権限マネージャー**: アプリごとに Shizuku 権限を一括付与/取り消しできる専用画面。ホームカードと連動。
*   **デバイスコントロール**: 接続・表示・音声・電源・システム設定の 5 カテゴリ 18 操作（機内モード、自動輝度、自動回転、アニメーション倍率、再起動 / シャットダウンなど）。ADB モードでは危険な項目を自動でグレーアウトして警告。
*   **バックアップと復元**: SAF による公開ディレクトリへのエクスポート、一括バックアップ / 復元（OBB データ含む）、内部データ（`run-as` + tar）復元、復元前の自動スナップショットと復元履歴。
*   **カスタマイズ可能なジェスチャー**: 左スワイプ、右スワイプ、長押しなどをアプリごとに個別設定可能。
*   **アプリ内更新ログ**: アップデート後にアプリを離れずに新機能を確認できます。
*   **一括管理**: 複数アプリを選択して、権限の付与/取り消しや非表示をワンタップで実行。
*   **アクティビティログ**: API 呼び出しと `su` ブリッジコマンドをアプリアイコン付きでリアルタイム監査・更新。
*   **Root 互換ハブ**: レガシー Root アプリ向けのダッシュボード。細かなモジュール制御（Magisk 偽装、自動許可、ファイルインターセプトなど）をサポート。
*   **ユニバーサル SU 自動化**: ワンタップ「マジック設定」で、インストール済みの全 Root アプリを ShizukuX SU ブリッジに向けます。
*   **サービス診断**: サービス起動の問題（Samsung Auto Blocker 含む）を診断・修復し、クラッシュスタックをワンタップでエクスポート。
*   **ステルスモード（痕跡隠し）**: `IPackageManager` クエリをインターセプトし、システムのアプリ一覧から ShizukuX 自身を隠します。
*   **パーソナライズ制御**: 太字レイアウト、拡張ヘッダー、角丸オーバーライド。アニメーションと触覚はシステム設定に連動。
*   **AMOLED ピュアブラックテーマ**: 9 つの surface token をすべて修正し、ピュアブラックモードでカードがグレーに見えなくなりました。
*   **バッテリー最適化のリアルタイム検知**: システム設定から戻ると即再チェック。MIUI / HyperOS の「省電力ポリシー」も統一的に判定し、古いバナーが残りません。
*   **内蔵機能ガイド**: すべての拡張機能に説明アイコンが付き、わかりやすい言葉で目的を説明します。
*   **クイック設定タイル**: 通知シェードからサービス状態を確認・切り替え可能（機内モード、アニメーション、輝度、回転などのデバイスコントロールタイルも含む）。

<a name="security"></a>
## 🛡️ 安定性とセキュリティ

*   **サードパーティクライアント互換（AIDL トランザクションコードオフセット）**: オフセット AIDL トランザクションコードを使う公式 v13.7+ や LSPatch 系クライアントと互換。権限リクエストが他の呼び出しに誤認されることがなくなりました。
*   **ADB プロキシのハンドシェイク認証**: 15555 ポートのローカル ADB プロキシが RSA 署名（秘密鍵の保持）を検証し、呼び出し元 UID をバインド。偽造公開鍵は突破できず、サードパーティクライアントのワイヤプロトコルは変更不要。
*   **クラッシュ復旧チェーンの強化**: ワイヤレスデバッグ起動前に前提条件をプローブし、TCP ポート未設定時は root 起動にフォールバック。どちらも不可なら明確に通知。連続クラッシュによる劣化は永続的ではなく、15 分のクールダウン後に自動再起動を再武装。
*   **isRooted 検出修正**: SELinux Enforcing の KernelSU / SukiSU 環境と互換。ファイル stat に加えて libsu 認可記録（5 分 TTL）も参照し、Root 済みデバイスを未 Root と誤判定しません。
*   **HyperOS / MIUI パッケージ可視性修正**: アプリ一覧をサーバー IPC（shell-uid PackageManager）経由で取得し、HyperOS のパッケージ可視性制限による空リストを解消。
*   **サーバー側の堅牢化**: shell インジェクション対策（エントリごとにパッケージ名の正規表現検証、`/`・`..`・スペースを拒否）、APK Signature Scheme v2 署名の正確性修正、大容量 APK のストリーミング署名による OOM 回避、フリーズ済みパッケージ集合のキャッシュ（`isAppFrozen` は O(1)）。
*   **起動の高速化**: ワイヤレスデバッグが mDNS ディスカバリを待たずに `service.adb.tls.port` へ直接接続。起動が約 2 分から約 9 秒に短縮。
*   **手動停止で再起動しない**: サービス停止時に starter supervisor も停止するため、「停止をタップしたのに 5 秒後に復活する」ことがなくなりました。
*   **バッテリー最適化**: アニメーションはシステムのモーション設定に連動、メトリクスポーリングを間引き、ダウンロード通知はパーセント単位でスロットル、アイドル時にプロセスを常駐させません。

<a name="extra-api"></a>
## 🚀 Extra API 拡張機能

ShizukuX は、オリジナルの Shizuku **にはない**専用システムインターフェースを提供します。高度な自動化とツール向けです：

*   **AICore Extra 自動化ブリッジ**: AI ツール向けの Root 不要・特権 UI 自動化（階層ダンプ、タップ/スワイプ）。（[導入記録](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **AVF（仮想マシン）マネージャー**: GPU アクセラレーション付きの隔離 Linux/Microdroid VM を実行。（[導入記録](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6)）
*   **特権ストレージプロキシ**: 制限パス（`/data/data/`、`/data/app/`）への認証付きアクセス。バックアップとファイル管理用。（[導入記録](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6)）
*   **デバイス偽装**（設定の *Spoof Device Identity*）: システムに異なるデバイス情報を提示し、機種固有の制限を回避。（[導入記録](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44)）
*   **スマートブリッジ**（*AI Core Extra*）: 特権 NPU スケジューリングと画面コンテキストインテリジェンス。（[導入記録](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **Window Manager Extra**: フリーフォームウィンドウの強制リサイズ、フローティングバブルバーと弾性オーバーレイの管理。（[導入記録](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187)）
*   **システムテーマブリッジ**（*Overlay Manager Extra*）: Root 不要の特権 Overlay 管理。テーマ適用用（例: Hex Installer）。（[導入記録](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **ネットワーク & DNS ガバナー**: プライベート DNS とファイアウォールルーティングを管理し、Root 不要の広告ブロックを実現。（[導入記録](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **ディーププロセス制御**（*Activity Manager Extra*）: プロセスマネージャーがアプリをより積極的に終了し、スタンバイバケットを設定可能に。（[導入記録](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7)）
*   **コンティニュイティブリッジ**: 複数の ShizukuX デバイス間で状態とタスクを安全に転送。（[導入記録](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7)）

## 🛠️ バックポートとパフォーマンス最適化

コードを 1 行も変更せずに、一般の Shizuku アプリをより高速で互換性高くします：

*   **透過 Shell インターセプター**: 一般的な `pm`、`am`、`settings` コマンドをより高速なネイティブ API にルーティング。
*   **ローカル ADB プロキシ**: 15555 ポートで ADB サーバーをエミュレートし、ワイヤレスデバッグをオフにした後もレガシーアプリが Shizuku を使い続けられるように。
*   **SU ブリッジ**: カスタム Root パスをサポートする Root 不要アプリ向けの、Shizuku ベースの `su` 代替。
*   **`extra` コマンドラインツール**: `rish` 内で使える特権 CLI。
*   **動的アプリデータベース**: GitHub からアプリの説明と提案をリアルタイムに同期し、UI を最新に維持。

## ⚙️ モジュール制御

ShizukuX のすべての機能はオプションです。設定の **Extra 機能**カテゴリでオン/オフできます：

*   透過 Shell インターセプト
*   各 Extra API（AVF、ストレージ、スマートなど）
*   ホームカード表示
*   アクティビティログ

<a name="compat"></a>
## 🔌 サードパーティアプリとの互換性

ShizukuX は独立パッケージ名（`xyz.shizuku.extra.api`）でインストールされ、オリジナル Shizuku と共存できます。多くの Shizuku アプリは `moe.shizuku.privileged.api` というパッケージ名しか検出しないため、ShizukuX は軽量な **互換ハブ（Compat Hub）** を同梱しています。これはそのパッケージ名を登録し、binder/権限リクエストを ShizukuX に転送するマイクロコンパニオンアプリです。

**サードパーティアプリが ShizukuX を検出できない場合：**

1. 先に ShizukuX サービスを起動します（ADB または Root 方式）。
2. ホーム画面の **互換ハブ** カードからコンパニオンアプリをインストールします（APK に同梱されています。インストールは実行中のサービスに依存するため、先にサービスを起動してください）。
3. サードパーティアプリを開き直すと、Shizuku を検出してサービス binder を受信できるようになります。

**直接置き換え版（drop-in）** をインストールすることもできます。こちらは `moe.shizuku.privileged.api` として直接登録されます（オリジナル Shizuku との同時インストールは避けてください）。

<a name="requirements"></a>
## ☑️ システム要件

**最小: Android 7+ · フルサポート: Android 17（SDK 37）まで**

- **Root モード**: Root 済みデバイスが必要（Magisk / KernelSU / SukiSU / APatch などと互換）
- **ワイヤレスデバッグモード**: Android 11+ およびすべての Android TV
- **PC の ADB モード**: すべてのデバイス
- **起動時の自動起動**: ワイヤレスデバッグまたは Root モードのみ

**Android 16+** では、ShizukuX はワイヤレスデバッグのディスカバリとペアリングを維持するため、新しいローカルネットワーク保護権限を要求します。**Android 17** では、非表示 API `deviceId` の変更を透過的に処理し、許可済みアプリの可視性と権限付与を維持します。

<a name="developer-guide"></a>
## 📱 開発者ガイドとドキュメント

専用 Extra API のドキュメントは [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API)（このリポジトリの `api` サブモジュールが参照）にあります。Shizuku フォークのメンテナーで ShizukuX への移行を検討している方は [FORK_MIGRATION.md](FORK_MIGRATION.md) を参照してください。

エンドユーザー向けの完全な中国語ドキュメント（サービス接続、ナレッジベース、権限、Dhizuku モードなど）は [docs/zh-CN/index.md](docs/zh-CN/index.md)（現在中国語のみ）にあります。

<a name="repo-layout"></a>
## 🗂️ リポジトリ構成

| ディレクトリ | 説明 |
|---|---|
| `manager/` | メインアプリ: ホームカード、設定、権限管理、デバイスコントロール、バックアップ/復元、Watchdog、更新 |
| `server/` | 特権サービスプロセス: ShizukuService と全 Extra API 実装 |
| `core/` | 共有コアライブラリ（UI コンポーネント、テーマ、文字列リソース） |
| `api/` | [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) サブモジュール |
| `compat/` | 互換ハブ（Compat Hub）コンパニオンアプリ |
| `shell/` | `rish` / `extra` 特権コマンドラインツール |
| `starter/` | サービススターター（ADB / Root / supervisor 起動チェーン） |
| `common/` / `app-process/` / `database/` | 共通コード、スタンドアロンプロセスラッパー、データベース層 |
| `docs/` | ドキュメント（アプリ内ヘルプと更新ノートがここを参照） |

<a name="releases"></a>
## 📦 最近のリリース

| バージョン | タイプ | 日付 | 主な更新 |
|---|---|---|---|
| **v13.7.0.k2016** | プレリリース | 2026-10-26 | 手動停止で再起動しない、root 起動と権限状態の連動（isRooted 実測フォールバック）、起動高速化（約 2 分→9 秒）、ターミナルチュートリアルのエクスポートとコマンド修正 |
| v13.7.0.k2015 | プレリリース | 2026-10-25 | 全リポジトリ 5.3 万行レビューによる 13 件の修正、ワイヤレスデバッグ自動起動 / クラッシュ復旧チェーン強化、設定ページを「一般/権限/サービス/診断」に再編 |
| v13.7.0.k2014 | プレリリース | 2026-10-07 | サードパーティクライアント AIDL トランザクションコードオフセット互換、デバイスコントロール / バックアップ一式、ステルスモード、AMOLED テーマ修正、互換ハブのワンタップアンインストール |
| v13.7.0.r2002 | 安定版 | 2026-09-22 | v13.7.0 安定版ライン |

完全な変更履歴は [CHANGELOG.md](CHANGELOG.md) と各リリースの [Release Notes](https://github.com/qianyumeng0228/ShizukuX/releases) を参照してください。

<a name="credits"></a>
## 🙏 謝辞とライセンス

ShizukuX はコミュニティ駆動の拡張フォークです。継承関係と免責事項は文頭の「継承について」を参照してください。

ShizukuX を可能にした上流の貢献者とプロジェクトに感謝します：

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — Shizuku の基盤プロジェクトとエレガントな API 設計。
- **[thedjchi](https://github.com/thedjchi)** — 中間フォークと体験改善。**Android 17（SDK 37）互換性**の作業を担い、ShizukuX の A17 サポートはここから移植されました。
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — Android 17 の非表示 API `deviceId` 互換（`Android17Compat` / `InstalledPackagesCompat` リフレクションレイヤー）とローカルネットワーク保護処理の源流。
- **[LandonMoran](https://github.com/LandonMoran)** — Nightzuku の Android 17 サポートを thedjchi フォークに移植し、**実機 Android 17 デバイスでエンドツーエンド検証**（ペアリング、サービス起動、許可済みアプリ一覧）を実施。ShizukuX 移植の実機検証基盤を提供。
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — aShell You のコードベース。ターミナルと Shell 自動化機能の着想源。
- **[iamr0s](https://github.com/iamr0s)** — 統一 Device Owner 権限モードを実現する Dhizuku、および独立 Java プロセス実行用の AndroidAppProcess を提供。
- **[pascua28](https://github.com/pascua28)** — ネイティブ Samsung System UID 1000 権限統合。
- **[kerneldroid](https://github.com/kerneldroid)** — Nightzuku フォーク。Android 16/17（SDK 37）の非表示 API 耐性（`deviceId` 対応）と UI 近代化の着想源。
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — 通常の制限を超えたシステムレベル UID 1000 権限のコンセプト。

### 上流プロジェクト

| プロジェクト | 作者 | ライセンス | 役割 |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | 基盤の特権プロセスアーキテクチャ |
| [Shizuku（フォーク）](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | 体験改善を含む中間フォーク。ShizukuX が適応する Android 17 互換作業を保有 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 17 非表示 API `deviceId` + ローカルネットワーク保護互換のソース |
| [Shizuku（フォーク）](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | Samsung UID 1000 システム実行方式 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 16/17 API 耐性と UI 近代化 |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | ShizukuExt チーム | Apache 2.0 | システム UID 権限のコンセプト |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Device Owner binder 共有（Dhizuku モード） |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | 独立高特権 Java プロセスラッパー |

### オープンソースライブラリ

このプロジェクトは以下の主要オープンソースライブラリを使用しています（完全なリストと各ライブラリのライセンステキストは [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) と [NOTICE](NOTICE) を参照）：AndroidX Jetpack、Material Components、Kotlin / Coroutines、RikkaX Libraries、Mavericks、Lottie、Coil、Koin、Timber、libsu、AndroidHiddenApiBypass、Gson、LeakCanary、AboutLibraries、Bouncy Castle、Sentry、SQLite など。

## 📃 ライセンス

[Apache 2.0](LICENSE)

### クレジット
- **AkayamiShurui42** 氏: 積極的なセキュリティ研究と安定性パッチに特に感謝（#239）。
- **AlexeiCrystal** 氏: MIUI クラッシュの特定と、レガシーアプリ向け Compat Hub 方式の提案に感謝（#241、#242）。
- **ddnexus** 氏と **kai-bash** 氏: Device Owner の出荷時リセット問題と Google バックアップ競合の指摘に感謝（#237）。
- **Kevinco1** 氏: Root 互換アプリ検出に関するフィードバックに感謝（#243）。
- **aragortsantiago6-beep** 氏、**Scoop2389** 氏（Pixel 9a）、**ConversionRituals** 氏（Xiaomi）: 実機 Android 16/17 テスト、クラッシュレポート、ログを提供し、SDK 37 非表示 API とローカルネットワーク保護互換の修正を推進（#317、#323）。
- **gmm96** 氏: 複数回の logcat デバッグで、最終的に Cached Apps Freezer の binder 配信バグを特定（#371）。
- **[odorizzioficial](https://github.com/odorizzioficial)** 氏: 完全なブラジルポルトガル語翻訳（#409）と Samsung「Sleeping apps」ウォッチドッグフリーズの詳細報告（#415）に感謝。

## 🤝 コントリビュートとサポート

- 問題と提案を報告: [GitHub Issues](https://github.com/qianyumeng0228/ShizukuX/issues)
- 開発への参加: [CONTRIBUTING.md](CONTRIBUTING.md) を参照
- セキュリティ脆弱性の報告: [SECURITY.md](SECURITY.md) を参照
- 完全な変更履歴: [CHANGELOG.md](CHANGELOG.md) · [CHANGES.md](CHANGES.md)
