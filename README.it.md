<div align="center">

# ShizukuX

[简体中文](./README.md) · [English](./README.en.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [Deutsch](./README.de.md) · [Français](./README.fr.md) · [Español](./README.es.md) · [Português](./README.pt-PT.md) · **Italiano** · [한국어](./README.ko.md) · [Português (Brasil)](./README.pt-BR.md)

**Gestione dei processi privilegiati Android per utenti avanzati**

ShizukuX è un fork comunitario migliorato di [Shizuku](https://github.com/RikkaApps/Shizuku), costruito su [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) e [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus). Eredita tutte le capacità del progetto originale aggiungendo localizzazione e ottimizzazioni dell'esperienza per un pubblico più ampio.

Shizuku consente alle app normali di chiamare direttamente le API di sistema tramite un processo privilegiato avviato con **ADB / Root**. ShizukuX mantiene una compatibilità al 100% aggiungendo capacità pratiche per utenti avanzati e sviluppatori.

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)
[![License](https://img.shields.io/github/license/qianyumeng0228/ShizukuX?style=for-the-badge&color=30bf60&labelColor=208040)](LICENSE)

> **Nota sull'eredità**: ShizukuX è la continuazione rinominata e localizzata in cinese di ShizukuPlus (qianyumeng0228); ShizukuPlus è a sua volta un fork di thejaustin/ShizukuPlus e thedjchi/Shizuku, risalendo in ultima analisi al progetto Shizuku di RikkaApps. ShizukuX è un fork comunitario mantenuto in modo indipendente e **non ha alcuna affiliazione, approvazione o partnership** con **RikkaApps/Shizuku, thedjchi/Shizuku, thejaustin/ShizukuPlus o i loro manutentori**. Tutti gli avvisi di copyright e attribuzione a monte sono integralmente preservati (vedi [LICENSE](LICENSE), [NOTICE](NOTICE), [CHANGES.md](CHANGES.md)).

</div>

## 📑 Indice

- [🚀 Avvio rapido](#quickstart)
- [⬇️ Download](#download)
- [✨ Funzionalità principali](#features)
- [🛡️ Stabilità e sicurezza](#security)
- [🚀 Estensioni Extra API](#extra-api)
- [🔌 Compatibilità con app di terze parti](#compat)
- [☑️ Requisiti di sistema](#requirements)
- [📱 Guida per sviluppatori e documentazione](#developer-guide)
- [🗂️ Struttura del repository](#repo-layout)
- [📦 Versioni recenti](#releases)
- [🙏 Ringraziamenti e licenza](#credits)

<a name="quickstart"></a>
## 🚀 Avvio rapido

1. Scarica l'ultimo APK **Standard** da [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) e installalo (nome pacchetto indipendente `xyz.shizuku.extra.api`, coesiste con lo Shizuku originale; la variante **Drop-In** si installa con il nome pacchetto originale, lo sostituisce e non può coesistere).
2. Apri l'app e avvia il servizio con **debug wireless / ADB da PC / Root** (attivazione ~9 secondi).
3. Concedi il permesso Shizuku alle app desiderate nell'app e inizia a usarla.

> Guide dettagliate, errori comuni e risoluzione dei problemi nella [documentazione in cinese](docs/zh-CN/index.md) (attualmente solo in cinese).

<a name="download"></a>
## ⬇️ Download

Ottieni l'ultima versione da [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases). Ogni versione fornisce i seguenti tre APK:

| Variante | Nome pacchetto | Utilizzo |
|---|---|---|
| **Standard** | `xyz.shizuku.extra.api` | Installazione pulita con nome pacchetto proprio; coesiste con altre build Shizuku |
| **Drop-In** | `moe.shizuku.privileged.api` | Sostituzione diretta dello Shizuku originale; compatibile con tutte le app dell'API Shizuku (non installare insieme allo Shizuku originale) |
| **Compat-Hub** | `moe.shizuku.privileged.api` | Piccola app complementare che consente alle app di terze parti che rilevano solo il nome pacchetto originale di vedere ShizukuX (inclusa nell'APK Standard; installazione/disinstallazione dall'app) |

- Le versioni stabili sono contrassegnate con la **serie `r`** (es. `v13.7.0.r2002`), le pre-release con la **serie `k`** (es. `v13.7.0.k2016`); entrambe includono gli stessi tre APK.
- Vedi le note di rilascio di ogni versione per i dettagli e [CHANGELOG.md](CHANGELOG.md) per la cronologia completa.

<a name="features"></a>
## ✨ Funzionalità principali di ShizukuX

*   **Provider di permessi universale**: un'interfaccia che unifica **Root**, **ADB Shell** e **Dhizuku (proprietario del dispositivo)** come fonti di permessi.
*   **Correzione tema OneUI 8+**: mantiene funzionanti i motori di tema come Hex Installer e Substratum su Android 16/17 e OneUI 8+.
*   **Modalità Dhizuku**: condivide il Binder Device Owner di sistema con qualsiasi app che possieda il permesso Shizuku — configurabile via ADB, senza Root; una pagina dedicata **Livello di permesso** gestisce i tre livelli Shell / Root / DO.
*   **Gestore permessi di precisione**: schermata dedicata per concedere/revocare i permessi Shizuku per app in massa, collegata alla scheda della schermata iniziale.
*   **Controllo del dispositivo**: 18 operazioni in cinque categorie (connettività, display, audio, alimentazione, impostazioni di sistema — modalità aereo, luminosità automatica, rotazione automatica, scala animazioni, riavvio/spegnimento, ecc.); le voci pericolose vengono automaticamente disabilitate con un avviso in modalità ADB.
*   **Backup e ripristino**: esportazione SAF verso directory pubbliche, backup/ripristino in batch (inclusi dati OBB), ripristino dei dati interni (`run-as` + tar), snapshot prima del ripristino e cronologia dei ripristini.
*   **Gesti personalizzabili**: scorrimento a sinistra/destra, pressione lunga, ecc., configurabili per app.
*   **Registro modifiche integrato**: vedi le novità dopo un aggiornamento senza uscire dall'app.
*   **Gestione in batch**: seleziona più app per concedere/revocare permessi o nasconderle con un tocco.
*   **Registro attività**: audit in tempo reale delle chiamate API e dei comandi del ponte `su`, con icone delle app e aggiornamento live.
*   **Centro compatibilità Root**: dashboard per le app Root legacy con controllo fine dei moduli (spoofing Magisk, concessione automatica, intercettazione file, ecc.).
*   **Automazione SU universale**: «impostazioni magiche» con un tocco che puntano tutte le app Root installate al ponte SU di ShizukuX.
*   **Diagnostica del servizio**: diagnostica e correggi i problemi di avvio (incluso Samsung Auto Blocker), esporta lo stack del crash con un tocco.
*   **Modalità stealth (nascondi tracce)**: intercetta le query `IPackageManager` per nascondere ShizukuX dall'elenco delle app di sistema.
*   **Controlli di personalizzazione**: tipografia in grassetto, intestazioni estese, override dei raggi degli angoli; animazioni e aptica seguono l'interruttore di sistema.
*   **Tema nero puro AMOLED**: i 9 token di superficie corretti per far sì che le schede non appaiano grigie in modalità nero puro.
*   **Rilevamento in tempo reale dell'ottimizzazione batteria**: ricontrolla al ritorno dalle impostazioni di sistema; le politiche di «risparmio energetico» MIUI / HyperOS sono unificate e i banner obsoleti scompaiono.
*   **Guida alle funzioni integrata**: ogni miglioramento ha un'icona informativa che ne spiega lo scopo in linguaggio semplice.
*   **Tile delle impostazioni rapide**: visualizza e commuta lo stato del servizio dal pannello notifiche (più tile di controllo: modalità aereo, animazioni, luminosità, rotazione).

<a name="security"></a>
## 🛡️ Stabilità e sicurezza

*   **Compatibilità client di terze parti (offset codici transazione AIDL)**: funziona con i client ufficiali v13.7+ e di tipo LSPatch che usano codici transazione AIDL con offset — le richieste di permesso non vengono più instradate male.
*   **Autenticazione handshake del proxy ADB**: il proxy ADB locale sulla porta 15555 ora verifica la firma RSA (cioè il possesso della chiave privata) e vincola l'UID del chiamante; le chiavi pubbliche contraffatte non possono più passare e il protocollo di rete resta invariato per i client di terze parti.
*   **Catena di recupero dai crash rafforzata**: l'avvio del debug wireless esegue prima una sonda dei prerequisiti, ripiega sull'avvio root senza porta TCP configurata e notifica chiaramente quando nessuno dei due funziona; il degrado dopo i crash non è permanente — un raffreddamento di 15 minuti riattiva il riavvio automatico.
*   **Correzione rilevamento isRooted**: compatibile con KernelSU / SukiSU in SELinux Enforcing — oltre allo `stat` dei file, consulta i registri di autorizzazione libsu (TTL 5 min), quindi i dispositivi rooted non vengono più segnalati come non rooted.
*   **Correzione visibilità pacchetti HyperOS / MIUI**: l'elenco delle app viene ottenuto tramite IPC del server (PackageManager shell-uid), correggendo gli elenchi vuoti causati dalle restrizioni di visibilità dei pacchetti di HyperOS.
*   **Indurimento del server**: protezione dall'iniezione shell (validazione regex del nome pacchetto a ogni ingresso, rifiuto di `/`, `..`, spazi), correzioni firma APK (Signature Scheme v2), firma in streaming per APK grandi contro gli OOM, e cache dell'insieme dei pacchetti congelati (`isAppFrozen` è O(1)).
*   **Attivazione più rapida**: il debug wireless si connette direttamente a `service.adb.tls.port` invece di attendere la scoperta mDNS — l'attivazione passa da ~2 minuti a ~9 secondi.
*   **L'arresto manuale resta fermo**: arrestare il servizio ferma anche il supervisore di avvio, non «risorge» più 5 secondi dopo aver toccato Stop.
*   **Rispettoso della batteria**: le animazioni seguono l'interruttore di movimento di sistema, le sonde delle metriche vengono limitate, le notifiche di download sono limitate per percentuale e lo stato inattivo non mantiene processi residenti.

<a name="extra-api"></a>
## 🚀 Estensioni Extra API

ShizukuX fornisce interfacce di sistema esclusive che lo Shizuku originale **non ha**, per automazione avanzata e strumenti:

*   **Ponte automazione AICore Extra**: automazione UI privilegiata senza Root per strumenti AI (dump della gerarchia, tap/swipe). ([commit di introduzione](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Gestore AVF (macchina virtuale)**: esegue VM Linux/Microdroid isolate con accelerazione GPU. ([commit di introduzione](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Proxy storage privilegiato**: accesso autenticato a percorsi ristretti (`/data/data/`, `/data/app/`) per backup e gestione file. ([commit di introduzione](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Spoofing del dispositivo** (*Spoof Device Identity* nelle Impostazioni): presenta informazioni di dispositivo diverse al sistema per aggirare le restrizioni specifiche del modello. ([commit di introduzione](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44))
*   **Ponte intelligente** (*AI Core Extra*): pianificazione NPU privilegiata e intelligenza del contesto dello schermo. ([commit di introduzione](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Window Manager Extra**: forza il ridimensionamento delle finestre libere, gestisce le barre di bolle fluttuanti e gli overlay elastici. ([commit di introduzione](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Ponte temi di sistema** (*Overlay Manager Extra*): gestione Overlay privilegiata senza Root per i temi (es. Hex Installer). ([commit di introduzione](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Governatore rete e DNS**: gestisce DNS privato e instradamento del firewall per il blocco pubblicità senza Root. ([commit di introduzione](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Controllo profondo dei processi** (*Activity Manager Extra*): consente ai gestori di processi di terminare le app in modo più aggressivo e impostare i bucket di standby. ([commit di introduzione](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Ponte di continuità**: trasferisce in modo sicuro stato e attività tra più dispositivi ShizukuX. ([commit di introduzione](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7))

## 🛠️ Backport e prestazioni

Senza toccare una singola riga di codice, ShizukuX rende le app Shizuku normali più veloci e compatibili:

*   **Interceptor shell trasparente**: instrada i comandi comuni `pm`, `am` e `settings` verso API native più veloci.
*   **Proxy ADB locale**: emula un server ADB sulla porta 15555 affinché le app legacy continuino a funzionare con Shizuku dopo la disattivazione del debug wireless.
*   **Ponte SU**: un'alternativa `su` basata su Shizuku per le app senza Root che supportano percorsi Root personalizzati.
*   **Strumento da riga di comando `extra`**: CLI privilegiata utilizzabile dentro `rish`.
*   **Database app dinamico**: sincronizza descrizioni e suggerimenti delle app da GitHub in tempo reale per mantenere l'interfaccia aggiornata.

## ⚙️ Controllo modulare

Tutto in ShizukuX è opzionale. Attiva o disattiva le funzioni nella categoria **Funzioni Extra** delle impostazioni:

*   Intercettazione shell trasparente
*   Extra API individuali (AVF, storage, intelligente, ecc.)
*   Visualizzazione delle schede iniziali
*   Registro attività

<a name="compat"></a>
## 🔌 Compatibilità con app di terze parti

ShizukuX si installa con il proprio nome pacchetto (`xyz.shizuku.extra.api`) e può coesistere con lo Shizuku originale. Poiché la maggior parte delle app Shizuku controlla solo il nome pacchetto `moe.shizuku.privileged.api`, ShizukuX include un **Compat Hub** leggero — una piccola app complementare che registra quel nome pacchetto e inoltra le richieste binder/permesso a ShizukuX.

**Se un'app di terze parti non rileva ShizukuX:**

1. Avvia prima il servizio ShizukuX (metodo ADB o Root).
2. Nella schermata iniziale, usa la scheda **Compat Hub** per installare l'app complementare (inclusa nell'APK; l'installazione dipende da un servizio in esecuzione, quindi avvia prima il servizio).
3. Riapri l'app di terze parti — ora dovrebbe rilevare Shizuku e ricevere il binder del servizio.

Puoi anche installare la **variante drop-in**, che si registra direttamente come `moe.shizuku.privileged.api` (non installarla insieme allo Shizuku originale).

<a name="requirements"></a>
## ☑️ Requisiti di sistema

**Minimo: Android 7+ · Supporto completo fino ad Android 17 (SDK 37)**

- **Modalità Root**: richiede un dispositivo rooted (compatibile con Magisk / KernelSU / SukiSU / APatch, ecc.)
- **Modalità debug wireless**: Android 11+ e tutti gli Android TV
- **Modalità ADB da PC**: tutti i dispositivi
- **Avvio automatico**: disponibile solo in modalità debug wireless o Root

Su **Android 16+**, ShizukuX richiede il nuovo permesso di protezione della rete locale per mantenere funzionanti scoperta e associazione del debug wireless; su **Android 17**, gestisce in modo trasparente la modifica dell'API nascosta `deviceId` affinché le app autorizzate restino visibili e le concessioni di permesso continuino a funzionare.

<a name="developer-guide"></a>
## 📱 Guida per sviluppatori e documentazione

La documentazione delle Extra API esclusive si trova in [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) (il sottomodulo `api` di questo repository vi punta). I manutentori di fork Shizuku che vogliono migrare a ShizukuX possono seguire [FORK_MIGRATION.md](FORK_MIGRATION.md).

La documentazione completa in cinese per gli utenti finali (connessione al servizio, knowledge base, permessi, modalità Dhizuku, ecc.) è in [docs/zh-CN/index.md](docs/zh-CN/index.md) (attualmente solo in cinese).

<a name="repo-layout"></a>
## 🗂️ Struttura del repository

| Directory | Descrizione |
|---|---|
| `manager/` | App principale: schede iniziali, impostazioni, gestore permessi, controllo del dispositivo, backup/ripristino, Watchdog, aggiornamenti |
| `server/` | Processo di servizio privilegiato: ShizukuService e tutte le implementazioni Extra API |
| `core/` | Libreria core condivisa (componenti UI, temi, risorse stringhe) |
| `api/` | Sottomodulo [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) |
| `compat/` | App complementare Compat Hub |
| `shell/` | Strumenti CLI privilegiati `rish` / `extra` |
| `starter/` | Avviatore del servizio (catena di avvio ADB / Root / supervisor) |
| `common/` / `app-process/` / `database/` | Codice comune, wrapper di processo autonomo, livello database |
| `docs/` | Documentazione (l'aiuto integrato e le note di aggiornamento vi puntano) |

<a name="releases"></a>
## 📦 Versioni recenti

| Versione | Tipo | Data | Punti salienti |
|---|---|---|---|
| **v13.7.0.k2016** | Pre-release | 2026-10-26 | L'arresto manuale resta fermo, avvio consapevole del root (fallback isRooted), attivazione più rapida (~2 min → 9 s), esportazione del tutorial del terminale e correzioni dei comandi |
| v13.7.0.k2015 | Pre-release | 2026-10-25 | 13 correzioni da un audit di 53 000 righe, auto-avvio / recupero dai crash rafforzati, impostazioni riorganizzate in Generale / Permesso / Servizio / Diagnostica |
| v13.7.0.k2014 | Pre-release | 2026-10-07 | Compatibilità offset codici transazione AIDL, suite completa controllo del dispositivo / backup, modalità stealth, correzione tema AMOLED, disinstallazione Compat Hub con un tocco |
| v13.7.0.r2002 | Stabile | 2026-09-22 | Linea stabile v13.7.0 |

Per il changelog completo vedi [CHANGELOG.md](CHANGELOG.md) e le [Release Notes](https://github.com/qianyumeng0228/ShizukuX/releases) di ogni versione.

<a name="credits"></a>
## 🙏 Ringraziamenti e licenza

ShizukuX è un fork comunitario migliorato — vedi la nota sull'eredità in cima a questo documento per la discendenza e l'avviso legale.

Grazie ai seguenti contributori e progetti a monte, il cui lavoro ha reso possibile ShizukuX:

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — il fondamento Shizuku e il suo elegante design API.
- **[thedjchi](https://github.com/thedjchi)** — fork intermedio con miglioramenti dell'esperienza e casa del lavoro di **compatibilità Android 17 (SDK 37)** da cui è stato portato il supporto A17 di ShizukuX.
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — fonte della compatibilità dell'API nascosta `deviceId` di Android 17 (`Android17Compat` / `InstalledPackagesCompat` livello reflection) e della gestione della protezione della rete locale.
- **[LandonMoran](https://github.com/LandonMoran)** — ha portato il supporto Android 17 di Nightzuku nel fork thedjchi e lo ha validato **end-to-end su un vero dispositivo Android 17** (associazione, avvio del servizio, elenco app autorizzate), fornendo la base di verifica su dispositivo reale per il port di ShizukuX.
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — la codebase di aShell You che ha ispirato le funzionalità di terminale e automazione shell.
- **[iamr0s](https://github.com/iamr0s)** — Dhizuku per la modalità unificata di permesso Device Owner e AndroidAppProcess per l'esecuzione di processi Java autonomi.
- **[pascua28](https://github.com/pascua28)** — integrazione nativa dei privilegi Samsung System UID 1000.
- **[kerneldroid](https://github.com/kerneldroid)** — il fork Nightzuku che ha ispirato la nostra resilienza API Android 16/17 (SDK 37) (gestione di `deviceId`) e la modernizzazione dell'interfaccia.
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — il concetto di privilegi UID 1000 a livello di sistema oltre i limiti normali.

### Progetti a monte

| Progetto | Autore | Licenza | Ruolo |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | Architettura fondante dei processi privilegiati |
| [Shizuku (fork)](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | Fork intermedio con miglioramenti dell'esperienza; ospita il lavoro di compatibilità Android 17 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Fonte della compatibilità `deviceId` Android 17 + protezione rete locale |
| [Shizuku (fork)](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | Schema di esecuzione di sistema Samsung UID 1000 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Resilienza API Android 16/17 e modernizzazione dell'interfaccia |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | Team ShizukuExt | Apache 2.0 | Concetto di privilegio UID di sistema |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Condivisione binder Device Owner (modalità Dhizuku) |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | Wrapper di processo Java ad alta privilegio autonomo |

### Librerie open source

Questo progetto utilizza le seguenti librerie open source chiave (elenco completo e testi delle licenze in [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) e [NOTICE](NOTICE)): AndroidX Jetpack, Material Components, Kotlin / Coroutines, RikkaX Libraries, Mavericks, Lottie, Coil, Koin, Timber, libsu, AndroidHiddenApiBypass, Gson, LeakCanary, AboutLibraries, Bouncy Castle, Sentry, SQLite e altre.

## 📃 Licenza

[Apache 2.0](LICENSE)

### Crediti
- Ringraziamento speciale ad **AkayamiShurui42** per la ricerca proattiva sulla sicurezza e le correzioni di stabilità (#239).
- Grazie ad **AlexeiCrystal** per aver individuato il crash MIUI e proposto lo schema Compat Hub per le app legacy (#241, #242).
- Grazie a **ddnexus** e **kai-bash** per aver segnalato la trappola del ripristino alle impostazioni di fabbrica del Device Owner e i conflitti con il backup di Google (#237).
- Grazie a **Kevinco1** per il feedback sul rilevamento delle app di compatibilità Root (#243).
- Grazie ad **aragortsantiago6-beep**, **Scoop2389** (Pixel 9a) e **ConversionRituals** (Xiaomi) per i test su dispositivo Android 16/17, i rapporti di crash e i log che hanno portato alle correzioni delle API nascoste SDK 37 e della protezione della rete locale (#317, #323).
- Grazie a **gmm96** per più sessioni di debug logcat che hanno infine isolato il bug di consegna del binder del Cached Apps Freezer (#371).
- Grazie a **[odorizzioficial](https://github.com/odorizzioficial)** per una traduzione completa in portoghese brasiliano (#409) e un rapporto dettagliato sui blocchi del watchdog Samsung «Sleeping apps» (#415).

## 🤝 Contributi e supporto

- Segnalare problemi e suggerimenti: [GitHub Issues](https://github.com/qianyumeng0228/ShizukuX/issues)
- Sviluppo: vedi [CONTRIBUTING.md](CONTRIBUTING.md)
- Vulnerabilità di sicurezza: vedi [SECURITY.md](SECURITY.md)
- Changelog completo: [CHANGELOG.md](CHANGELOG.md) · [CHANGES.md](CHANGES.md)
