<div align="center">

# ShizukuX

[简体中文](./README.md) · [English](./README.en.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [Deutsch](./README.de.md) · **Français** · [Español](./README.es.md) · [Português](./README.pt-PT.md) · [Italiano](./README.it.md) · [한국어](./README.ko.md) · [Português (Brasil)](./README.pt-BR.md)

**Gestionnaire de processus privilégiés Android pour utilisateurs avancés**

ShizukuX est un fork communautaire amélioré de [Shizuku](https://github.com/RikkaApps/Shizuku), construit sur [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) et [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus). Il hérite de toutes les capacités du projet d'origine tout en ajoutant une localisation et des améliorations d'expérience pour un public plus large.

Shizuku permet aux applications ordinaires d'appeler directement des API système via un processus privilégié démarré par **ADB / Root**. ShizukuX reste 100 % compatible tout en ajoutant des capacités pratiques pour les utilisateurs avancés et les développeurs.

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)
[![License](https://img.shields.io/github/license/qianyumeng0228/ShizukuX?style=for-the-badge&color=30bf60&labelColor=208040)](LICENSE)

> **Note d'héritage** : ShizukuX est la continuation renommée et localisée en chinois de ShizukuPlus (qianyumeng0228) ; ShizukuPlus est lui-même un fork de thejaustin/ShizukuPlus et thedjchi/Shizuku, remontant finalement au projet Shizuku de RikkaApps. ShizukuX est un fork communautaire maintenu indépendamment et n'a **aucune affiliation, recommandation ou partenariat** avec **RikkaApps/Shizuku, thedjchi/Shizuku, thejaustin/ShizukuPlus ou leurs mainteneurs**. Tous les avis de droit d'auteur et d'attribution en amont sont entièrement conservés (voir [LICENSE](LICENSE), [NOTICE](NOTICE), [CHANGES.md](CHANGES.md)).

</div>

## 📑 Table des matières

- [🚀 Démarrage rapide](#quickstart)
- [⬇️ Téléchargement](#download)
- [✨ Fonctionnalités principales](#features)
- [🛡️ Stabilité et sécurité](#security)
- [🚀 Extensions Extra API](#extra-api)
- [🔌 Compatibilité avec les applications tierces](#compat)
- [☑️ Configuration requise](#requirements)
- [📱 Guide développeur et documentation](#developer-guide)
- [🗂️ Structure du dépôt](#repo-layout)
- [📦 Versions récentes](#releases)
- [🙏 Remerciements et licence](#credits)

<a name="quickstart"></a>
## 🚀 Démarrage rapide

1. Téléchargez la dernière APK **Standard** depuis [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) et installez-la (nom de paquet indépendant `xyz.shizuku.extra.api`, coexiste avec le Shizuku d'origine ; la variante **Drop-In** s'installe sous le nom de paquet d'origine, le remplace et ne peut pas coexister).
2. Ouvrez l'application et démarrez le service avec **débogage sans fil / ADB PC / Root** (activation ~9 secondes).
3. Accordez la permission Shizuku aux applications souhaitées dans l'application, puis commencez.

> Guides détaillés, erreurs courantes et dépannage dans la [documentation chinoise](docs/zh-CN/index.md) (actuellement en chinois uniquement).

<a name="download"></a>
## ⬇️ Téléchargement

Récupérez la dernière version sur [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases). Chaque version fournit les trois APK suivantes :

| Variante | Nom de paquet | Utilisation |
|---|---|---|
| **Standard** | `xyz.shizuku.extra.api` | Installation neuve sous son propre nom de paquet ; coexiste avec d'autres builds Shizuku |
| **Drop-In** | `moe.shizuku.privileged.api` | Remplacement direct du Shizuku d'origine ; compatible avec toutes les applications Shizuku API (ne pas installer en même temps que le Shizuku d'origine) |
| **Compat-Hub** | `moe.shizuku.privileged.api` | Petite application compagnon qui permet aux applications tierces ne détectant que le nom de paquet d'origine de voir ShizukuX (incluse dans l'APK Standard ; installation/désinstallation depuis l'application) |

- Les versions stables sont marquées avec la **série `r`** (ex. `v13.7.0.r2002`), les pré-versions avec la **série `k`** (ex. `v13.7.0.k2016`) ; les deux fournissent les mêmes trois APK.
- Voir les notes de version de chaque version pour les détails et [CHANGELOG.md](CHANGELOG.md) pour l'historique complet.

<a name="features"></a>
## ✨ Fonctionnalités principales de ShizukuX

*   **Fournisseur de permissions universel** : une interface unifiant **Root**, **ADB Shell** et **Dhizuku (propriétaire de l'appareil)** comme sources de permissions.
*   **Correctif de thème OneUI 8+** : maintient les moteurs de thème comme Hex Installer et Substratum fonctionnels sur Android 16/17 et OneUI 8+.
*   **Mode Dhizuku** : partage le Binder Device Owner du système avec toute application détenant la permission Shizuku — configurable via ADB, sans Root ; une page **Niveau de permission** dédiée gère les trois niveaux Shell / Root / DO.
*   **Gestionnaire de permissions fin** : écran dédié pour accorder/révoquer les permissions Shizuku par application en masse, lié à la carte d'accueil.
*   **Contrôle de l'appareil** : 18 opérations sur cinq catégories (connectivité, affichage, audio, alimentation, paramètres système — mode avion, luminosité automatique, rotation automatique, échelle d'animation, redémarrage/extinction, etc.) ; les éléments dangereux sont automatiquement désactivés avec une indication en mode ADB.
*   **Sauvegarde et restauration** : export SAF vers les répertoires publics, sauvegarde/restauration par lots (y compris les données OBB), restauration des données internes (`run-as` + tar), instantanés avant restauration et historique de restauration.
*   **Gestes personnalisables** : glisser à gauche/droite, appui long, etc., configurables par application.
*   **Journal des modifications intégré** : voir les nouveautés après une mise à jour sans quitter l'application.
*   **Gestion par lots** : sélectionner plusieurs applications pour accorder/révoquer les permissions ou masquer en un seul geste.
*   **Journal d'activité** : audit en temps réel des appels API et des commandes du pont `su`, avec icônes d'application et actualisation en direct.
*   **Centre de compatibilité Root** : tableau de bord pour les applications Root héritées avec contrôle fin des modules (usurpation Magisk, octroi automatique, interception de fichiers, etc.).
*   **Automatisation SU universelle** : « réglages magiques » en un geste qui pointent toutes les applications Root installées vers le pont SU ShizukuX.
*   **Diagnostic du service** : diagnostiquer et corriger les problèmes de démarrage (y compris Samsung Auto Blocker), export de la pile d'incident en un geste.
*   **Mode furtif (masquer les traces)** : intercepte les requêtes `IPackageManager` pour masquer ShizukuX de la liste des applications système.
*   **Contrôles de personnalisation** : typographie en gras, en-têtes étendus, overrides de rayons d'angle ; animations et retour haptique suivant l'interrupteur système.
*   **Thème noir pur AMOLED** : les 9 tokens de surface corrigés pour que les cartes ne paraissent plus grises en mode noir pur.
*   **Détection en temps réel de l'optimisation de la batterie** : re-vérification au retour des paramètres système ; les politiques « d'économie d'énergie » MIUI / HyperOS sont unifiées, les bannières obsolètes disparaissent.
*   **Guide de fonctionnalités intégré** : chaque amélioration comporte une icône d'information expliquant son but en langage simple.
*   **Tuiles de réglages rapides** : voir et basculer l'état du service depuis le panneau de notification (plus des tuiles de contrôle : mode avion, animations, luminosité, rotation).

<a name="security"></a>
## 🛡️ Stabilité et sécurité

*   **Compatibilité clients tiers (décalage des codes de transaction AIDL)** : fonctionne avec les clients officiels v13.7+ et de type LSPatch utilisant des codes de transaction AIDL décalés — les demandes de permission ne sont plus mal routées.
*   **Authentification de la poignée de main du proxy ADB** : le proxy ADB local sur le port 15555 vérifie désormais la signature RSA (c'est-à-dire la possession de la clé privée) et lie l'UID de l'appelant ; les clés publiques forgées ne peuvent plus passer, le protocole filaire reste inchangé pour les clients tiers.
*   **Chaîne de récupération de crash renforcée** : le démarrage en débogage sans fil exécute d'abord une sonde de préconditions, retombe sur le démarrage root sans port TCP configuré, et notifie clairement quand aucun des deux n'est possible ; la dégradation après crash n'est pas permanente — un refroidissement de 15 minutes réactive le redémarrage automatique.
*   **Correctif de détection isRooted** : compatible KernelSU / SukiSU sous SELinux Enforcing — en plus du `stat` de fichier, il consulte les enregistrements d'autorisation libsu (TTL 5 min), les appareils rootés ne sont plus signalés comme non rootés.
*   **Correctif de visibilité des paquets HyperOS / MIUI** : la liste des applications est récupérée via IPC serveur (PackageManager shell-uid), corrigeant les listes vides causées par les restrictions de visibilité HyperOS.
*   **Renforcement du serveur** : protection contre l'injection shell (validation regex du nom de paquet à chaque entrée, rejet de `/`, `..`, espaces), correctifs de signature APK (Signature Scheme v2), signature en streaming pour les grandes APK contre les OOM, et cache de l'ensemble des paquets gelés (`isAppFrozen` est O(1)).
*   **Activation plus rapide** : le débogage sans fil se connecte directement à `service.adb.tls.port` au lieu d'attendre la découverte mDNS — l'activation passe de ~2 minutes à ~9 secondes.
*   **L'arrêt manuel reste arrêté** : arrêter le service arrête aussi le superviseur starter, il ne « ressuscite » plus 5 secondes après avoir appuyé sur Arrêter.
*   **Respectueux de la batterie** : animations suivant l'interrupteur système, sondes de métriques limitées, notifications de téléchargement limitées par pourcentage, et aucun processus résident au repos.

<a name="extra-api"></a>
## 🚀 Extensions Extra API

ShizukuX fournit des interfaces système exclusives que le Shizuku d'origine **n'a pas**, pour l'automatisation avancée et l'outillage :

*   **Pont d'automatisation AICore Extra** : automatisation UI privilégiée sans Root pour les outils IA (dump de hiérarchie, tap/swipe). ([commit d'introduction](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Gestionnaire AVF (machine virtuelle)** : exécute des VM Linux/Microdroid isolées avec accélération GPU. ([commit d'introduction](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Proxy de stockage privilégié** : accès authentifié aux chemins restreints (`/data/data/`, `/data/app/`) pour la sauvegarde et la gestion de fichiers. ([commit d'introduction](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Usurpation d'appareil** (*Spoof Device Identity* dans les paramètres) : présente différentes informations d'appareil au système pour contourner les restrictions spécifiques au modèle. ([commit d'introduction](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44))
*   **Pont intelligent** (*AI Core Extra*) : ordonnancement NPU privilégié et intelligence contextuelle d'écran. ([commit d'introduction](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Window Manager Extra** : forcer le redimensionnement en fenêtre libre, gérer les barres de bulles flottantes et les overlays élastiques. ([commit d'introduction](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Pont de thème système** (*Overlay Manager Extra*) : gestion Overlay privilégiée sans Root pour le theming (ex. Hex Installer). ([commit d'introduction](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Gouverneur réseau et DNS** : gérer le DNS privé et le routage du pare-feu pour le blocage de publicités sans Root. ([commit d'introduction](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Contrôle de processus profond** (*Activity Manager Extra*) : laisse les gestionnaires de processus tuer les applications plus agressivement et définir des buckets d'attente. ([commit d'introduction](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Pont de continuité** : transfère en toute sécurité l'état et les tâches entre plusieurs appareils ShizukuX. ([commit d'introduction](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7))

## 🛠️ Rétroportages et performances

Sans toucher une seule ligne de code, ShizukuX rend les applications Shizuku ordinaires plus rapides et plus compatibles :

*   **Intercepteur shell transparent** : route les commandes `pm`, `am` et `settings` courantes vers des API natives plus rapides.
*   **Proxy ADB local** : émule un serveur ADB sur le port 15555 pour que les applications héritées continuent de fonctionner avec Shizuku après désactivation du débogage sans fil.
*   **Pont SU** : une alternative `su` basée sur Shizuku pour les applications sans Root qui prennent en charge les chemins Root personnalisés.
*   **Outil en ligne de commande `extra`** : CLI privilégiée utilisable dans `rish`.
*   **Base de données d'applications dynamique** : synchronise en temps réel les descriptions et suggestions d'applications depuis GitHub pour garder l'interface à jour.

## ⚙️ Contrôle modulaire

Tout dans ShizukuX est optionnel. Activez/désactivez les fonctions dans la catégorie **Extra** des paramètres :

*   Interception shell transparente
*   Extra API individuelles (AVF, stockage, intelligent, etc.)
*   Affichage des cartes d'accueil
*   Journal d'activité

<a name="compat"></a>
## 🔌 Compatibilité avec les applications tierces

ShizukuX s'installe sous son propre nom de paquet (`xyz.shizuku.extra.api`) et peut coexister avec le Shizuku d'origine. Comme la plupart des applications Shizuku ne vérifient que le nom de paquet `moe.shizuku.privileged.api`, ShizukuX inclut un **Compat Hub** léger — une petite application compagnon qui enregistre ce nom de paquet et transfère les requêtes binder/permission à ShizukuX.

**Si une application tierce ne détecte pas ShizukuX :**

1. Démarrez d'abord le service ShizukuX (méthode ADB ou Root).
2. Sur l'écran d'accueil, utilisez la carte **Compat Hub** pour installer l'application compagnon (incluse dans l'APK ; l'installation dépend d'un service en cours d'exécution, démarrez donc le service d'abord).
3. Rouvrez l'application tierce — elle devrait maintenant détecter Shizuku et recevoir le binder de service.

Vous pouvez aussi installer la **variante drop-in**, qui s'enregistre directement sous `moe.shizuku.privileged.api` (ne pas l'installer en même temps que le Shizuku d'origine).

<a name="requirements"></a>
## ☑️ Configuration requise

**Minimum : Android 7+ · Prise en charge complète jusqu'à Android 17 (SDK 37)**

- **Mode Root** : nécessite un appareil rooté (compatible Magisk / KernelSU / SukiSU / APatch, etc.)
- **Mode débogage sans fil** : Android 11+ et tous les Android TV
- **Mode ADB PC** : tous les appareils
- **Démarrage au boot** : disponible uniquement en mode débogage sans fil ou Root

Sur **Android 16+**, ShizukuX demande la nouvelle permission de protection du réseau local pour que la découverte et l'appairage du débogage sans fil fonctionnent ; sur **Android 17**, il gère de manière transparente le changement d'API cachée `deviceId` pour que les applications autorisées restent visibles et que les octrois de permission continuent de fonctionner.

<a name="developer-guide"></a>
## 📱 Guide développeur et documentation

La documentation des Extra API exclusives se trouve dans [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) (le sous-module `api` de ce dépôt y pointe). Les mainteneurs de forks Shizuku souhaitant migrer vers ShizukuX peuvent suivre [FORK_MIGRATION.md](FORK_MIGRATION.md).

La documentation complète en chinois pour les utilisateurs (connexion au service, base de connaissances, permissions, mode Dhizuku, etc.) se trouve dans [docs/zh-CN/index.md](docs/zh-CN/index.md) (actuellement en chinois uniquement).

<a name="repo-layout"></a>
## 🗂️ Structure du dépôt

| Répertoire | Description |
|---|---|
| `manager/` | Application principale : cartes d'accueil, paramètres, gestionnaire de permissions, contrôle de l'appareil, sauvegarde/restauration, Watchdog, mises à jour |
| `server/` | Processus de service privilégié : ShizukuService et toutes les implémentations Extra API |
| `core/` | Bibliothèque centrale partagée (composants UI, thèmes, ressources de chaînes) |
| `api/` | Sous-module [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) |
| `compat/` | Application compagnon Compat Hub |
| `shell/` | Outils en ligne de commande privilégiés `rish` / `extra` |
| `starter/` | Lanceur de service (chaîne de démarrage ADB / Root / supervisor) |
| `common/` / `app-process/` / `database/` | Code commun, wrapper de processus autonome, couche base de données |
| `docs/` | Documentation (l'aide intégrée et les notes de mise à jour y renvoient) |

<a name="releases"></a>
## 📦 Versions récentes

| Version | Type | Date | Points clés |
|---|---|---|---|
| **v13.7.0.k2016** | Pré-version | 2026-10-26 | L'arrêt manuel reste arrêté, démarrage conscient du root (fallback isRooted), activation plus rapide (~2 min → 9 s), export du tutoriel terminal et corrections de commandes |
| v13.7.0.k2015 | Pré-version | 2026-10-25 | 13 correctifs issus d'un audit de 53 000 lignes, auto-démarrage / récupération de crash renforcés, paramètres réorganisés en Général / Permission / Service / Diagnostic |
| v13.7.0.k2014 | Pré-version | 2026-10-07 | Compatibilité du décalage des codes de transaction AIDL, suite complète contrôle d'appareil / sauvegarde, mode furtif, correctif de thème AMOLED, désinstallation Compat Hub en un geste |
| v13.7.0.r2002 | Stable | 2026-09-22 | Ligne stable v13.7.0 |

Pour le changelog complet voir [CHANGELOG.md](CHANGELOG.md) et les [Release Notes](https://github.com/qianyumeng0228/ShizukuX/releases) de chaque version.

<a name="credits"></a>
## 🙏 Remerciements et licence

ShizukuX est un fork communautaire amélioré — voir la note d'héritage en haut de ce document pour la lignée et l'avertissement.

Merci aux contributeurs et projets en amont suivants, dont le travail a rendu ShizukuX possible :

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — le socle Shizuku et sa conception d'API élégante.
- **[thedjchi](https://github.com/thedjchi)** — fork intermédiaire avec améliorations d'expérience, et maison du travail de **compatibilité Android 17 (SDK 37)** dont le support A17 de ShizukuX a été porté.
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — source de la compatibilité de l'API cachée `deviceId` Android 17 (`Android17Compat` / `InstalledPackagesCompat` couche réflexion) et de la gestion de la protection du réseau local.
- **[LandonMoran](https://github.com/LandonMoran)** — a porté le support Android 17 de Nightzuku dans le fork thedjchi et l'a validé **de bout en bout sur un vrai appareil Android 17** (appairage, démarrage du service, liste des applications autorisées), fournissant la base de vérification sur appareil réel pour le port de ShizukuX.
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — la base de code aShell You qui a inspiré les fonctionnalités de terminal et d'automatisation shell.
- **[iamr0s](https://github.com/iamr0s)** — Dhizuku pour le mode unifié de permission Device Owner, et AndroidAppProcess pour l'exécution de processus Java autonomes.
- **[pascua28](https://github.com/pascua28)** — intégration native des privilèges Samsung System UID 1000.
- **[kerneldroid](https://github.com/kerneldroid)** — le fork Nightzuku qui a inspiré notre résilience API Android 16/17 (SDK 37) (gestion de `deviceId`) et la modernisation de l'interface.
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — le concept de privilèges UID 1000 au niveau système au-delà des limites normales.

### Projets en amont

| Projet | Auteur | Licence | Rôle |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | Architecture de processus privilégié fondatrice |
| [Shizuku (fork)](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | Fork intermédiaire avec améliorations d'expérience ; héberge le travail de compatibilité Android 17 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Source de compatibilité `deviceId` Android 17 + protection du réseau local |
| [Shizuku (fork)](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | Schéma d'exécution système Samsung UID 1000 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Résilience API Android 16/17 et modernisation de l'interface |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | Équipe ShizukuExt | Apache 2.0 | Concept de privilège UID système |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Partage du binder Device Owner (mode Dhizuku) |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | Wrapper de processus Java hautement privilégié autonome |

### Bibliothèques open source

Ce projet utilise les bibliothèques open source clés suivantes (liste complète et textes de licence dans [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) et [NOTICE](NOTICE)) : AndroidX Jetpack, Material Components, Kotlin / Coroutines, RikkaX Libraries, Mavericks, Lottie, Coil, Koin, Timber, libsu, AndroidHiddenApiBypass, Gson, LeakCanary, AboutLibraries, Bouncy Castle, Sentry, SQLite, etc.

## 📃 Licence

[Apache 2.0](LICENSE)

### Crédits
- Merci tout particulier à **AkayamiShurui42** pour la recherche de sécurité proactive et les correctifs de stabilité (#239).
- Merci à **AlexeiCrystal** pour avoir localisé le crash MIUI et proposé le schéma Compat Hub pour les applications héritées (#241, #242).
- Merci à **ddnexus** et **kai-bash** pour avoir signalé le piège de réinitialisation d'usine du Device Owner et les conflits de sauvegarde Google (#237).
- Merci à **Kevinco1** pour les retours sur la détection des applications de compatibilité Root (#243).
- Merci à **aragortsantiago6-beep**, **Scoop2389** (Pixel 9a) et **ConversionRituals** (Xiaomi) pour les tests sur appareil Android 16/17, les rapports de crash et les journaux qui ont conduit aux correctifs API cachées SDK 37 et de protection du réseau local (#317, #323).
- Merci à **gmm96** pour plusieurs sessions de débogage logcat qui ont finalement isolé le bug de livraison binder du Cached Apps Freezer (#371).
- Merci à **[odorizzioficial](https://github.com/odorizzioficial)** pour une traduction complète en portugais brésilien (#409) et un rapport détaillé sur les gels du watchdog Samsung « Sleeping apps » (#415).

## 🤝 Contribution et support

- Signaler des problèmes et suggestions : [GitHub Issues](https://github.com/qianyumeng0228/ShizukuX/issues)
- Développement : voir [CONTRIBUTING.md](CONTRIBUTING.md)
- Vulnérabilités de sécurité : voir [SECURITY.md](SECURITY.md)
- Changelog complet : [CHANGELOG.md](CHANGELOG.md) · [CHANGES.md](CHANGES.md)
