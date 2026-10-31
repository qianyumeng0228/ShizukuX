<div align="center">

# ShizukuX

[简体中文](./README.md) · [English](./README.en.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · **Deutsch** · [Français](./README.fr.md) · [Español](./README.es.md) · [Português](./README.pt-PT.md) · [Italiano](./README.it.md) · [한국어](./README.ko.md) · [Português (Brasil)](./README.pt-BR.md)

**Android-Privilegien-Prozessverwaltung für fortgeschrittene Benutzer**

ShizukuX ist ein Community-verbesserter Fork von [Shizuku](https://github.com/RikkaApps/Shizuku), der auf [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) und [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus) aufbaut. Er erbt alle Fähigkeiten des ursprünglichen Projekts und fügt Lokalisierung und optimierte Erfahrung für ein breiteres Publikum hinzu.

Shizuku ermöglicht normalen Apps, über einen per **ADB / Root** gestarteten privilegierten Prozess direkt System-APIs aufzurufen. ShizukuX bleibt 100 % kompatibel und fügt gleichzeitig praktische Funktionen für Power-User und Entwickler hinzu.

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)
[![License](https://img.shields.io/github/license/qianyumeng0228/ShizukuX?style=for-the-badge&color=30bf60&labelColor=208040)](LICENSE)

> **Hinweis zur Herkunft**: ShizukuX ist die umbenannte, chinesisch lokalisierte Fortsetzung von ShizukuPlus (qianyumeng0228); ShizukuPlus selbst ist ein Fork von thejaustin/ShizukuPlus und thedjchi/Shizuku, der letztlich auf RikkaApps' Shizuku-Projekt zurückgeht. ShizukuX ist ein unabhängig gepflegter Community-Fork und hat **keine Verbindung, Empfehlung oder Partnerschaft** mit **RikkaApps/Shizuku, thedjchi/Shizuku, thejaustin/ShizukuPlus oder deren Maintainern**. Alle Urheberrechts- und Namenshinweise der Upstream-Projekte bleiben vollständig erhalten (siehe [LICENSE](LICENSE), [NOTICE](NOTICE), [CHANGES.md](CHANGES.md)).

</div>

## 📑 Inhaltsverzeichnis

- [🚀 Schnellstart](#quickstart)
- [⬇️ Download](#download)
- [✨ Kernfunktionen](#features)
- [🛡️ Stabilität & Sicherheit](#security)
- [🚀 Extra API-Erweiterungen](#extra-api)
- [🔌 Kompatibilität mit Drittanbieter-Apps](#compat)
- [☑️ Systemanforderungen](#requirements)
- [📱 Entwicklerhandbuch & Doku](#developer-guide)
- [🗂️ Repository-Struktur](#repo-layout)
- [📦 Aktuelle Versionen](#releases)
- [🙏 Danksagungen & Lizenz](#credits)

<a name="quickstart"></a>
## 🚀 Schnellstart

1. Laden Sie die neueste **Standard**-APK von [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) herunter und installieren Sie sie (eigener Paketname `xyz.shizuku.extra.api`, koexistiert mit dem Original-Shizuku; die **Drop-In**-Variante wird unter dem Original-Paketnamen installiert, ersetzt es und kann nicht koexistieren).
2. Öffnen Sie die App und starten Sie den Dienst mit **drahtlosem Debugging / PC-ADB / Root** (Aktivierung ca. 9 Sekunden).
3. Erteilen Sie in der App Shizuku-Berechtigungen für die gewünschten Apps und legen Sie los.

> Schritt-für-Schritt-Anleitungen, häufige Fehler und Fehlerbehebung finden Sie in der [chinesischen Dokumentation](docs/zh-CN/index.md) (derzeit nur auf Chinesisch).

<a name="download"></a>
## ⬇️ Download

Laden Sie die neueste Version von [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases). Jede Version enthält die folgenden drei APKs:

| Variante | Paketname | Verwendung |
|---|---|---|
| **Standard** | `xyz.shizuku.extra.api` | Frische Installation unter eigenem Paketnamen; koexistiert mit anderen Shizuku-Builds |
| **Drop-In** | `moe.shizuku.privileged.api` | Direkter Ersatz für das Original-Shizuku; kompatibel mit allen Shizuku-API-Apps (nicht zusammen mit dem Original-Shizuku installieren) |
| **Compat-Hub** | `moe.shizuku.privileged.api` | Kleine Begleit-App, damit Drittanbieter-Apps, die nur den Original-Paketnamen erkennen, ShizukuX sehen (in der Standard-APK enthalten; in der App installierbar/deinstallierbar) |

- Stabile Versionen tragen ein **`r`-Serie**-Label (z. B. `v13.7.0.r2002`), Vorabversionen ein **`k`-Serie**-Label (z. B. `v13.7.0.k2016`); beide enthalten dieselben drei APKs.
- Details finden Sie in den jeweiligen Versionshinweisen, die vollständige Historie in [CHANGELOG.md](CHANGELOG.md).

<a name="features"></a>
## ✨ ShizukuX-Kernfunktionen

*   **Universeller Berechtigungsanbieter**: eine Schnittstelle vereinheitlicht **Root**, **ADB Shell** und **Dhizuku (Gerätebesitzer)** als Berechtigungsquellen.
*   **OneUI 8+-Theme-Fix**: hält Theme-Engines wie Hex Installer und Substratum auf Android 16/17 und OneUI 8+ am Laufen.
*   **Dhizuku-Modus**: teilt den System-Device-Owner-Binder mit jeder App, die Shizuku-Berechtigung besitzt – per ADB konfigurierbar, ohne Root; eine eigene **Berechtigungsstufen**-Seite verwaltet Shell / Root / DO zentral.
*   **Feingranularer Berechtigungsmanager**: eigener Bildschirm zum Batch-Erteilen/Entziehen von Shizuku-Berechtigungen pro App, verknüpft mit der Startbildschirm-Karte.
*   **Gerätesteuerung**: 18 Operationen in fünf Kategorien (Konnektivität, Anzeige, Audio, Strom, Systemeinstellungen – Flugmodus, Auto-Helligkeit, Auto-Rotation, Animationsskalierung, Neustart/Herunterfahren usw.); gefährliche Aktionen werden im ADB-Modus automatisch deaktiviert und mit Hinweis versehen.
*   **Backup & Wiederherstellung**: SAF-Export in öffentliche Verzeichnisse, Batch-Backup/Wiederherstellung (inkl. OBB-Daten), Wiederherstellung interner Daten (`run-as` + tar), Snapshots vor der Wiederherstellung und Wiederherstellungsverlauf.
*   **Anpassbare Gesten**: Wischen nach links/rechts, langes Drücken usw., pro App konfigurierbar.
*   **In-App-Änderungsprotokoll**: neue Funktionen direkt nach dem Update ansehen, ohne die App zu verlassen.
*   **Batch-Verwaltung**: mehrere Apps auswählen und Berechtigungen erteilen/entziehen oder Apps mit einem Tipp ausblenden.
*   **Aktivitätsprotokoll**: Echtzeit-Prüfung von API-Aufrufen und `su`-Brückenbefehlen, mit App-Icons und Live-Aktualisierung.
*   **Root-Kompatibilitätszentrum**: Dashboard für ältere Root-Apps mit feingranularer Modulsteuerung (Magisk-Spoofing, Auto-Gewährung, Datei-Interception usw.).
*   **Universelle SU-Automatisierung**: „Magic Settings" mit einem Tipp, die alle installierten Root-Apps auf die ShizukuX-SU-Brücke umleitet.
*   **Dienst-Diagnose**: Startprobleme diagnostizieren und beheben (inkl. Samsung Auto Blocker), Absturz-Stacks mit einem Tipp exportieren.
*   **Stealth-Modus (Spuren verbergen)**: fängt `IPackageManager`-Abfragen ab und verbirgt ShizukuX in der System-App-Liste.
*   **Personalisierungssteuerung**: fette Typografie, erweiterte Header, Eckenradius-Overrides; Animationen und Haptik folgen dem System-Schalter.
*   **AMOLED-Reinschwarz-Theme**: alle 9 Surface-Tokens korrigiert, Karten wirken im Reinschwarz-Modus nicht mehr grau.
*   **Echtzeit-Erkennung der Akku-Optimierung**: prüft beim Zurückkehren aus den Systemeinstellungen erneut; MIUI / HyperOS-„Energiesparrichtlinien" werden vereinheitlicht, veraltete Banner verschwinden.
*   **Integrierte Funktionsführung**: jede Erweiterung hat ein Info-Icon, das ihren Zweck in einfacher Sprache erklärt.
*   **Schnelleinstellungs-Kacheln**: Dienststatus direkt aus der Benachrichtigungsleiste ansehen und umschalten (plus Gerätesteuerungs-Kacheln: Flugmodus, Animationen, Helligkeit, Rotation).

<a name="security"></a>
## 🛡️ Stabilität & Sicherheit

*   **Kompatibilität mit Drittanbieter-Clients (AIDL-Transaktionscode-Offset)**: funktioniert mit offiziellen v13.7+- und LSPatch-artigen Clients mit Offset-AIDL-Transaktionscodes – Berechtigungsanfragen werden nicht mehr fehlgeleitet.
*   **ADB-Proxy-Handshake-Authentifizierung**: der lokale ADB-Proxy auf Port 15555 verifiziert jetzt die RSA-Signatur (d. h. Besitz des privaten Schlüssels) und bindet die UID des Aufrufers; gefälschte öffentliche Schlüssel können nicht mehr durchkommen, das Drahtprotokoll bleibt für Drittanbieter-Clients unverändert.
*   **Gehärtete Crash-Recovery-Kette**: Start des drahtlosen Debuggings führt zuerst eine Voraussetzungsprüfung durch, fällt ohne konfigurierten TCP-Port auf Root-Start zurück und informiert klar, wenn beides nicht möglich ist; die Degradation bei Abstürzen ist nicht dauerhaft – nach 15 Minuten Kühlzeit wird der Auto-Neustart wieder aktiviert.
*   **isRooted-Erkennungsfix**: kompatibel mit KernelSU / SukiSU unter SELinux Enforcing – neben Datei-`stat` werden libsu-Autorisierungsaufzeichnungen (5-Min-TTL) konsultiert, sodass gerootete Geräte nicht mehr als ungerootet gemeldet werden.
*   **HyperOS / MIUI-Paketsichtbarkeitsfix**: die App-Liste wird über Server-IPC (shell-uid PackageManager) abgerufen, wodurch leere Listen durch HyperOS-Paketsichtbarkeitsbeschränkungen behoben werden.
*   **Server-Härtung**: Shell-Injektionsschutz (einheitliche Paketnamen-Regex-Validierung an jedem Einstieg, Ablehnung von `/`, `..`, Leerzeichen), Korrekturen der APK-Signatur (Signature Scheme v2), Streaming-Signatur für große APKs gegen OOM, und ein Cache der eingefrorenen Pakete (`isAppFrozen` ist O(1)).
*   **Schnellere Aktivierung**: drahtloses Debugging verbindet direkt mit `service.adb.tls.port` statt auf mDNS-Discovery zu warten – Aktivierung sinkt von ca. 2 Minuten auf ca. 9 Sekunden.
*   **Manuelles Stoppen bleibt gestoppt**: das Stoppen des Dienstes stoppt auch den Starter-Supervisor, sodass er nicht mehr „5 Sekunden nach Stopp wiederaufersteht".
*   **Akku-freundlich**: Animationen folgen dem System-Bewegungsschalter, Metrik-Abfragen werden gedrosselt, Download-Benachrichtigungen nach Prozentanteil begrenzt, Leerlauf hält keinen Prozess resident.

<a name="extra-api"></a>
## 🚀 Extra API-Erweiterungen

ShizukuX bietet exklusive System-Schnittstellen, die das Original-Shizuku **nicht** hat, für fortgeschrittene Automatisierung und Werkzeuge:

*   **AICore Extra-Automatisierungsbrücke**: Root-freie privilegierte UI-Automatisierung für KI-Tools (Hierarchie-Dump, Tippen/Wischen). ([Einführungs-Commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **AVF (Virtuelle Maschine) Manager**: führt isolierte Linux/Microdroid-VMs mit GPU-Beschleunigung aus. ([Einführungs-Commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Privilegierter Speicher-Proxy**: authentifizierter Zugriff auf eingeschränkte Pfade (`/data/data/`, `/data/app/`) für Backup und Dateiverwaltung. ([Einführungs-Commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Geräte-Spoofing** (*Spoof Device Identity* in den Einstellungen): zeigt dem System andere Geräteinformationen, um modellspezifische Einschränkungen zu umgehen. ([Einführungs-Commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44))
*   **Smart-Brücke** (*AI Core Extra*): privilegierte NPU-Planung und Bildschirm-Kontext-Intelligenz. ([Einführungs-Commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Window Manager Extra**: erzwingt freies Fenster-Resizing, verwaltet schwebende Bubble-Bars und elastische Overlays. ([Einführungs-Commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **System-Theme-Brücke** (*Overlay Manager Extra*): Root-freies privilegiertes Overlay-Management für Theming (z. B. Hex Installer). ([Einführungs-Commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Netzwerk- & DNS-Governor**: verwaltet privates DNS und Firewall-Routing für Root-freie Werbeblockierung. ([Einführungs-Commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Tiefe Prozesssteuerung** (*Activity Manager Extra*): Prozessmanager können Apps aggressiver beenden und Standby-Buckets setzen. ([Einführungs-Commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Kontinuitätsbrücke**: überträgt Zustand und Aufgaben sicher zwischen mehreren ShizukuX-Geräten. ([Einführungs-Commit](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7))

## 🛠️ Backports & Leistung

Ohne eine einzige Codezeile zu ändern, macht ShizukuX normale Shizuku-Apps schneller und kompatibler:

*   **Transparenter Shell-Interceptor**: leitet gängige `pm`-, `am`- und `settings`-Befehle an schnellere native APIs um.
*   **Lokaler ADB-Proxy**: emuliert einen ADB-Server auf Port 15555, sodass ältere Apps auch nach Deaktivierung des drahtlosen Debuggings mit Shizuku funktionieren.
*   **SU-Brücke**: eine Shizuku-basierte `su`-Alternative für Root-freie Apps, die benutzerdefinierte Root-Pfade unterstützen.
*   **`extra`-Befehlszeilenwerkzeug**: privilegierte CLI, innerhalb von `rish` nutzbar.
*   **Dynamische App-Datenbank**: synchronisiert App-Beschreibungen und Vorschläge in Echtzeit von GitHub, um die UI aktuell zu halten.

## ⚙️ Modulare Steuerung

Alles in ShizukuX ist optional. Schalten Sie Funktionen in der Kategorie **Extra-Funktionen** in den Einstellungen um:

*   Transparente Shell-Interception
*   Einzelne Extra APIs (AVF, Speicher, Smart usw.)
*   Startbildschirm-Kartenanzeige
*   Aktivitätsprotokoll

<a name="compat"></a>
## 🔌 Kompatibilität mit Drittanbieter-Apps

ShizukuX wird unter eigenem Paketnamen (`xyz.shizuku.extra.api`) installiert und kann mit dem Original-Shizuku koexistieren. Da die meisten Shizuku-Apps nur den Paketnamen `moe.shizuku.privileged.api` prüfen, enthält ShizukuX einen leichten **Compat Hub** – eine kleine Begleit-App, die diesen Paketnamen registriert und Binder-/Berechtigungsanfragen an ShizukuX weiterleitet.

**Wenn eine Drittanbieter-App ShizukuX nicht erkennt:**

1. Starten Sie zuerst den ShizukuX-Dienst (ADB- oder Root-Methode).
2. Installieren Sie auf dem Startbildschirm über die **Compat Hub**-Karte die Begleit-App (in der APK enthalten; die Installation hängt von einem laufenden Dienst ab, also starten Sie den Dienst zuerst).
3. Öffnen Sie die Drittanbieter-App erneut – sie sollte Shizuku jetzt erkennen und den Dienst-Binder erhalten.

Sie können auch die **Drop-In-Variante** installieren, die direkt als `moe.shizuku.privileged.api` registriert wird (nicht zusammen mit dem Original-Shizuku installieren).

<a name="requirements"></a>
## ☑️ Systemanforderungen

**Minimum: Android 7+ · Voll unterstützt bis Android 17 (SDK 37)**

- **Root-Modus**: erfordert ein gerootetes Gerät (kompatibel mit Magisk / KernelSU / SukiSU / APatch usw.)
- **Drahtloses Debugging**: Android 11+ und alle Android-TVs
- **PC-ADB-Modus**: alle Geräte
- **Autostart**: nur im drahtlosen Debugging- oder Root-Modus

Auf **Android 16+** fordert ShizukuX die neue Berechtigung zum Schutz des lokalen Netzwerks an, damit Discovery und Pairing des drahtlosen Debuggings funktionieren; auf **Android 17** behandelt es transparent die Änderung der versteckten API `deviceId`, sodass autorisierte Apps sichtbar bleiben und Berechtigungen weiter funktionieren.

<a name="developer-guide"></a>
## 📱 Entwicklerhandbuch & Doku

Dokumentation zu den exklusiven Extra APIs finden Sie in [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) (das `api`-Submodul dieses Repositories verweist darauf). Maintainer von Shizuku-Forks, die auf ShizukuX migrieren möchten, folgen [FORK_MIGRATION.md](FORK_MIGRATION.md).

Die vollständige chinesische Endbenutzer-Dokumentation (Dienstverbindung, Wissensdatenbank, Berechtigungen, Dhizuku-Modus usw.) finden Sie unter [docs/zh-CN/index.md](docs/zh-CN/index.md) (derzeit nur auf Chinesisch).

<a name="repo-layout"></a>
## 🗂️ Repository-Struktur

| Verzeichnis | Beschreibung |
|---|---|
| `manager/` | Haupt-App: Startkarten, Einstellungen, Berechtigungsmanager, Gerätesteuerung, Backup/Wiederherstellung, Watchdog, Updates |
| `server/` | Privilegierter Dienstprozess: ShizukuService und alle Extra-API-Implementierungen |
| `core/` | Gemeinsame Kernbibliothek (UI-Komponenten, Themes, String-Ressourcen) |
| `api/` | [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API)-Submodul |
| `compat/` | Compat-Hub-Begleit-App |
| `shell/` | `rish` / `extra` privilegierte Befehlszeilenwerkzeuge |
| `starter/` | Dienststarter (ADB / Root / Supervisor-Startkette) |
| `common/` / `app-process/` / `database/` | Gemeinsamer Code, eigenständiger Prozess-Wrapper, Datenbankschicht |
| `docs/` | Dokumentation (In-App-Hilfe und Versionshinweise verweisen hierher) |

<a name="releases"></a>
## 📦 Aktuelle Versionen

| Version | Typ | Datum | Highlights |
|---|---|---|---|
| **v13.7.0.k2016** | Vorabversion | 2026-10-26 | Manuelles Stoppen bleibt gestoppt, Root-bewusster Start (isRooted-Fallback), schnellere Aktivierung (~2 Min → 9 s), Terminal-Tutorial-Export und Befehlsfixes |
| v13.7.0.k2015 | Vorabversion | 2026-10-25 | 13 Fixes aus einem 53k-Zeilen-Audit, gehärteter Auto-Start/Crash-Recovery für drahtloses Debugging, Einstellungen in Allgemein/Berechtigung/Dienst/Diagnose neu organisiert |
| v13.7.0.k2014 | Vorabversion | 2026-10-07 | AIDL-Transaktionscode-Offset-Kompatibilität, vollständige Gerätesteuerung/Backup-Suite, Stealth-Modus, AMOLED-Theme-Fix, Compat-Hub-Deinstallation mit einem Tipp |
| v13.7.0.r2002 | Stabil | 2026-09-22 | Stabile v13.7.0-Linie |

Für das vollständige Änderungsprotokoll siehe [CHANGELOG.md](CHANGELOG.md) und die [Release Notes](https://github.com/qianyumeng0228/ShizukuX/releases) pro Version.

<a name="credits"></a>
## 🙏 Danksagungen & Lizenz

ShizukuX ist ein community-getriebener, erweiterter Fork – siehe den Herkunftshinweis oben für Abstammung und Haftungsausschluss.

Dank an die folgenden Upstream-Mitwirkenden und Projekte, deren Arbeit ShizukuX möglich gemacht hat:

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — das Shizuku-Fundament und sein elegantes API-Design.
- **[thedjchi](https://github.com/thedjchi)** — Zwischen-Fork mit Erfahrungsverbesserungen und Heimat der **Android 17 (SDK 37)-Kompatibilitätsarbeit**, aus der die A17-Unterstützung von ShizukuX portiert wurde.
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — Quelle der Android-17-Kompatibilität für die versteckte API `deviceId` (`Android17Compat` / `InstalledPackagesCompat`-Reflexionsebene) und der lokalen Netzwerk-Schutzbehandlung.
- **[LandonMoran](https://github.com/LandonMoran)** — portierte die Android-17-Unterstützung von Nightzuku in den thedjchi-Fork und validierte sie **End-to-End auf einem echten Android-17-Gerät** (Pairing, Dienststart, autorisierte App-Liste) – die Basis für ShizukuX' Portierung.
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — die aShell-You-Codebasis, die Terminal- und Shell-Automatisierungsfunktionen inspirierte.
- **[iamr0s](https://github.com/iamr0s)** — Dhizuku für den einheitlichen Device-Owner-Berechtigungsmodus und AndroidAppProcess für die eigenständige Java-Prozessausführung.
- **[pascua28](https://github.com/pascua28)** — native Samsung System-UID-1000-Privilegienintegration.
- **[kerneldroid](https://github.com/kerneldroid)** — der Nightzuku-Fork, der unsere Android-16/17-(SDK-37)-Resilienz (Umgang mit `deviceId`) und UI-Modernisierung inspirierte.
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — das Konzept von System-UID-1000-Privilegien jenseits normaler Grenzen.

### Upstream-Projekte

| Projekt | Autor | Lizenz | Rolle |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | Fundament der privilegierten Prozessarchitektur |
| [Shizuku (Fork)](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | Zwischen-Fork mit Erfahrungsverbesserungen; beherbergt die Android-17-Kompatibilitätsarbeit |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Quelle der Android-17-`deviceId`- und Netzwerkschutzkompatibilität |
| [Shizuku (Fork)](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | Samsung-UID-1000-Systemausführungsschema |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android-16/17-API-Resilienz und UI-Modernisierung |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | ShizukuExt-Team | Apache 2.0 | System-UID-Privilegienkonzept |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Device-Owner-Binder-Sharing (Dhizuku-Modus) |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | Eigenständiger Hochprivilegien-Java-Prozess-Wrapper |

### Open-Source-Bibliotheken

Dieses Projekt verwendet die folgenden wichtigen Open-Source-Bibliotheken (vollständige Liste und Lizenztexte in [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) und [NOTICE](NOTICE)): AndroidX Jetpack, Material Components, Kotlin / Coroutines, RikkaX Libraries, Mavericks, Lottie, Coil, Koin, Timber, libsu, AndroidHiddenApiBypass, Gson, LeakCanary, AboutLibraries, Bouncy Castle, Sentry, SQLite und mehr.

## 📃 Lizenz

[Apache 2.0](LICENSE)

### Danksagung
- Besonderer Dank an **AkayamiShurui42** für proaktive Sicherheitsforschung und Stabilitätspatches (#239).
- Dank an **AlexeiCrystal** für die Lokalisierung des MIUI-Absturzes und den Vorschlag des Compat-Hub-Schemas für Alt-Apps (#241, #242).
- Dank an **ddnexus** und **kai-bash** für den Hinweis auf die Werkseinstellungs-Falle des Device Owner und Google-Backup-Konflikte (#237).
- Dank an **Kevinco1** für Feedback zur Erkennung von Root-Kompatibilitäts-Apps (#243).
- Dank an **aragortsantiago6-beep**, **Scoop2389** (Pixel 9a) und **ConversionRituals** (Xiaomi) für On-Device-Tests, Absturzberichte und Logs, die die SDK-37-Hidden-API- und Netzwerkschutz-Fixes vorantrieben (#317, #323).
- Dank an **gmm96** für mehrere Runden Logcat-Debugging, die schließlich den Cached-Apps-Freezer-Binder-Bug isolierten (#371).
- Dank an **[odorizzioficial](https://github.com/odorizzioficial)** für eine vollständige brasilianisch-portugiesische Übersetzung (#409) und einen detaillierten Bericht über Samsung-„Sleeping-apps"-Watchdog-Einfrierungen (#415).

## 🤝 Mitwirken & Support

- Probleme und Vorschläge melden: [GitHub Issues](https://github.com/qianyumeng0228/ShizukuX/issues)
- Entwicklung: siehe [CONTRIBUTING.md](CONTRIBUTING.md)
- Sicherheitslücken: siehe [SECURITY.md](SECURITY.md)
- Vollständiges Änderungsprotokoll: [CHANGELOG.md](CHANGELOG.md) · [CHANGES.md](CHANGES.md)
