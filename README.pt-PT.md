<div align="center">

# ShizukuX

[简体中文](./README.md) · [English](./README.en.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [Deutsch](./README.de.md) · [Français](./README.fr.md) · [Español](./README.es.md) · **Português** · [Italiano](./README.it.md) · [한국어](./README.ko.md) · [Português (Brasil)](./README.pt-BR.md)

**Gestor de processos privilegiados Android para utilizadores avançados**

ShizukuX é um fork comunitário melhorado de [Shizuku](https://github.com/RikkaApps/Shizuku), construído sobre [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) e [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus). Herda todas as capacidades do projeto original enquanto acrescenta localização e melhorias de experiência para um público mais alargado.

O Shizuku permite que aplicações normais chamem diretamente APIs de sistema através de um processo privilegiado iniciado por **ADB / Root**. O ShizukuX mantém 100 % de compatibilidade enquanto adiciona capacidades práticas para utilizadores avançados e programadores.

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)
[![License](https://img.shields.io/github/license/qianyumeng0228/ShizukuX?style=for-the-badge&color=30bf60&labelColor=208040)](LICENSE)

> **Nota de herança**: O ShizukuX é a continuação renomeada e localizada em chinês do ShizukuPlus (qianyumeng0228); o ShizukuPlus é por sua vez um fork do thejaustin/ShizukuPlus e do thedjchi/Shizuku, remontando em última instância ao projeto Shizuku da RikkaApps. O ShizukuX é um fork comunitário mantido de forma independente e **não tem qualquer afiliação, endosso ou parceria** com **RikkaApps/Shizuku, thedjchi/Shizuku, thejaustin/ShizukuPlus ou os seus mantenedores**. Todos os avisos de direitos de autor e atribuição a montante são integralmente preservados (ver [LICENSE](LICENSE), [NOTICE](NOTICE), [CHANGES.md](CHANGES.md)).

</div>

## 📑 Índice

- [🚀 Início rápido](#quickstart)
- [⬇️ Transferência](#download)
- [✨ Funcionalidades principais](#features)
- [🛡️ Estabilidade e segurança](#security)
- [🚀 Extensões Extra API](#extra-api)
- [🔌 Compatibilidade com aplicações de terceiros](#compat)
- [☑️ Requisitos do sistema](#requirements)
- [📱 Guia para programadores e documentação](#developer-guide)
- [🗂️ Estrutura do repositório](#repo-layout)
- [📦 Versões recentes](#releases)
- [🙏 Agradecimentos e licença](#credits)

<a name="quickstart"></a>
## 🚀 Início rápido

1. Transfira o último APK **Standard** de [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) e instale-o (nome de pacote independente `xyz.shizuku.extra.api`, coexiste com o Shizuku original; a variante **Drop-In** instala-se com o nome de pacote original, substitui-o e não pode coexistir).
2. Abra a aplicação e inicie o serviço com **depuração sem fios / ADB PC / Root** (ativação ~9 segundos).
3. Conceda permissão Shizuku às aplicações pretendidas dentro da aplicação e comece a usar.

> Guias detalhados, erros comuns e resolução de problemas na [documentação em chinês](docs/zh-CN/index.md) (atualmente apenas em chinês).

<a name="download"></a>
## ⬇️ Transferência

Obtenha a versão mais recente em [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases). Cada versão fornece os seguintes três APK:

| Variante | Nome de pacote | Utilização |
|---|---|---|
| **Standard** | `xyz.shizuku.extra.api` | Instalação limpa com o seu próprio nome de pacote; coexiste com outras compilações Shizuku |
| **Drop-In** | `moe.shizuku.privileged.api` | Substituição direta do Shizuku original; compatível com todas as aplicações da API Shizuku (não instalar em simultâneo com o Shizuku original) |
| **Compat-Hub** | `moe.shizuku.privileged.api` | Pequena aplicação complementar que permite às aplicações de terceiros que apenas detetam o nome de pacote original ver o ShizukuX (incluída no APK Standard; instalação/remoção a partir da aplicação) |

- As versões estáveis são identificadas com a **série `r`** (ex. `v13.7.0.r2002`), as pré-versões com a **série `k`** (ex. `v13.7.0.k2016`); ambas incluem os mesmos três APK.
- Consulte as notas de cada versão para detalhes e [CHANGELOG.md](CHANGELOG.md) para o histórico completo.

<a name="features"></a>
## ✨ Funcionalidades principais do ShizukuX

*   **Fornecedor de permissões universal**: uma interface que unifica **Root**, **ADB Shell** e **Dhizuku (proprietário do dispositivo)** como fontes de permissões.
*   **Correção de tema OneUI 8+**: mantém os motores de temas como Hex Installer e Substratum a funcionar em Android 16/17 e OneUI 8+.
*   **Modo Dhizuku**: partilha o Binder de Device Owner do sistema com qualquer aplicação que detenha permissão Shizuku — configurável via ADB, sem Root; uma página dedicada de **Nível de permissão** gere os três níveis Shell / Root / DO.
*   **Gestor de permissões fino**: ecrã dedicado para conceder/revogar permissões Shizuku por aplicação em massa, ligado ao cartão do ecrã inicial.
*   **Controlo do dispositivo**: 18 operações em cinco categorias (conectividade, ecrã, áudio, energia, definições do sistema — modo de avião, brilho automático, rotação automática, escala de animação, reinício/desligar, etc.); os itens perigosos são automaticamente desativados com uma indicação em modo ADB.
*   **Cópia de segurança e restauração**: exportação SAF para diretórios públicos, cópia/restauração em lote (incluindo dados OBB), restauração de dados internos (`run-as` + tar), instantâneos pré-restauração e histórico de restaurações.
*   **Gestos personalizáveis**: deslizar para a esquerda/direita, toque prolongado, etc., configuráveis por aplicação.
*   **Registo de alterações integrado**: ver as novidades após uma atualização sem sair da aplicação.
*   **Gestão em lote**: selecione várias aplicações para conceder/revogar permissões ou ocultá-las num toque.
*   **Registo de atividade**: auditoria em tempo real de chamadas API e comandos da ponte `su`, com ícones de aplicação e atualização em direto.
*   **Centro de compatibilidade Root**: painel para aplicações Root antigas com controlo fino de módulos (falsificação Magisk, concessão automática, interceção de ficheiros, etc.).
*   **Automação SU universal**: «definições mágicas» com um toque que apontam todas as aplicações Root instaladas para a ponte SU do ShizukuX.
*   **Diagnóstico do serviço**: diagnosticar e corrigir problemas de arranque (incluindo Samsung Auto Blocker), exportar a pilha de bloqueio com um toque.
*   **Modo furtivo (ocultar rastos)**: interceta consultas `IPackageManager` para ocultar o ShizukuX da lista de aplicações do sistema.
*   **Controlos de personalização**: tipografia a negrito, cabeçalhos alargados, substituição de raios de canto; animações e háptica seguem o interruptor do sistema.
*   **Tema preto puro AMOLED**: os 9 tokens de superfície corrigidos para que os cartões não pareçam cinzentos em modo preto puro.
*   **Deteção em tempo real da otimização da bateria**: verifica novamente ao regressar das definições do sistema; as políticas de «poupança de energia» MIUI / HyperOS são unificadas e os banners obsoletos desaparecem.
*   **Guia de funcionalidades integrado**: cada melhoria tem um ícone de informação que explica o seu propósito em linguagem simples.
*   **Mosaicos de definições rápidas**: ver e alternar o estado do serviço a partir do painel de notificações (mais mosaicos de controlo: modo de avião, animações, brilho, rotação).

<a name="security"></a>
## 🛡️ Estabilidade e segurança

*   **Compatibilidade com clientes de terceiros (desvio de códigos de transação AIDL)**: funciona com clientes oficiais v13.7+ e de tipo LSPatch que usam códigos de transação AIDL com desvio — os pedidos de permissão já não são mal encaminhados.
*   **Autenticação do handshake do proxy ADB**: o proxy ADB local na porta 15555 verifica agora a assinatura RSA (ou seja, a posse da chave privada) e vincula o UID do chamador; chaves públicas forjadas já não passam, e o protocolo de rede permanece inalterado para clientes de terceiros.
*   **Cadeia de recuperação de falhas reforçada**: o arranque por depuração sem fios executa primeiro uma sonda de pré-condições, recorre ao arranque root quando não há porta TCP configurada e notifica claramente quando nenhum funciona; a degradação por falhas não é permanente — um arrefecimento de 15 minutos reativa o reinício automático.
*   **Correção de deteção isRooted**: compatível com KernelSU / SukiSU em SELinux Enforcing — além do `stat` de ficheiros, consulta os registos de autorização libsu (TTL de 5 min), pelo que os dispositivos com root já não são reportados como sem root.
*   **Correção de visibilidade de pacotes HyperOS / MIUI**: a lista de aplicações é obtida via IPC do servidor (PackageManager shell-uid), corrigindo listas vazias causadas pelas restrições de visibilidade de pacotes do HyperOS.
*   **Reforço do servidor**: proteção contra injeção de shell (validação regex do nome de pacote em cada entrada, rejeição de `/`, `..`, espaços), correções de assinatura APK (Signature Scheme v2), assinatura em streaming para APK grandes contra OOM, e cache do conjunto de pacotes congelados (`isAppFrozen` é O(1)).
*   **Ativação mais rápida**: a depuração sem fios liga-se diretamente a `service.adb.tls.port` em vez de esperar pela descoberta mDNS — a ativação passa de ~2 minutos para ~9 segundos.
*   **A paragem manual continua parada**: parar o serviço também para o supervisor de arranque, já não «ressuscita» 5 segundos depois de tocar em Parar.
*   **Amigo da bateria**: as animações seguem o interruptor de movimento do sistema, as sondas de métricas são limitadas, as notificações de transferência são limitadas por percentagem e o estado inativo não mantém nenhum processo residente.

<a name="extra-api"></a>
## 🚀 Extensões Extra API

O ShizukuX fornece interfaces de sistema exclusivas que o Shizuku original **não tem**, para automação avançada e ferramentas:

*   **Ponte de automação AICore Extra**: automação de UI privilegiada sem Root para ferramentas de IA (despejo de hierarquia, tocar/deslizar). ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Gestor AVF (máquina virtual)**: executa VMs Linux/Microdroid isoladas com aceleração GPU. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Proxy de armazenamento privilegiado**: acesso autenticado a caminhos restritos (`/data/data/`, `/data/app/`) para cópias de segurança e gestão de ficheiros. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Falsificação de dispositivo** (*Spoof Device Identity* nas Definições): apresenta informações de dispositivo diferentes ao sistema para contornar restrições específicas do modelo. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44))
*   **Ponte inteligente** (*AI Core Extra*): agendamento NPU privilegiado e inteligência de contexto de ecrã. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Window Manager Extra**: forçar o redimensionamento de janelas livres, gerir barras de bolhas flutuantes e sobreposições elásticas. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Ponte de temas do sistema** (*Overlay Manager Extra*): gestão Overlay privilegiada sem Root para temas (ex. Hex Installer). ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Governador de rede e DNS**: gere DNS privado e encaminhamento de firewall para bloquear anúncios sem Root. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Controlo profundo de processos** (*Activity Manager Extra*): permite aos gestores de processos matar aplicações de forma mais agressiva e definir grupos de espera. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Ponte de continuidade**: transfere com segurança estado e tarefas entre vários dispositivos ShizukuX. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7))

## 🛠️ Retroportes e desempenho

Sem tocar numa única linha de código, o ShizukuX torna as aplicações Shizuku normais mais rápidas e compatíveis:

*   **Intercetor de shell transparente**: encaminha os comandos `pm`, `am` e `settings` comuns para APIs nativas mais rápidas.
*   **Proxy ADB local**: emula um servidor ADB na porta 15555 para que as aplicações antigas continuem a funcionar com Shizuku após desativar a depuração sem fios.
*   **Ponte SU**: uma alternativa `su` baseada em Shizuku para aplicações sem Root que suportam caminhos Root personalizados.
*   **Ferramenta de linha de comandos `extra`**: CLI privilegiada utilizável dentro de `rish`.
*   **Base de dados de aplicações dinâmica**: sincroniza descrições e sugestões de aplicações do GitHub em tempo real para manter a interface atualizada.

## ⚙️ Controlo modular

Tudo no ShizukuX é opcional. Ative ou desative funções na categoria **Funções Extra** das definições:

*   Interceção de shell transparente
*   Extra API individuais (AVF, armazenamento, inteligente, etc.)
*   Exibição de cartões iniciais
*   Registo de atividade

<a name="compat"></a>
## 🔌 Compatibilidade com aplicações de terceiros

O ShizukuX instala-se com o seu próprio nome de pacote (`xyz.shizuku.extra.api`) e pode coexistir com o Shizuku original. Como a maioria das aplicações Shizuku só verifica o nome de pacote `moe.shizuku.privileged.api`, o ShizukuX inclui um **Compat Hub** leve — uma pequena aplicação complementar que regista esse nome de pacote e reencaminha os pedidos de binder/permissão para o ShizukuX.

**Se uma aplicação de terceiros não detetar o ShizukuX:**

1. Inicie primeiro o serviço ShizukuX (método ADB ou Root).
2. No ecrã inicial, use o cartão **Compat Hub** para instalar a aplicação complementar (incluída no APK; a instalação depende de um serviço em execução, por isso inicie o serviço primeiro).
3. Reabra a aplicação de terceiros — deve agora detetar Shizuku e receber o binder do serviço.

Também pode instalar a **variante drop-in**, que se regista diretamente como `moe.shizuku.privileged.api` (não a instale em simultâneo com o Shizuku original).

<a name="requirements"></a>
## ☑️ Requisitos do sistema

**Mínimo: Android 7+ · Suporte completo até Android 17 (SDK 37)**

- **Modo Root**: requer um dispositivo com root (compatível com Magisk / KernelSU / SukiSU / APatch, etc.)
- **Modo de depuração sem fios**: Android 11+ e todos os Android TV
- **Modo ADB PC**: todos os dispositivos
- **Arranque automático**: disponível apenas em modo de depuração sem fios ou Root

Em **Android 16+**, o ShizukuX solicita a nova permissão de proteção de rede local para manter a descoberta e o emparelhamento da depuração sem fios; em **Android 17**, lida de forma transparente com a alteração da API oculta `deviceId` para que as aplicações autorizadas permaneçam visíveis e as concessões de permissões continuem a funcionar.

<a name="developer-guide"></a>
## 📱 Guia para programadores e documentação

A documentação das Extra API exclusivas está em [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) (o submódulo `api` deste repositório aponta para ele). Mantenedores de forks Shizuku que pretendam migrar para o ShizukuX podem seguir [FORK_MIGRATION.md](FORK_MIGRATION.md).

A documentação completa em chinês para utilizadores finais (ligação ao serviço, base de conhecimentos, permissões, modo Dhizuku, etc.) está em [docs/zh-CN/index.md](docs/zh-CN/index.md) (atualmente apenas em chinês).

<a name="repo-layout"></a>
## 🗂️ Estrutura do repositório

| Diretório | Descrição |
|---|---|
| `manager/` | Aplicação principal: cartões iniciais, definições, gestor de permissões, controlo do dispositivo, cópia/restauração, Watchdog, atualizações |
| `server/` | Processo de serviço privilegiado: ShizukuService e todas as implementações Extra API |
| `core/` | Biblioteca central partilhada (componentes de UI, temas, recursos de cadeias) |
| `api/` | Submódulo [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) |
| `compat/` | Aplicação complementar Compat Hub |
| `shell/` | Ferramentas de linha de comandos privilegiadas `rish` / `extra` |
| `starter/` | Arrancador do serviço (cadeia de arranque ADB / Root / supervisor) |
| `common/` / `app-process/` / `database/` | Código comum, invólucro de processo independente, camada de base de dados |
| `docs/` | Documentação (a ajuda integrada e as notas de atualização apontam para aqui) |

<a name="releases"></a>
## 📦 Versões recentes

| Versão | Tipo | Data | Destaques |
|---|---|---|---|
| **v13.7.0.k2016** | Pré-versão | 2026-10-26 | A paragem manual continua parada, arranque consciente do root (fallback isRooted), ativação mais rápida (~2 min → 9 s), exportação do tutorial de terminal e correções de comandos |
| v13.7.0.k2015 | Pré-versão | 2026-10-25 | 13 correções de uma auditoria de 53 000 linhas, auto-arranque / recuperação de falhas reforçados, definições reorganizadas em Geral / Permissão / Serviço / Diagnóstico |
| v13.7.0.k2014 | Pré-versão | 2026-10-07 | Compatibilidade de desvio de códigos de transação AIDL, suíte completa de controlo do dispositivo / cópia de segurança, modo furtivo, correção de tema AMOLED, remoção do Compat Hub com um toque |
| v13.7.0.r2002 | Estável | 2026-09-22 | Linha estável v13.7.0 |

Para o registo de alterações completo ver [CHANGELOG.md](CHANGELOG.md) e as [Release Notes](https://github.com/qianyumeng0228/ShizukuX/releases) de cada versão.

<a name="credits"></a>
## 🙏 Agradecimentos e licença

O ShizukuX é um fork comunitário melhorado — ver a nota de herança no topo deste documento para a linhagem e o aviso legal.

Obrigado aos seguintes contribuidores e projetos a montante, cujo trabalho tornou o ShizukuX possível:

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — o alicerce Shizuku e o seu design elegante de API.
- **[thedjchi](https://github.com/thedjchi)** — fork intermédio com melhorias de experiência e lar do trabalho de **compatibilidade Android 17 (SDK 37)** de onde o suporte A17 do ShizukuX foi portado.
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — fonte da compatibilidade da API oculta `deviceId` do Android 17 (`Android17Compat` / `InstalledPackagesCompat` camada de reflexão) e do tratamento da proteção de rede local.
- **[LandonMoran](https://github.com/LandonMoran)** — portou o suporte Android 17 do Nightzuku para o fork thedjchi e validou-o **de ponta a ponta num dispositivo Android 17 real** (emparelhamento, arranque do serviço, lista de aplicações autorizadas), fornecendo a base de verificação em dispositivo real para o port do ShizukuX.
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — a base de código aShell You que inspirou as funcionalidades de terminal e automação de shell.
- **[iamr0s](https://github.com/iamr0s)** — Dhizuku para o modo unificado de permissão Device Owner, e AndroidAppProcess para execução de processos Java independentes.
- **[pascua28](https://github.com/pascua28)** — integração nativa de privilégios Samsung System UID 1000.
- **[kerneldroid](https://github.com/kerneldroid)** — o fork Nightzuku que inspirou a nossa resiliência de API Android 16/17 (SDK 37) (tratamento de `deviceId`) e a modernização da interface.
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — o conceito de privilégios UID 1000 a nível de sistema para além dos limites normais.

### Projetos a montante

| Projeto | Autor | Licença | Papel |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | Arquitetura fundacional de processos privilegiados |
| [Shizuku (fork)](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | Fork intermédio com melhorias de experiência; alberga o trabalho de compatibilidade Android 17 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Fonte de compatibilidade `deviceId` Android 17 + proteção de rede local |
| [Shizuku (fork)](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | Esquema de execução do sistema Samsung UID 1000 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Resiliência de API Android 16/17 e modernização da interface |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | Equipa ShizukuExt | Apache 2.0 | Conceito de privilégio UID do sistema |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Partilha de binder Device Owner (modo Dhizuku) |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | Invólucro de processo Java altamente privilegiado independente |

### Bibliotecas de código aberto

Este projeto utiliza as seguintes bibliotecas de código aberto principais (lista completa e textos de licença em [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) e [NOTICE](NOTICE)): AndroidX Jetpack, Material Components, Kotlin / Coroutines, RikkaX Libraries, Mavericks, Lottie, Coil, Koin, Timber, libsu, AndroidHiddenApiBypass, Gson, LeakCanary, AboutLibraries, Bouncy Castle, Sentry, SQLite, etc.

## 📃 Licença

[Apache 2.0](LICENSE)

### Créditos
- Agradecimento especial a **AkayamiShurui42** pela investigação de segurança proativa e correções de estabilidade (#239).
- Obrigado a **AlexeiCrystal** por localizar o bloqueio MIUI e propor o esquema Compat Hub para aplicações antigas (#241, #242).
- Obrigado a **ddnexus** e **kai-bash** por assinalarem a armadilha de reposição de fábrica do Device Owner e os conflitos com a cópia de segurança do Google (#237).
- Obrigado a **Kevinco1** pelos comentários sobre a deteção de aplicações de compatibilidade Root (#243).
- Obrigado a **aragortsantiago6-beep**, **Scoop2389** (Pixel 9a) e **ConversionRituals** (Xiaomi) pelos testes em dispositivo Android 16/17, relatórios de bloqueio e registos que impulsionaram as correções de API ocultas SDK 37 e de proteção de rede local (#317, #323).
- Obrigado a **gmm96** por múltiplas sessões de depuração logcat que finalmente isolaram o erro de entrega de binder do Cached Apps Freezer (#371).
- Obrigado a **[odorizzioficial](https://github.com/odorizzioficial)** por uma tradução completa em português do Brasil (#409) e um relatório detalhado sobre os bloqueios do watchdog Samsung «Sleeping apps» (#415).

## 🤝 Contribuição e suporte

- Reportar problemas e sugestões: [GitHub Issues](https://github.com/qianyumeng0228/ShizukuX/issues)
- Desenvolvimento: ver [CONTRIBUTING.md](CONTRIBUTING.md)
- Vulnerabilidades de segurança: ver [SECURITY.md](SECURITY.md)
- Registo de alterações completo: [CHANGELOG.md](CHANGELOG.md) · [CHANGES.md](CHANGES.md)
