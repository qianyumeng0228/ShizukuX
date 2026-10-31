<div align="center">

# ShizukuX

[简体中文](./README.md) · [English](./README.en.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [Deutsch](./README.de.md) · [Français](./README.fr.md) · **Español** · [Português](./README.pt-PT.md) · [Italiano](./README.it.md) · [한국어](./README.ko.md) · [Português (Brasil)](./README.pt-BR.md)

**Gestor de procesos privilegiados de Android para usuarios avanzados**

ShizukuX es un fork mejorado de la comunidad de [Shizuku](https://github.com/RikkaApps/Shizuku), construido sobre [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) y [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus). Hereda todas las capacidades del proyecto original y añade localización y mejoras de experiencia para un público más amplio.

Shizuku permite que las aplicaciones normales llamen directamente a APIs de sistema a través de un proceso privilegiado iniciado mediante **ADB / Root**. ShizukuX mantiene un 100 % de compatibilidad mientras añade capacidades prácticas para usuarios avanzados y desarrolladores.

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)
[![License](https://img.shields.io/github/license/qianyumeng0228/ShizukuX?style=for-the-badge&color=30bf60&labelColor=208040)](LICENSE)

> **Nota de herencia**: ShizukuX es la continuación renombrada y localizada al chino de ShizukuPlus (qianyumeng0228); ShizukuPlus es a su vez un fork de thejaustin/ShizukuPlus y thedjchi/Shizuku, que en última instancia se remonta al proyecto Shizuku de RikkaApps. ShizukuX es un fork comunitario mantenido de forma independiente y **no tiene afiliación, respaldo ni asociación** con **RikkaApps/Shizuku, thedjchi/Shizuku, thejaustin/ShizukuPlus ni sus mantenedores**. Todos los avisos de derechos de autor y atribución ascendentes se conservan íntegramente (ver [LICENSE](LICENSE), [NOTICE](NOTICE), [CHANGES.md](CHANGES.md)).

</div>

## 📑 Índice

- [🚀 Inicio rápido](#quickstart)
- [⬇️ Descarga](#download)
- [✨ Funciones principales](#features)
- [🛡️ Estabilidad y seguridad](#security)
- [🚀 Extensiones Extra API](#extra-api)
- [🔌 Compatibilidad con aplicaciones de terceros](#compat)
- [☑️ Requisitos del sistema](#requirements)
- [📱 Guía para desarrolladores y documentación](#developer-guide)
- [🗂️ Estructura del repositorio](#repo-layout)
- [📦 Versiones recientes](#releases)
- [🙏 Agradecimientos y licencia](#credits)

<a name="quickstart"></a>
## 🚀 Inicio rápido

1. Descargue la última APK **Estándar** desde [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases) e instálela (nombre de paquete independiente `xyz.shizuku.extra.api`, coexiste con el Shizuku original; la variante **Drop-In** se instala con el nombre de paquete original, lo reemplaza y no puede coexistir).
2. Abra la aplicación e inicie el servicio con **depuración inalámbrica / ADB de PC / Root** (activación ~9 segundos).
3. Conceda permiso Shizuku a las aplicaciones que desee dentro de la aplicación y empiece a usar.

> Guías detalladas, errores comunes y solución de problemas en la [documentación en chino](docs/zh-CN/index.md) (actualmente solo en chino).

<a name="download"></a>
## ⬇️ Descarga

Obtenga la última versión en [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases). Cada versión incluye los siguientes tres APK:

| Variante | Nombre de paquete | Uso |
|---|---|---|
| **Estándar** | `xyz.shizuku.extra.api` | Instalación limpia con su propio nombre de paquete; coexiste con otras compilaciones de Shizuku |
| **Drop-In** | `moe.shizuku.privileged.api` | Reemplazo directo del Shizuku original; compatible con todas las aplicaciones de la API Shizuku (no instalar junto con el Shizuku original) |
| **Compat-Hub** | `moe.shizuku.privileged.api` | Pequeña aplicación complementaria que permite que las aplicaciones de terceros que solo detectan el nombre de paquete original vean ShizukuX (incluida en el APK Estándar; se instala/desinstala desde la aplicación) |

- Las versiones estables se etiquetan con la **serie `r`** (p. ej. `v13.7.0.r2002`), las pre-versiones con la **serie `k`** (p. ej. `v13.7.0.k2016`); ambas incluyen los mismos tres APK.
- Consulte las notas de cada versión para los detalles y [CHANGELOG.md](CHANGELOG.md) para el historial completo.

<a name="features"></a>
## ✨ Funciones principales de ShizukuX

*   **Proveedor de permisos universal**: una interfaz que unifica **Root**, **ADB Shell** y **Dhizuku (propietario del dispositivo)** como fuentes de permisos.
*   **Corrección de tema OneUI 8+**: mantiene los motores de temas como Hex Installer y Substratum funcionando en Android 16/17 y OneUI 8+.
*   **Modo Dhizuku**: comparte el Binder de Device Owner del sistema con cualquier aplicación que tenga permiso Shizuku — configurable mediante ADB, sin Root; una página dedicada de **Nivel de permiso** gestiona los tres niveles Shell / Root / DO.
*   **Gestor de permisos de grano fino**: pantalla dedicada para conceder/revocar permisos Shizuku por aplicación en masa, vinculada a la tarjeta de la pantalla de inicio.
*   **Control del dispositivo**: 18 operaciones en cinco categorías (conectividad, pantalla, audio, energía, ajustes del sistema — modo avión, brillo automático, rotación automática, escala de animación, reinicio/apagado, etc.); los elementos peligrosos se desactivan automáticamente con una indicación en modo ADB.
*   **Copia de seguridad y restauración**: exportación SAF a directorios públicos, copia/restauración por lotes (incluidos datos OBB), restauración de datos internos (`run-as` + tar), instantáneas previas a la restauración e historial de restauraciones.
*   **Gestos personalizables**: deslizar a la izquierda/derecha, pulsación larga, etc., configurables por aplicación.
*   **Registro de cambios integrado**: ver las novedades tras una actualización sin salir de la aplicación.
*   **Gestión por lotes**: seleccione varias aplicaciones para conceder/revocar permisos u ocultarlas de una vez.
*   **Registro de actividad**: auditoría en tiempo real de llamadas API y comandos del puente `su`, con iconos de aplicación y actualización en vivo.
*   **Centro de compatibilidad Root**: panel para aplicaciones Root heredadas con control fino de módulos (suplantación de Magisk, concesión automática, interceptación de archivos, etc.).
*   **Automatización SU universal**: «ajustes mágicos» con un toque que apuntan todas las aplicaciones Root instaladas al puente SU de ShizukuX.
*   **Diagnóstico del servicio**: diagnosticar y corregir problemas de inicio (incluido Samsung Auto Blocker), exportar el volcado de pila de bloqueo con un toque.
*   **Modo sigiloso (ocultar rastros)**: intercepta las consultas `IPackageManager` para ocultar ShizukuX de la lista de aplicaciones del sistema.
*   **Controles de personalización**: tipografía en negrita, encabezados ampliados, anulación de radios de esquina; animaciones y hápticos siguen el interruptor del sistema.
*   **Tema negro puro AMOLED**: los 9 tokens de superficie corregidos para que las tarjetas no se vean grises en modo negro puro.
*   **Detección en tiempo real de la optimización de batería**: vuelve a comprobar al regresar de los ajustes del sistema; las políticas de «ahorro de energía» de MIUI / HyperOS se unifican y los banners obsoletos desaparecen.
*   **Guía de funciones integrada**: cada mejora incluye un icono de información que explica su propósito en lenguaje sencillo.
*   **Mosaicos de ajustes rápidos**: ver y alternar el estado del servicio desde el panel de notificaciones (más mosaicos de control: modo avión, animaciones, brillo, rotación).

<a name="security"></a>
## 🛡️ Estabilidad y seguridad

*   **Compatibilidad con clientes de terceros (desplazamiento de códigos de transacción AIDL)**: funciona con clientes oficiales v13.7+ y de tipo LSPatch que usan códigos de transacción AIDL desplazados — las solicitudes de permiso ya no se enrutan mal.
*   **Autenticación del protocolo de enlace del proxy ADB**: el proxy ADB local en el puerto 15555 ahora verifica la firma RSA (es decir, la posesión de la clave privada) y vincula el UID del llamante; las claves públicas falsificadas ya no pueden pasar, y el protocolo inalámbrico no cambia para los clientes de terceros.
*   **Cadena de recuperación de bloqueos reforzada**: el inicio de la depuración inalámbrica ejecuta primero una sonda de requisitos previos, recurre al inicio root cuando no hay puerto TCP configurado y notifica claramente cuando ninguno funciona; la degradación por bloqueos no es permanente — un enfriamiento de 15 minutos reactiva el reinicio automático.
*   **Corrección de detección isRooted**: compatible con KernelSU / SukiSU bajo SELinux Enforcing — además del `stat` de archivos, consulta los registros de autorización de libsu (TTL de 5 min), por lo que los dispositivos rooteados ya no se informan como no rooteados.
*   **Corrección de visibilidad de paquetes HyperOS / MIUI**: la lista de aplicaciones se obtiene mediante IPC del servidor (PackageManager shell-uid), corrigiendo las listas vacías causadas por las restricciones de visibilidad de paquetes de HyperOS.
*   **Refuerzo del servidor**: protección contra inyección de shell (validación regex del nombre de paquete en cada entrada, rechazo de `/`, `..`, espacios), correcciones de firma APK (Signature Scheme v2), firma en streaming para APK grandes contra OOM, y caché del conjunto de paquetes congelados (`isAppFrozen` es O(1)).
*   **Activación más rápida**: la depuración inalámbrica se conecta directamente a `service.adb.tls.port` en lugar de esperar el descubrimiento mDNS — la activación pasa de ~2 minutos a ~9 segundos.
*   **El detenido manual sigue detenido**: detener el servicio también detiene el supervisor de arranque, ya no «resucita» 5 segundos después de pulsar Detener.
*   **Respetuoso con la batería**: las animaciones siguen el interruptor de movimiento del sistema, las sondas de métricas se limitan, las notificaciones de descarga se limitan por porcentaje y el estado inactivo no mantiene ningún proceso residente.

<a name="extra-api"></a>
## 🚀 Extensiones Extra API

ShizukuX proporciona interfaces de sistema exclusivas que el Shizuku original **no tiene**, para automatización avanzada y herramientas:

*   **Puente de automatización AICore Extra**: automatización de UI privilegiada sin Root para herramientas de IA (volcado de jerarquía, tocar/deslizar). ([commit de introducción](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Gestor AVF (máquina virtual)**: ejecuta VM Linux/Microdroid aisladas con aceleración GPU. ([commit de introducción](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Proxy de almacenamiento privilegiado**: acceso autenticado a rutas restringidas (`/data/data/`, `/data/app/`) para copias de seguridad y gestión de archivos. ([commit de introducción](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **Suplantación de dispositivo** (*Spoof Device Identity* en Ajustes): presenta información de dispositivo diferente al sistema para evitar restricciones específicas del modelo. ([commit de introducción](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44))
*   **Puente inteligente** (*AI Core Extra*): programación NPU privilegiada e inteligencia de contexto de pantalla. ([commit de introducción](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Window Manager Extra**: forzar el redimensionado de ventanas libres, gestionar barras de burbujas flotantes y superposiciones elásticas. ([commit de introducción](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Puente de temas del sistema** (*Overlay Manager Extra*): gestión Overlay privilegiada sin Root para temas (p. ej. Hex Installer). ([commit de introducción](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Gobernador de red y DNS**: gestiona DNS privado y enrutamiento de firewall para bloquear anuncios sin Root. ([commit de introducción](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Control profundo de procesos** (*Activity Manager Extra*): permite a los gestores de procesos matar aplicaciones de forma más agresiva y establecer grupos de espera. ([commit de introducción](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **Puente de continuidad**: transfiere de forma segura estado y tareas entre varios dispositivos ShizukuX. ([commit de introducción](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7))

## 🛠️ Retroportes y rendimiento

Sin tocar una sola línea de código, ShizukuX hace que las aplicaciones Shizuku normales sean más rápidas y compatibles:

*   **Interceptor de shell transparente**: enruta los comandos `pm`, `am` y `settings` comunes a APIs nativas más rápidas.
*   **Proxy ADB local**: emula un servidor ADB en el puerto 15555 para que las aplicaciones heredadas sigan funcionando con Shizuku después de desactivar la depuración inalámbrica.
*   **Puente SU**: una alternativa `su` basada en Shizuku para aplicaciones sin Root que admiten rutas Root personalizadas.
*   **Herramienta de línea de comandos `extra`**: CLI privilegiada utilizable dentro de `rish`.
*   **Base de datos de aplicaciones dinámica**: sincroniza descripciones y sugerencias de aplicaciones desde GitHub en tiempo real para mantener la interfaz actualizada.

## ⚙️ Control modular

Todo en ShizukuX es opcional. Active o desactive funciones en la categoría **Funciones Extra** de los ajustes:

*   Interceptación de shell transparente
*   Extra API individuales (AVF, almacenamiento, inteligente, etc.)
*   Visualización de tarjetas de inicio
*   Registro de actividad

<a name="compat"></a>
## 🔌 Compatibilidad con aplicaciones de terceros

ShizukuX se instala con su propio nombre de paquete (`xyz.shizuku.extra.api`) y puede coexistir con el Shizuku original. Como la mayoría de las aplicaciones Shizuku solo comprueban el nombre de paquete `moe.shizuku.privileged.api`, ShizukuX incluye un **Compat Hub** ligero — una pequeña aplicación complementaria que registra ese nombre de paquete y reenvía las solicitudes de binder/permiso a ShizukuX.

**Si una aplicación de terceros no detecta ShizukuX:**

1. Inicie primero el servicio ShizukuX (método ADB o Root).
2. En la pantalla de inicio, use la tarjeta **Compat Hub** para instalar la aplicación complementaria (incluida en el APK; la instalación depende de un servicio en ejecución, así que inicie el servicio primero).
3. Vuelva a abrir la aplicación de terceros — ahora debería detectar Shizuku y recibir el binder del servicio.

También puede instalar la **variante drop-in**, que se registra directamente como `moe.shizuku.privileged.api` (no la instale junto con el Shizuku original).

<a name="requirements"></a>
## ☑️ Requisitos del sistema

**Mínimo: Android 7+ · Soporte completo hasta Android 17 (SDK 37)**

- **Modo Root**: requiere un dispositivo rooteado (compatible con Magisk / KernelSU / SukiSU / APatch, etc.)
- **Modo de depuración inalámbrica**: Android 11+ y todos los Android TV
- **Modo ADB de PC**: todos los dispositivos
- **Inicio al encender**: solo disponible en modo de depuración inalámbrica o Root

En **Android 16+**, ShizukuX solicita el nuevo permiso de protección de red local para mantener el descubrimiento y emparejamiento de la depuración inalámbrica; en **Android 17**, maneja de forma transparente el cambio de la API oculta `deviceId` para que las aplicaciones autorizadas sigan visibles y las concesiones de permisos sigan funcionando.

<a name="developer-guide"></a>
## 📱 Guía para desarrolladores y documentación

La documentación de las Extra API exclusivas está en [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) (el submódulo `api` de este repositorio apunta a él). Los mantenedores de forks de Shizuku que quieran migrar a ShizukuX pueden seguir [FORK_MIGRATION.md](FORK_MIGRATION.md).

La documentación completa en chino para usuarios finales (conexión al servicio, base de conocimientos, permisos, modo Dhizuku, etc.) está en [docs/zh-CN/index.md](docs/zh-CN/index.md) (actualmente solo en chino).

<a name="repo-layout"></a>
## 🗂️ Estructura del repositorio

| Directorio | Descripción |
|---|---|
| `manager/` | Aplicación principal: tarjetas de inicio, ajustes, gestor de permisos, control del dispositivo, copia/restauración, Watchdog, actualizaciones |
| `server/` | Proceso de servicio privilegiado: ShizukuService y todas las implementaciones Extra API |
| `core/` | Biblioteca central compartida (componentes de UI, temas, recursos de cadenas) |
| `api/` | Submódulo [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) |
| `compat/` | Aplicación complementaria Compat Hub |
| `shell/` | Herramientas de línea de comandos privilegiadas `rish` / `extra` |
| `starter/` | Iniciador del servicio (cadena de arranque ADB / Root / supervisor) |
| `common/` / `app-process/` / `database/` | Código común, envoltorio de proceso independiente, capa de base de datos |
| `docs/` | Documentación (la ayuda integrada y las notas de actualización apuntan aquí) |

<a name="releases"></a>
## 📦 Versiones recientes

| Versión | Tipo | Fecha | Destacados |
|---|---|---|---|
| **v13.7.0.k2016** | Pre-versión | 2026-10-26 | El detenido manual sigue detenido, inicio consciente del root (respaldo isRooted), activación más rápida (~2 min → 9 s), exportación del tutorial de terminal y correcciones de comandos |
| v13.7.0.k2015 | Pre-versión | 2026-10-25 | 13 correcciones de una auditoría de 53 000 líneas, auto-inicio / recuperación de bloqueos reforzados, ajustes reorganizados en General / Permiso / Servicio / Diagnóstico |
| v13.7.0.k2014 | Pre-versión | 2026-10-07 | Compatibilidad de desplazamiento de códigos de transacción AIDL, suite completa de control del dispositivo / copia de seguridad, modo sigiloso, corrección de tema AMOLED, desinstalación de Compat Hub con un toque |
| v13.7.0.r2002 | Estable | 2026-09-22 | Línea estable de v13.7.0 |

Para el registro de cambios completo ver [CHANGELOG.md](CHANGELOG.md) y las [Release Notes](https://github.com/qianyumeng0228/ShizukuX/releases) de cada versión.

<a name="credits"></a>
## 🙏 Agradecimientos y licencia

ShizukuX es un fork comunitario mejorado — ver la nota de herencia al principio de este documento para el linaje y el descargo de responsabilidad.

Gracias a los siguientes contribuyentes y proyectos ascendentes, cuyo trabajo hizo posible ShizukuX:

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — los cimientos de Shizuku y su elegante diseño de API.
- **[thedjchi](https://github.com/thedjchi)** — fork intermedio con mejoras de experiencia y hogar del trabajo de **compatibilidad Android 17 (SDK 37)** del que se portó el soporte A17 de ShizukuX.
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — fuente de la compatibilidad de la API oculta `deviceId` de Android 17 (`Android17Compat` / `InstalledPackagesCompat` capa de reflexión) y del manejo de la protección de red local.
- **[LandonMoran](https://github.com/LandonMoran)** — portó el soporte de Android 17 de Nightzuku al fork thedjchi y lo validó **de extremo a extremo en un dispositivo Android 17 real** (emparejamiento, inicio del servicio, lista de aplicaciones autorizadas), proporcionando la base de verificación en dispositivo real para el port de ShizukuX.
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — la base de código de aShell You que inspiró las funciones de terminal y automatización de shell.
- **[iamr0s](https://github.com/iamr0s)** — Dhizuku para el modo unificado de permiso Device Owner, y AndroidAppProcess para la ejecución de procesos Java independientes.
- **[pascua28](https://github.com/pascua28)** — integración nativa de privilegios Samsung System UID 1000.
- **[kerneldroid](https://github.com/kerneldroid)** — el fork Nightzuku que inspiró nuestra resiliencia de API Android 16/17 (SDK 37) (manejo de `deviceId`) y la modernización de la interfaz.
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — el concepto de privilegios UID 1000 a nivel de sistema más allá de los límites normales.

### Proyectos ascendentes

| Proyecto | Autor | Licencia | Rol |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | Arquitectura fundacional de procesos privilegiados |
| [Shizuku (fork)](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | Fork intermedio con mejoras de experiencia; alberga el trabajo de compatibilidad Android 17 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Fuente de compatibilidad `deviceId` Android 17 + protección de red local |
| [Shizuku (fork)](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | Esquema de ejecución del sistema Samsung UID 1000 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Resiliencia de API Android 16/17 y modernización de la interfaz |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | Equipo ShizukuExt | Apache 2.0 | Concepto de privilegio UID del sistema |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Compartir binder de Device Owner (modo Dhizuku) |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | Envoltorio de proceso Java altamente privilegiado independiente |

### Bibliotecas de código abierto

Este proyecto utiliza las siguientes bibliotecas de código abierto clave (lista completa y textos de licencia en [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md) y [NOTICE](NOTICE)): AndroidX Jetpack, Material Components, Kotlin / Coroutines, RikkaX Libraries, Mavericks, Lottie, Coil, Koin, Timber, libsu, AndroidHiddenApiBypass, Gson, LeakCanary, AboutLibraries, Bouncy Castle, Sentry, SQLite, etc.

## 📃 Licencia

[Apache 2.0](LICENSE)

### Créditos
- Gracias especiales a **AkayamiShurui42** por la investigación de seguridad proactiva y los parches de estabilidad (#239).
- Gracias a **AlexeiCrystal** por localizar el bloqueo de MIUI y proponer el esquema Compat Hub para aplicaciones heredadas (#241, #242).
- Gracias a **ddnexus** y **kai-bash** por señalar la trampa de restablecimiento de fábrica del Device Owner y los conflictos con la copia de seguridad de Google (#237).
- Gracias a **Kevinco1** por los comentarios sobre la detección de aplicaciones de compatibilidad Root (#243).
- Gracias a **aragortsantiago6-beep**, **Scoop2389** (Pixel 9a) y **ConversionRituals** (Xiaomi) por las pruebas en dispositivo Android 16/17, los informes de bloqueo y los registros que impulsaron las correcciones de API ocultas SDK 37 y de protección de red local (#317, #323).
- Gracias a **gmm96** por múltiples sesiones de depuración logcat que finalmente aislaron el error de entrega de binder de Cached Apps Freezer (#371).
- Gracias a **[odorizzioficial](https://github.com/odorizzioficial)** por una traducción completa al portugués brasileño (#409) y un informe detallado sobre los bloqueos del watchdog Samsung «Sleeping apps» (#415).

## 🤝 Contribución y soporte

- Informar problemas y sugerencias: [GitHub Issues](https://github.com/qianyumeng0228/ShizukuX/issues)
- Desarrollo: ver [CONTRIBUTING.md](CONTRIBUTING.md)
- Vulnerabilidades de seguridad: ver [SECURITY.md](SECURITY.md)
- Registro de cambios completo: [CHANGELOG.md](CHANGELOG.md) · [CHANGES.md](CHANGES.md)
