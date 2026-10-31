<div align="center">

# ShizukuX

[简体中文](./README.md) · [English](./README.en.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [Deutsch](./README.de.md) · [Français](./README.fr.md) · [Español](./README.es.md) · [Português](./README.pt-PT.md) · [Italiano](./README.it.md) · [한국어](./README.ko.md) · **Português (Brasil)**

**Gerenciador de processos privilegiados Android para usuários avançados**

ShizukuX é um fork comunitário aprimorado de [Shizuku](https://github.com/RikkaApps/Shizuku), construído sobre [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) e [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus). Ele herda todos os recursos do projeto original e adiciona localização e melhorias de experiência para um público mais amplo.

O Shizuku permite que aplicativos comuns chamem diretamente APIs de sistema por meio de um processo privilegiado iniciado via **ADB / Root**. O ShizukuX mantém 100% de compatibilidade enquanto adiciona recursos práticos para usuários avançados e desenvolvedores.

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)
[![License](https://img.shields.io/github/license/qianyumeng0228/ShizukuX?style=for-the-badge&color=30bf60&labelColor=208040)](LICENSE)

> **Nota de herança**: O ShizukuX é a continuação renomeada e localizada em chinês do ShizukuPlus (qianyumeng0228); o ShizukuPlus é por sua vez um fork do thejaustin/ShizukuPlus e do thedjchi/Shizuku, remontando em última instância ao projeto Shizuku da RikkaApps. O ShizukuX é um fork comunitário mantido de forma independente e **não tem nenhuma afiliação, endosso ou parceria** com **RikkaApps/Shizuku, thedjchi/Shizuku, thejaustin/ShizukuPlus ou seus mantenedores**. Todos os avisos de direitos autorais e atribuição a montante são integralmente preservados (veja [LICENSE](LICENSE), [NOTICE](NOTICE), [CHANGES.md](CHANGES.md)).

</div>

## 📑 Índice

- [🚀 Início rápido](#quickstart)
- [⬇️ Download](#download)
- [✨ Recursos principais](#features)
- [🛡️ Estabilidade e segurança](#security)
- [🚀 Extensões Extra API](#extra-api)
- [🔌 Compatibilidade com aplicativos de terceiros](#compat)
- [☑️ Requisitos do sistema](#requirements)
- [📱 Guia do desenvolvedor e documentação](#developer-guide)
- [🗂️ Estrutura do repositório](#repo-layout)
- [📦 Versões recentes](#releases)
- [🙏 Agradecimentos e licença](#credits)

<a name="quickstart"></a>
## 🚀 Início rápido

1. Baixe o APK **Standard** mais recente de [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) e instale-o (nome de pacote independente `xyz.shizuku.extra.api`, coexiste com o Shizuku original; a variante **Drop-In** é instalada com o nome de pacote original, substitui-o e não pode coexistir).
2. Abra o aplicativo e inicie o serviço com **depuração sem fio / ADB do PC / Root** (ativação ~9 segundos).
3. Conceda a permissão Shizuku aos aplicativos desejados dentro do app e comece a usar.

> Guias detalhados, erros comuns e solução de problemas na [documentação em chinês](docs/zh-CN/index.md) (atualmente apenas em chinês).

<a name="download"></a>
## ⬇️ Download

Obtenha a versão mais recente em [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases). Cada versão fornece os seguintes três APKs:

| Variante | Nome do pacote | Uso |
|---|---|---|
| **Standard** | `xyz.shizuku.extra.api` | Instalação limpa com nome de pacote próprio; coexiste com outros builds do Shizuku |
| **Drop-In** | `moe.shizuku.privileged.api` | Substituição direta do Shizuku original; compatível com todos os apps da API Shizuku (não instale junto com o Shizuku original) |
| **Compat-Hub** | `moe.shizuku.privileged.api` | Pequeno aplicativo complementar que permite que apps de terceiros que só detectam o nome de pacote original vejam o ShizukuX (incluído no APK Standard; instalação/remoção pelo app) |

- As versões estáveis são identificadas com a **série `r`** (ex.: `v13.7.0.r2002`), as pré-versões com a **série `k`** (ex.: `v13.7.0.k2016`); ambas incluem os mesmos três APKs.
- Consulte as notas de cada versão para detalhes e [CHANGELOG.md](CHANGELOG.md) para o histórico completo.

<a name="features"></a>
## ✨ Recursos principais do ShizukuX

*   **Provedor universal de permissões**: uma interface que unifica **Root**, **ADB Shell** e **Dhizuku (proprietário do dispositivo)** como fontes de permissão.
*   **Correção de tema OneUI 8+**: mantém os mecanismos de tema como Hex Installer e Substratum funcionando no Android 16/17 e OneUI 8+.
*   **Modo Dhizuku**: compartilha o Binder de Device Owner do sistema com qualquer app que tenha permissão Shizuku — configurável via ADB, sem Root; uma página dedicada de **Nível de permissão** gerencia os três níveis Shell / Root / DO.
*   **Gerenciador de permissões de precisão**: tela dedicada para conceder/revogar permissões Shizuku por app em massa, vinculada ao cartão da tela inicial.
*   **Controle do dispositivo**: 18 operações em cinco categorias (conectividade, exibição, áudio, energia, configurações do sistema — modo avião, brilho automático, rotação automática, escala de animação, reinicialização/desligamento, etc.); itens perigosos são automaticamente desativados com um aviso no modo ADB.
*   **Backup e restauração**: exportação SAF para pastas públicas, backup/restauração em lote (incluindo dados OBB), restauração de dados internos (`run-as` + tar), snapshots antes da restauração e histórico de restaurações.
*   **Gestos personalizáveis**: deslizar para a esquerda/direita, toque longo, etc., configuráveis por app.
*   **Log de alterações integrado**: veja as novidades após uma atualização sem sair do app.
*   **Gerenciamento em lote**: selecione vários apps para conceder/revogar permissões ou ocultá-los com um toque.
*   **Log de atividade**: auditoria em tempo real de chamadas de API e comandos da ponte `su`, com ícones de apps e atualização ao vivo.
*   **Centro de compatibilidade Root**: painel para apps Root legados com controle fino de módulos (falsificação de Magisk, concessão automática, interceptação de arquivos, etc.).
*   **Automação SU universal**: «configurações mágicas» com um toque que apontam todos os apps Root instalados para a ponte SU do ShizukuX.
*   **Diagnóstico do serviço**: diagnostique e corrija problemas de inicialização (incluindo Samsung Auto Blocker), exporte a pilha de travamento com um toque.
*   **Modo furtivo (ocultar rastros)**: intercepta consultas `IPackageManager` para ocultar o ShizukuX da lista de apps do sistema.
*   **Controles de personalização**: tipografia em negrito, cabeçalhos expandidos, substituição de raios de canto; animações e hápticos seguem o interruptor do sistema.
*   **Tema preto puro AMOLED**: os 9 tokens de superfície corrigidos para que os cartões não pareçam cinzas no modo preto puro.
*   **Detecção em tempo real da otimização da bateria**: verifica novamente ao voltar das configurações do sistema; as políticas de «economia de energia» MIUI / HyperOS são unificadas e os banners obsoletos desaparecem.
*   **Guia de recursos integrado**: cada melhoria tem um ícone de informação que explica seu propósito em linguagem simples.
*   **Blocos de configurações rápidas**: veja e alterne o estado do serviço pelo painel de notificações (mais blocos de controle: modo avião, animações, brilho, rotação).

<a name="security"></a>
## 🛡️ Estabilidade e segurança

*   **Compatibilidade com clientes de terceiros (deslocamento de códigos de transação AIDL)**: funciona com clientes oficiais v13.7+ e do tipo LSPatch que usam códigos de transação AIDL com deslocamento — as solicitações de permissão não são mais roteadas incorretamente.
*   **Autenticação de handshake do proxy ADB**: o proxy ADB local na porta 15555 agora verifica a assinatura RSA (ou seja, a posse da chave privada) e vincula o UID do chamador; chaves públicas forjadas não passam mais, e o protocolo de rede permanece inalterado para clientes de terceiros.
*   **Cadeia de recuperação de falhas reforçada**: a inicialização por depuração sem fio executa primeiro uma sonda de pré-condições, recorre à inicialização root quando não há porta TCP configurada e notifica claramente quando nenhuma funciona; a degradação por falhas não é permanente — um resfriamento de 15 minutos reativa a reinicialização automática.
*   **Correção de detecção isRooted**: compatível com KernelSU / SukiSU em SELinux Enforcing — além do `stat` de arquivos, consulta os registros de autorização libsu (TTL de 5 min), portanto dispositivos com root não são mais reportados como sem root.
*   **Correção de visibilidade de pacotes HyperOS / MIUI**: a lista de apps é obtida via IPC do servidor (PackageManager shell-uid), corrigindo listas vazias causadas pelas restrições de visibilidade de pacotes do HyperOS.
*   **Reforço do servidor**: proteção contra injeção de shell (validação regex do nome de pacote em cada entrada, rejeição de `/`, `..`, espaços), correções de assinatura APK (Signature Scheme v2), assinatura em streaming para APKs grandes contra OOM, e cache do conjunto de pacotes congelados (`isAppFrozen` é O(1)).
*   **Ativação mais rápida**: a depuração sem fio conecta-se diretamente a `service.adb.tls.port` em vez de esperar pela descoberta mDNS — a ativação passa de ~2 minutos para ~9 segundos.
*   **A parada manual permanece parada**: parar o serviço também para o supervisor de inicialização, ele não «ressuscita» mais 5 segundos depois de tocar em Parar.
*   **Amigo da bateria**: as animações seguem o interruptor de movimento do sistema, as sondas de métricas são limitadas, as notificações de download são limitadas por porcentagem e o estado ocioso não mantém processos residentes.

<a name="extra-api"></a>
## 🚀 Extensões Extra API

O ShizukuX fornece interfaces de sistema exclusivas que o Shizuku original **não tem**, para automação avançada e ferramentas:

*   **Ponte de automação AICore Extra**: automação de UI privilegiada sem Root para ferramentas de IA (dump de hierarquia, tocar/deslizar). ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Gerenciador AVF (máquina virtual)**: executa VMs Linux/Microdroid isoladas com aceleração de GPU. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Proxy de armazenamento privilegiado**: acesso autenticado a caminhos restritos (`/data/data/`, `/data/app/`) para backup e gerenciamento de arquivos. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Falsificação de dispositivo** (*Spoof Device Identity* nas Configurações): apresenta informações de dispositivo diferentes ao sistema para contornar restrições específicas do modelo. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44))
*   **Ponte inteligente** (*AI Core Extra*): agendamento NPU privilegiado e inteligência de contexto de tela. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Window Manager Extra**: força o redimensionamento de janelas livres, gerencia barras de bolhas flutuantes e sobreposições elásticas. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Ponte de temas do sistema** (*Overlay Manager Extra*): gerenciamento Overlay privilegiado sem Root para temas (ex.: Hex Installer). ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Governador de rede e DNS**: gerencia DNS privado e roteamento de firewall para bloqueio de anúncios sem Root. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Controle profundo de processos** (*Activity Manager Extra*): permite que os gerenciadores de processos encerrem apps de forma mais agressiva e definam buckets de espera. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Ponte de continuidade**: transfere com segurança estado e tarefas entre vários dispositivos ShizukuX. ([commit de introdução](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7))

## 🛠️ Backports e desempenho

Sem tocar em uma única linha de código, o ShizukuX torna os apps Shizuku comuns mais rápidos e compatíveis:

*   **Interceptador de shell transparente**: roteia comandos comuns `pm`, `am` e `settings` para APIs nativas mais rápidas.
*   **Proxy ADB local**: emula um servidor ADB na porta 15555 para que apps legados continuem funcionando com Shizuku após desativar a depuração sem fio.
*   **Ponte SU**: uma alternativa `su` baseada em Shizuku para apps sem Root que suportam caminhos Root personalizados.
*   **Ferramenta de linha de comando `extra`**: CLI privilegiada utilizável dentro do `rish`.
*   **Banco de dados de apps dinâmico**: sincroniza descrições e sugestões de apps do GitHub em tempo real para manter a interface atualizada.

## ⚙️ Controle modular

Tudo no ShizukuX é opcional. Ative ou desative funções na categoria **Funções Extra** das configurações:

*   Interceptação de shell transparente
*   Extra APIs individuais (AVF, armazenamento, inteligente, etc.)
*   Exibição dos cartões iniciais
*   Log de atividade

<a name="compat"></a>
## 🔌 Compatibilidade com aplicativos de terceiros

O ShizukuX é instalado com nome de pacote próprio (`xyz.shizuku.extra.api`) e pode coexistir com o Shizuku original. Como a maioria dos apps Shizuku só verifica o nome de pacote `moe.shizuku.privileged.api`, o ShizukuX inclui um **Compat Hub** leve — um pequeno aplicativo complementar que registra esse nome de pacote e encaminha as solicitações de binder/permissão para o ShizukuX.

**Se um aplicativo de terceiros não detectar o ShizukuX:**

1. Inicie primeiro o serviço ShizukuX (método ADB ou Root).
2. Na tela inicial, use o cartão **Compat Hub** para instalar o aplicativo complementar (incluído no APK; a instalação depende de um serviço em execução, então inicie o serviço primeiro).
3. Reabra o aplicativo de terceiros — ele agora deve detectar o Shizuku e receber o binder do serviço.

Você também pode instalar a **variante drop-in**, que se registra diretamente como `moe.shizuku.privileged.api` (não a instale junto com o Shizuku original).

<a name="requirements"></a>
## ☑️ Requisitos do sistema

**Mínimo: Android 7+ · Suporte completo até Android 17 (SDK 37)**

- **Modo Root**: requer um dispositivo com root (compatível com Magisk / KernelSU / SukiSU / APatch, etc.)
- **Modo de depuração sem fio**: Android 11+ e todos os Android TV
- **Modo ADB do PC**: todos os dispositivos
- **Inicialização automática**: disponível apenas no modo de depuração sem fio ou Root

No **Android 16+**, o ShizukuX solicita a nova permissão de proteção de rede local para manter a descoberta e o pareamento da depuração sem fio; no **Android 17**, ele lida de forma transparente com a alteração da API oculta `deviceId` para que os apps autorizados permaneçam visíveis e as concessões de permissão continuem funcionando.

<a name="developer-guide"></a>
## 📱 Guia do desenvolvedor e documentação

A documentação das Extra APIs exclusivas está em [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) (o submódulo `api` deste repositório aponta para ele). Mantenedores de forks do Shizuku que desejam migrar para o ShizukuX podem seguir [FORK_MIGRATION.md](FORK_MIGRATION.md).

A documentação completa em chinês para usuários finais (conexão ao serviço, base de conhecimento, permissões, modo Dhizuku, etc.) está em [docs/zh-CN/index.md](docs/zh-CN/index.md) (atualmente apenas em chinês).

<a name="repo-layout"></a>
## 🗂️ Estrutura do repositório

| Diretório | Descrição |
|---|---|
| `manager/` | Aplicativo principal: cartões iniciais, configurações, gerenciador de permissões, controle do dispositivo, backup/restauração, Watchdog, atualizações |
| `server/` | Processo de serviço privilegiado: ShizukuService e todas as implementações Extra API |
| `core/` | Biblioteca central compartilhada (componentes de UI, temas, recursos de strings) |
| `api/` | Submódulo [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) |
| `compat/` | Aplicativo complementar Compat Hub |
| `shell/` | Ferramentas de linha de comando privilegiadas `rish` / `extra` |
| `starter/` | Iniciador do serviço (cadeia de inicialização ADB / Root / supervisor) |
| `common/` / `app-process/` / `database/` | Código comum, wrapper de processo autônomo, camada de banco de dados |
| `docs/` | Documentação (a ajuda integrada e as notas de atualização apontam para cá) |

<a name="releases"></a>
## 📦 Versões recentes

| Versão | Tipo | Data | Destaques |
|---|---|---|---|
| **v13.7.0.k2016** | Pré-versão | 2026-10-26 | A parada manual permanece parada, inicialização consciente do root (fallback isRooted), ativação mais rápida (~2 min → 9 s), exportação do tutorial do terminal e correções de comandos |
| v13.7.0.k2015 | Pré-versão | 2026-10-25 | 13 correções de uma auditoria de 53 000 linhas, auto-inicialização / recuperação de falhas reforçadas, configurações reorganizadas em Geral / Permissão / Serviço / Diagnóstico |
| v13.7.0.k2014 | Pré-versão | 2026-10-07 | Compatibilidade de deslocamento de códigos de transação AIDL, suíte completa de controle do dispositivo / backup, modo furtivo, correção de tema AMOLED, remoção do Compat Hub com um toque |
| v13.7.0.r2002 | Estável | 2026-09-22 | Linha estável v13.7.0 |

Para o changelog completo veja [CHANGELOG.md](CHANGELOG.md) e as [Release Notes](https://github.com/qianyumeng0228/ShizukuX/releases) de cada versão.

<a name="credits"></a>
## 🙏 Agradecimentos e licença

O ShizukuX é um fork comunitário aprimorado — veja a nota de herança no topo deste documento para a linhagem e o aviso legal.

Obrigado aos seguintes contribuidores e projetos a montante, cujo trabalho tornou o ShizukuX possível:

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — o alicerce do Shizuku e seu design elegante de API.
- **[thedjchi](https://github.com/thedjchi)** — fork intermediário com melhorias de experiência e lar do trabalho de **compatibilidade Android 17 (SDK 37)** de onde o suporte A17 do ShizukuX foi portado.
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — fonte da compatibilidade da API oculta `deviceId` do Android 17 (`Android17Compat` / `InstalledPackagesCompat` camada de reflexão) e do tratamento da proteção de rede local.
- **[LandonMoran](https://github.com/LandonMoran)** — portou o suporte Android 17 do Nightzuku para o fork thedjchi e o validou **de ponta a ponta em um dispositivo Android 17 real** (pareamento, inicialização do serviço, lista de apps autorizados), fornecendo a base de verificação em dispositivo real para o port do ShizukuX.
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — a base de código do aShell You que inspirou os recursos de terminal e automação de shell.
- **[iamr0s](https://github.com/iamr0s)** — Dhizuku para o modo unificado de permissão Device Owner, e AndroidAppProcess para execução de processos Java independentes.
- **[pascua28](https://github.com/pascua28)** — integração nativa de privilégios Samsung System UID 1000.
- **[kerneldroid](https://github.com/kerneldroid)** — o fork Nightzuku que inspirou nossa resiliência de API Android 16/17 (SDK 37) (tratamento de `deviceId`) e a modernização da interface.
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — o conceito de privilégios UID 1000 a nível de sistema além dos limites normais.

### Projetos a montante

| Projeto | Autor | Licença | Papel |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | Arquitetura fundacional de processos privilegiados |
| [Shizuku (fork)](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | Fork intermediário com melhorias de experiência; abriga o trabalho de compatibilidade Android 17 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Fonte da compatibilidade `deviceId` Android 17 + proteção de rede local |
| [Shizuku (fork)](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | Esquema de execução do sistema Samsung UID 1000 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Resiliência de API Android 16/17 e modernização da interface |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | Equipe ShizukuExt | Apache 2.0 | Conceito de privilégio UID do sistema |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Compartilhamento de binder Device Owner (modo Dhizuku) |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | Wrapper de processo Java altamente privilegiado independente |

### Bibliotecas de código aberto

Este projeto utiliza as seguintes bibliotecas de código aberto principais (lista completa e textos de licença em [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) e [NOTICE](NOTICE)): AndroidX Jetpack, Material Components, Kotlin / Coroutines, RikkaX Libraries, Mavericks, Lottie, Coil, Koin, Timber, libsu, AndroidHiddenApiBypass, Gson, LeakCanary, AboutLibraries, Bouncy Castle, Sentry, SQLite, etc.

## 📃 Licença

[Apache 2.0](LICENSE)

### Créditos
- Agradecimento especial a **AkayamiShurui42** pela pesquisa proativa de segurança e correções de estabilidade (#239).
- Obrigado a **AlexeiCrystal** por localizar o travamento MIUI e propor o esquema Compat Hub para apps legados (#241, #242).
- Obrigado a **ddnexus** e **kai-bash** por apontarem a armadilha de restauração de fábrica do Device Owner e os conflitos com o backup do Google (#237).
- Obrigado a **Kevinco1** pelo feedback sobre a detecção de apps de compatibilidade Root (#243).
- Obrigado a **aragortsantiago6-beep**, **Scoop2389** (Pixel 9a) e **ConversionRituals** (Xiaomi) pelos testes em dispositivo Android 16/17, relatórios de travamento e logs que impulsionaram as correções de API ocultas SDK 37 e de proteção de rede local (#317, #323).
- Obrigado a **gmm96** por múltiplas sessões de depuração logcat que finalmente isolaram o bug de entrega de binder do Cached Apps Freezer (#371).
- Obrigado a **[odorizzioficial](https://github.com/odorizzioficial)** por uma tradução completa em português do Brasil (#409) e um relatório detalhado sobre os congelamentos do watchdog Samsung «Sleeping apps» (#415).

## 🤝 Contribuição e suporte

- Reportar problemas e sugestões: [GitHub Issues](https://github.com/qianyumeng0228/ShizukuX/issues)
- Desenvolvimento: veja [CONTRIBUTING.md](CONTRIBUTING.md)
- Vulnerabilidades de segurança: veja [SECURITY.md](SECURITY.md)
- Changelog completo: [CHANGELOG.md](CHANGELOG.md) · [CHANGES.md](CHANGES.md)
