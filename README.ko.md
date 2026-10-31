<div align="center">

# ShizukuX

[简体中文](./README.md) · [English](./README.en.md) · [繁體中文](./README.zh-TW.md) · [日本語](./README.ja.md) · [Deutsch](./README.de.md) · [Français](./README.fr.md) · [Español](./README.es.md) · [Português](./README.pt-PT.md) · [Italiano](./README.it.md) · **한국어** · [Português (Brasil)](./README.pt-BR.md)

**고급 사용자를 위한 Android 권한 프로세스 관리자**

ShizukuX는 [Shizuku](https://github.com/RikkaApps/Shizuku)의 커뮤니티 개선 포크로, [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku)와 [ShizukuPlus](https://github.com/qianyumeng0228/ShizukuPlus)를 기반으로 발전했으며, 원본 프로젝트의 모든 기능을 계승하면서 더 넓은 사용자를 위한 현지화와 경험 개선을 더했습니다.

Shizuku는 **ADB / Root**로 시작된 권한 프로세스를 통해 일반 앱이 시스템 수준 API를 직접 호출할 수 있게 해줍니다. ShizukuX는 100% 호환성을 유지하면서 고급 사용자와 개발자를 위한 실용적인 기능을 추가합니다.

[![Stars](https://img.shields.io/github/stars/qianyumeng0228/ShizukuX?style=for-the-badge&color=bfb330&labelColor=807820)](https://github.com/qianyumeng0228/ShizukuX/stargazers)
[![Downloads](https://img.shields.io/github/downloads/qianyumeng0228/ShizukuX/total?style=for-the-badge&color=bf7830&labelColor=805020)](https://github.com/qianyumeng0228/ShizukuX/releases)
[![Latest Release](https://img.shields.io/github/v/release/qianyumeng0228/ShizukuX?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest)](https://github.com/qianyumeng0228/ShizukuX/releases/latest)
[![License](https://img.shields.io/github/license/qianyumeng0228/ShizukuX?style=for-the-badge&color=30bf60&labelColor=208040)](LICENSE)

> **계승 안내**: ShizukuX는 ShizukuPlus(qianyumeng0228)의 개명·중국어 현지화 연장선이며, ShizukuPlus 자체는 thejaustin/ShizukuPlus와 thedjchi/Shizuku의 포크로 최종적으로 RikkaApps의 Shizuku 프로젝트로 거슬러 올라갑니다. ShizukuX는 독립적으로 유지 관리되는 커뮤니티 포크이며 **RikkaApps/Shizuku, thedjchi/Shizuku, thejaustin/ShizukuPlus 및 그 유지 관리자와는 아무런 소속·추천·제휴 관계가 없습니다**. 모든 업스트림 저작권 및 크레딧 표시는 완전히 보존됩니다([LICENSE](LICENSE), [NOTICE](NOTICE), [CHANGES.md](CHANGES.md) 참조).

</div>

## 📑 목차

- [🚀 빠른 시작](#quickstart)
- [⬇️ 다운로드](#download)
- [✨ 핵심 기능](#features)
- [🛡️ 안정성과 보안](#security)
- [🚀 Extra API 확장 기능](#extra-api)
- [🔌 타사 앱 호환성](#compat)
- [☑️ 시스템 요구 사항](#requirements)
- [📱 개발자 가이드 및 문서](#developer-guide)
- [🗂️ 저장소 구조](#repo-layout)
- [📦 최근 릴리스](#releases)
- [🙏 감사 인사와 라이선스](#credits)

<a name="quickstart"></a>
## 🚀 빠른 시작

1. [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases)에서 최신 **표준(Standard)** APK를 다운로드해 설치합니다(독립 패키지명 `xyz.shizuku.extra.api`, 원본 Shizuku와 공존 가능. **Drop-In** 버전은 원본 패키지명으로 설치되어 교체용이므로 공존할 수 없습니다).
2. 앱을 열고 **무선 디버깅 / PC ADB / Root** 중 하나로 서비스를 시작합니다(활성화 약 9초).
3. 앱 내에서 Shizuku 권한을 부여할 앱을 허용하면 바로 사용할 수 있습니다.

> 자세한 단계별 안내, 흔한 오류와 문제 해결은 [중국어 문서](docs/zh-CN/index.md)(현재 중국어만 제공)를 참조하세요.

<a name="download"></a>
## ⬇️ 다운로드

최신 버전은 [GitHub Releases](https://github.com/qianyumeng0228/ShizukuX/releases)에서 받으세요. 각 릴리스에는 다음 세 가지 APK가 포함됩니다:

| 변형 | 패키지명 | 용도 |
|---|---|---|
| **표준(Standard)** | `xyz.shizuku.extra.api` | 독립 패키지명으로 새로 설치. 다른 Shizuku 빌드와 공존 가능 |
| **Drop-In** | `moe.shizuku.privileged.api` | 원본 Shizuku를 직접 교체. Shizuku API를 쓰는 모든 앱과 호환(원본 Shizuku와 동시 설치 금지) |
| **Compat-Hub** | `moe.shizuku.privileged.api` | 원본 패키지명만 감지하는 타사 앱이 ShizukuX를 볼 수 있게 해주는 경량 컴패니언(표준 APK에 포함, 앱 내에서 설치/제거) |

- 정식 버전은 **`r` 시리즈**(예: `v13.7.0.r2002`), 프리릴리스는 **`k` 시리즈**(예: `v13.7.0.k2016`)로 표시되며, 둘 다 동일한 세 APK를 포함합니다.
- 각 버전의 자세한 내용은 릴리스 노트, 전체 이력은 [CHANGELOG.md](CHANGELOG.md)를 참조하세요.

<a name="features"></a>
## ✨ ShizukuX 핵심 기능

*   **유니버설 권한 제공자**: **Root**, **ADB Shell**, **Dhizuku(기기 소유자)** 세 가지 권한 소스를 하나의 인터페이스로 통합.
*   **OneUI 8+ 테마 수정**: Hex Installer, Substratum 같은 테마 엔진이 Android 16/17과 OneUI 8+에서 계속 동작하도록 유지.
*   **Dhizuku 모드**: 시스템 Device Owner Binder를 Shizuku 권한이 있는 모든 앱과 공유 — ADB로 설정 가능, Root 불필요. 전용 **권한 수준** 페이지에서 Shell / Root / DO 세 수준을 통합 관리.
*   **세밀한 권한 관리자**: 앱별 Shizuku 권한을 일괄 부여/회수하는 전용 화면. 홈 카드와 연동.
*   **기기 제어**: 연결·디스플레이·오디오·전원·시스템 설정 5개 범주 18개 작업(비행기 모드, 자동 밝기, 자동 회전, 애니메이션 배율, 재시작/종료 등). ADB 모드에서는 위험 항목을 자동으로 비활성화하고 안내.
*   **백업 및 복원**: SAF로 공용 디렉터리 내보내기, 일괄 백업/복원(OBB 데이터 포함), 내부 데이터(`run-as` + tar) 복원, 복원 전 자동 스냅샷과 복원 이력.
*   **사용자 정의 제스처**: 왼쪽/오른쪽 스와이프, 길게 누르기 등을 앱별로 설정 가능.
*   **앱 내 업데이트 로그**: 업데이트 후 앱을 벗어나지 않고 새 기능 확인.
*   **일괄 관리**: 여러 앱을 선택해 권한 부여/회수 또는 숨기기를 한 번에 실행.
*   **활동 로그**: API 호출과 `su` 브리지 명령을 앱 아이콘과 함께 실시간 감사·갱신.
*   **Root 호환 허브**: 레거시 Root 앱용 대시보드. 세밀한 모듈 제어(Magisk 위장, 자동 허용, 파일 가로채기 등) 지원.
*   **유니버설 SU 자동화**: 원터치 '매직 설정'으로 설치된 모든 Root 앱을 ShizukuX SU 브리지로 연결.
*   **서비스 진단**: 서비스 시작 문제(삼성 Auto Blocker 포함)를 진단·수리하고 크래시 스택을 원터치 내보내기.
*   **스텔스 모드(흔적 숨기기)**: `IPackageManager` 쿼리를 가로채 시스템 앱 목록에서 ShizukuX 자체를 숨김.
*   **개인화 제어**: 굵은 글꼴, 확장 헤더, 모서리 반경 오버라이드. 애니메이션과 햅틱은 시스템 설정을 따라감.
*   **AMOLED 순수 블랙 테마**: 9개 surface token을 모두 수정해 순수 블랙 모드에서 카드가 회색으로 보이지 않음.
*   **배터리 최적화 실시간 감지**: 시스템 설정에서 돌아오면 즉시 재확인. MIUI / HyperOS의 '절전 정책'도 통일 판정해 낡은 배너가 남지 않음.
*   **내장 기능 가이드**: 모든 확장 기능에 설명 아이콘이 있어 쉬운 말로 목적을 설명.
*   **빠른 설정 타일**: 알림 창에서 서비스 상태 확인·전환(비행기 모드, 애니메이션, 밝기, 회전 등 기기 제어 타일 포함).

<a name="security"></a>
## 🛡️ 안정성과 보안

*   **타사 클라이언트 호환(AIDL 트랜잭션 코드 오프셋)**: 오프셋 AIDL 트랜잭션 코드를 쓰는 공식 v13.7+ 및 LSPatch 계열 클라이언트와 호환 — 권한 요청이 다른 호출로 오인되지 않음.
*   **ADB 프록시 핸드셰이크 인증**: 15555 포트의 로컬 ADB 프록시가 RSA 서명(즉, 개인 키 보유)을 검증하고 호출자 UID를 바인딩. 위조 공개 키는 통과할 수 없으며, 타사 클라이언트의 와이어 프로토콜은 변경 불필요.
*   **크래시 복구 체인 강화**: 무선 디버깅 시작 전 전제 조건을 프로브하고, TCP 포트 미설정 시 root 시작으로 폴백. 둘 다 불가하면 명확히 안내. 연속 크래시에 따른 저하가 영구적이지 않음 — 15분 쿨다운 후 자동 재시작을 재무장.
*   **isRooted 감지 수정**: SELinux Enforcing의 KernelSU / SukiSU 환경과 호환 — 파일 stat 외에 libsu 인증 기록(5분 TTL)도 참조해 루팅된 기기를 루팅 안 됨으로 오판하지 않음.
*   **HyperOS / MIUI 패키지 가시성 수정**: 앱 목록을 서버 IPC(shell-uid PackageManager)로 획득해 HyperOS 패키지 가시성 제한으로 인한 빈 목록을 해결.
*   **서버 측 강화**: 셸 주입 방어(모든 진입점에서 패키지명 정규식 검증, `/`·`..`·공백 거부), APK Signature Scheme v2 서명 정확성 수정, 대용량 APK 스트리밍 서명으로 OOM 방지, 동결 패키지 집합 캐시(`isAppFrozen`은 O(1)).
*   **시작 가속**: 무선 디버깅이 mDNS 탐색을 기다리지 않고 `service.adb.tls.port`에 직접 연결 — 시작이 약 2분에서 약 9초로 단축.
*   **수동 중지 시 재시작 안 함**: 서비스를 중지하면 starter supervisor도 함께 중지되어 '중지를 눌렀는데 5초 후 부활'하는 일이 없음.
*   **배터리 친화적**: 애니메이션은 시스템 모션 설정을 따르고, 메트릭 폴링을 제한하며, 다운로드 알림은 퍼센트 단위로 스로틀링하고, 유휴 상태에서 프로세스를 상주시키지 않음.

<a name="extra-api"></a>
## 🚀 Extra API 확장 기능

ShizukuX는 원본 Shizuku에 **없는** 전용 시스템 인터페이스를 제공합니다. 고급 자동화와 도구용입니다:

*   **AICore Extra 자동화 브리지**: AI 도구용 Root 불필요·특권 UI 자동화(계층 덤프, 탭/스와이프). ([도입 기록](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **AVF(가상 머신) 관리자**: GPU 가속이 포함된 격리된 Linux/Microdroid VM 실행. ([도입 기록](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **특권 스토리지 프록시**: 제한 경로(`/data/data/`, `/data/app/`)에 대한 인증된 접근. 백업과 파일 관리용. ([도입 기록](https://github.com/qianyumeng0228/ShizukuPlus/commit/c8e962f6))
*   **기기 위장**(설정의 *Spoof Device Identity*): 시스템에 다른 기기 정보를 제시해 기종별 제한 우회. ([도입 기록](https://github.com/qianyumeng0228/ShizukuPlus/commit/11867f44))
*   **스마트 브리지**(*AI Core Extra*): 특권 NPU 스케줄링과 화면 컨텍스트 인텔리전스. ([도입 기록](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **Window Manager Extra**: 자유 창 강제 리사이즈, 플로팅 버블 바와 탄성 오버레이 관리. ([도입 기록](https://github.com/qianyumeng0228/ShizukuPlus/commit/e9bd1187))
*   **시스템 테마 브리지**(*Overlay Manager Extra*): Root 불필요 특권 Overlay 관리. 테마 적용용(예: Hex Installer). ([도입 기록](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **네트워크 & DNS 거버너**: 프라이빗 DNS와 방화벽 라우팅을 관리해 Root 없는 광고 차단 실현. ([도입 기록](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **심층 프로세스 제어**(*Activity Manager Extra*): 프로세스 관리자가 앱을 더 적극적으로 종료하고 대기 버킷 설정 가능. ([도입 기록](https://github.com/qianyumeng0228/ShizukuPlus/commit/55f6b7c7))
*   **연속성 브리지**: 여러 ShizukuX 기기 간 상태와 작업을 안전하게 전송. ([도입 기록](https://github.com/qianyumeng0228/ShizukuPlus/commit/20cf14f7))

## 🛠️ 백포트 및 성능 최적화

코드 한 줄도 수정하지 않고 일반 Shizuku 앱을 더 빠르고 호환성 있게 만듭니다:

*   **투명 셸 인터셉터**: 흔한 `pm`, `am`, `settings` 명령을 더 빠른 네이티브 API로 라우팅.
*   **로컬 ADB 프록시**: 15555 포트에서 ADB 서버를 에뮬레이션해 무선 디버깅을 끈 후에도 레거시 앱이 Shizuku를 계속 사용 가능.
*   **SU 브리지**: 사용자 정의 Root 경로를 지원하는 Root 없는 앱을 위한 Shizuku 기반 `su` 대안.
*   **`extra` 명령줄 도구**: `rish` 안에서 사용 가능한 특권 CLI.
*   **동적 앱 데이터베이스**: GitHub에서 앱 설명과 제안을 실시간 동기화해 UI를 최신 상태로 유지.

## ⚙️ 모듈식 제어

ShizukuX의 모든 기능은 선택 사항입니다. 설정의 **Extra 기능** 범주에서 켜고 끌 수 있습니다:

*   투명 셸 인터셉트
*   개별 Extra API(AVF, 스토리지, 스마트 등)
*   홈 카드 표시
*   활동 로그

<a name="compat"></a>
## 🔌 타사 앱 호환성

ShizukuX는 독립 패키지명(`xyz.shizuku.extra.api`)으로 설치되어 원본 Shizuku와 공존할 수 있습니다. 대부분의 Shizuku 앱은 `moe.shizuku.privileged.api` 패키지명만 검사하므로, ShizukuX는 이 패키지명을 등록하고 binder/권한 요청을 ShizukuX로 전달하는 경량 **호환 허브(Compat Hub)** 를 포함합니다.

**타사 앱이 ShizukuX를 감지하지 못할 때:**

1. 먼저 ShizukuX 서비스를 시작합니다(ADB 또는 Root 방식).
2. 홈 화면의 **호환 허브** 카드에서 컴패니언 앱을 설치합니다(APK에 포함됨. 설치는 실행 중인 서비스에 의존하므로 먼저 서비스를 시작하세요).
3. 타사 앱을 다시 열면 Shizuku를 감지하고 서비스 binder를 받을 수 있습니다.

**직접 교체(drop-in) 버전**을 설치할 수도 있습니다. 이 버전은 `moe.shizuku.privileged.api`로 직접 등록됩니다(원본 Shizuku와 동시 설치하지 마세요).

<a name="requirements"></a>
## ☑️ 시스템 요구 사항

**최소: Android 7+ · 전체 지원: Android 17(SDK 37)까지**

- **Root 모드**: 루팅된 기기 필요(Magisk / KernelSU / SukiSU / APatch 등 호환)
- **무선 디버깅 모드**: Android 11+ 및 모든 Android TV
- **PC ADB 모드**: 모든 기기
- **부팅 시 자동 시작**: 무선 디버깅 또는 Root 모드에서만 가능

**Android 16+**에서는 무선 디버깅의 탐색과 페어링을 유지하기 위해 새로운 로컬 네트워크 보호 권한을 요청합니다. **Android 17**에서는 숨겨진 API `deviceId` 변경을 투명하게 처리해 권한이 부여된 앱의 가시성과 권한 부여가 계속 동작하게 합니다.

<a name="developer-guide"></a>
## 📱 개발자 가이드 및 문서

전용 Extra API 문서는 [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API)(이 저장소의 `api` 서브모듈이 가리킴)에 있습니다. Shizuku 포크 유지 관리자로 ShizukuX로 마이그레이션하려는 경우 [FORK_MIGRATION.md](FORK_MIGRATION.md)를 참조하세요.

최종 사용자용 완전한 중국어 문서(서비스 연결, 지식 베이스, 권한, Dhizuku 모드 등)는 [docs/zh-CN/index.md](docs/zh-CN/index.md)(현재 중국어만 제공)에 있습니다.

<a name="repo-layout"></a>
## 🗂️ 저장소 구조

| 디렉터리 | 설명 |
|---|---|
| `manager/` | 메인 앱: 홈 카드, 설정, 권한 관리자, 기기 제어, 백업/복원, Watchdog, 업데이트 |
| `server/` | 특권 서비스 프로세스: ShizukuService와 모든 Extra API 구현 |
| `core/` | 공유 코어 라이브러리(UI 컴포넌트, 테마, 문자열 리소스) |
| `api/` | [ShizukuX-API](https://github.com/qianyumeng0228/ShizukuX-API) 서브모듈 |
| `compat/` | 호환 허브(Compat Hub) 컴패니언 앱 |
| `shell/` | `rish` / `extra` 특권 명령줄 도구 |
| `starter/` | 서비스 스타터(ADB / Root / supervisor 시작 체인) |
| `common/` / `app-process/` / `database/` | 공통 코드, 독립 프로세스 래퍼, 데이터베이스 계층 |
| `docs/` | 문서(앱 내 도움말과 업데이트 노트가 여기를 참조) |

<a name="releases"></a>
## 📦 최근 릴리스

| 버전 | 유형 | 날짜 | 주요 내용 |
|---|---|---|---|
| **v13.7.0.k2016** | 프리릴리스 | 2026-10-26 | 수동 중지 시 재시작 안 함, root 시작과 권한 상태 연동(isRooted 실측 폴백), 시작 가속(약 2분→9초), 터미널 튜토리얼 내보내기 및 명령 수정 |
| v13.7.0.k2015 | 프리릴리스 | 2026-10-25 | 저장소 전체 5.3만 줄 검토로 13건 수정, 무선 디버깅 자동 시작 / 크래시 복구 체인 강화, 설정을 '일반/권한/서비스/진단'으로 재구성 |
| v13.7.0.k2014 | 프리릴리스 | 2026-10-07 | 타사 클라이언트 AIDL 트랜잭션 코드 오프셋 호환, 기기 제어 / 백업 전체 세트, 스텔스 모드, AMOLED 테마 수정, 호환 허브 원터치 제거 |
| v13.7.0.r2002 | 정식 | 2026-09-22 | v13.7.0 정식 릴리스 라인 |

전체 변경 기록은 [CHANGELOG.md](CHANGELOG.md)와 각 버전의 [Release Notes](https://github.com/qianyumeng0228/ShizukuX/releases)를 참조하세요.

<a name="credits"></a>
## 🙏 감사 인사와 라이선스

ShizukuX는 커뮤니티 주도 개선 포크입니다. 계승 관계와 면책 조항은 문서 상단의 '계승 안내'를 참조하세요.

ShizukuX를 가능하게 한 업스트림 기여자와 프로젝트에 감사드립니다:

- **[RikkaApps / Rikka](https://github.com/RikkaApps)** — Shizuku의 기반 프로젝트와 우아한 API 설계.
- **[thedjchi](https://github.com/thedjchi)** — 경험 개선이 포함된 중간 포크이자 **Android 17(SDK 37) 호환성** 작업의 본거지. ShizukuX의 A17 지원이 여기서 포팅됨.
- **[kerneldroid / Nightzuku](https://github.com/kerneldroid/Nightzuku)** — Android 17 숨겨진 API `deviceId` 호환(`Android17Compat` / `InstalledPackagesCompat` 리플렉션 계층)과 로컬 네트워크 보호 처리의 원천.
- **[LandonMoran](https://github.com/LandonMoran)** — Nightzuku의 Android 17 지원을 thedjchi 포크에 포팅하고 **실제 Android 17 기기에서 종단 간 검증**(페어링, 서비스 시작, 권한 부여 앱 목록)을 수행. ShizukuX 포팅의 실기 검증 기반을 제공.
- **[Muntashir Akon](https://github.com/MuntashirAkon)** — 터미널과 셸 자동화 기능의 영감이 된 aShell You 코드베이스.
- **[iamr0s](https://github.com/iamr0s)** — 통합 Device Owner 권한 모드를 위한 Dhizuku와 독립 Java 프로세스 실행용 AndroidAppProcess 제공.
- **[pascua28](https://github.com/pascua28)** — 네이티브 삼성 System UID 1000 권한 통합.
- **[kerneldroid](https://github.com/kerneldroid)** — Android 16/17(SDK 37) 숨겨진 API 탄력성(`deviceId` 대응)과 UI 현대화의 영감이 된 Nightzuku 포크.
- **[ShizukuExt-SystemUID](https://github.com/ShizukuExt)** — 일반적인 한계를 넘어선 시스템 수준 UID 1000 권한의 개념.

### 업스트림 프로젝트

| 프로젝트 | 저자 | 라이선스 | 역할 |
|---------|--------|---------|------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | RikkaApps / Rikka | Apache 2.0 | 기반 특권 프로세스 아키텍처 |
| [Shizuku(포크)](https://github.com/thedjchi/Shizuku) | thedjchi | Apache 2.0 | 경험 개선이 포함된 중간 포크. ShizukuX가 적응한 Android 17 호환 작업 보유 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 17 `deviceId` + 로컬 네트워크 보호 호환의 원천 |
| [Shizuku(포크)](https://github.com/pascua28/Shizuku) | pascua28 | Apache 2.0 | 삼성 UID 1000 시스템 실행 방식 |
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | kerneldroid | Apache 2.0 | Android 16/17 API 탄력성과 UI 현대화 |
| [ShizukuExt-SystemUID](https://github.com/ShizukuExt) | ShizukuExt 팀 | Apache 2.0 | 시스템 UID 권한 개념 |
| [Dhizuku](https://github.com/iamr0s/Dhizuku) | iamr0s | Apache 2.0 | Device Owner binder 공유(Dhizuku 모드) |
| [AndroidAppProcess](https://github.com/iamr0s/AndroidAppProcess) | iamr0s | LGPL-3.0 | 독립 고특권 Java 프로세스 래퍼 |

### 오픈소스 라이브러리

이 프로젝트는 다음 주요 오픈소스 라이브러리를 사용합니다(전체 목록과 각 라이선스 전문은 [OPEN_SOURCE_LICENSES.md](OPEN_SOURCE_LICENSES.md)와 [NOTICE](NOTICE) 참조): AndroidX Jetpack, Material Components, Kotlin / Coroutines, RikkaX Libraries, Mavericks, Lottie, Coil, Koin, Timber, libsu, AndroidHiddenApiBypass, Gson, LeakCanary, AboutLibraries, Bouncy Castle, Sentry, SQLite 등.

## 📃 라이선스

[Apache 2.0](LICENSE)

### 크레딧
- **AkayamiShurui42** 님의 적극적인 보안 연구와 안정성 패치에 특히 감사드립니다(#239).
- **AlexeiCrystal** 님의 MIUI 크래시 위치 확인과 레거시 앱용 Compat Hub 방식 제안에 감사드립니다(#241, #242).
- **ddnexus** 님과 **kai-bash** 님의 Device Owner 공장 초기화 함정과 Google 백업 충돌 지적에 감사드립니다(#237).
- **Kevinco1** 님의 Root 호환 앱 감지에 대한 피드백에 감사드립니다(#243).
- **aragortsantiago6-beep** 님, **Scoop2389** 님(Pixel 9a), **ConversionRituals** 님(샤오미)의 실기 Android 16/17 테스트, 크래시 보고서와 로그로 SDK 37 숨겨진 API 및 로컬 네트워크 보호 호환 수정이 진행되었습니다(#317, #323).
- **gmm96** 님의 여러 차례 logcat 디버깅으로 마침내 Cached Apps Freezer의 binder 전달 버그가 특정되었습니다(#371).
- **[odorizzioficial](https://github.com/odorizzioficial)** 님의 완전한 브라질 포르투갈어 번역(#409)과 삼성 'Sleeping apps' 워치독 동결 상세 보고(#415)에 감사드립니다.

## 🤝 기여와 지원

- 문제 및 제안 보고: [GitHub Issues](https://github.com/qianyumeng0228/ShizukuX/issues)
- 개발 참여: [CONTRIBUTING.md](CONTRIBUTING.md) 참조
- 보안 취약점 신고: [SECURITY.md](SECURITY.md) 참조
- 전체 변경 기록: [CHANGELOG.md](CHANGELOG.md) · [CHANGES.md](CHANGES.md)
