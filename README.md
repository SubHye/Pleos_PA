# Pleos_PA

Pleos Connect MCP: 평소 쓰는 AI와 연결된 외부 서비스를 Pleos 차량 기능까지 확장하는 MCP 기반 연결 서비스. 현대차·기아 차량용 앱 개발 공모전 2인 팀 저장소다.

1차 목표는 외부 AI(ChatGPT·Claude)에서 외부 일정 확인 → 순정 내비 목적지 설정 → ETA 기반 지연 판단 → 필요 시 승인 후 연락을 수행하고, 기존 차량 조회 4개 도구와 연결 중지를 재검증하는 것이다. 충전소·주유소 검색, 경유지 추가, 주행 가능 거리는 필수가 아닌 조건부 기능으로, SDK 지원이 확인되면 포함한다. 제출 데모는 차량 앱에 내장된 MCP 서버에 개발 PC의 AI 클라이언트가 ADB forward로 연결하는 에뮬레이터 환경이며, SDK·AI·외부 서비스 지원은 검증 전제다. Gleo 연동과 차량 직접 제어는 포함하지 않는다.

## 현재 상태

Android 앱에 연결 제어 화면, Foreground Service, MCP 서버(`127.0.0.1:18766/mcp`)가 있다. MCP 서버는 실행 검증을 통과한 도구 20개를 제공하고, 연결을 시작해야 열린다([ADR-0008](docs/decisions/0008-embedded-mcp-server.md)). Vehicle Adapter에는 카탈로그 97개 속성의 호출 표가 있고, 속성별 실행 결과는 카탈로그의 `verification`에 있다. `SMART_GLASS_CONFIG`는 공개 capability API가 없어 읽지 않고, `ETCS_VOICE_GUIDANCE_VOLUME`은 `CAR_TOLL`을 Manifest에서 빼서 `PERMISSION_DENIED`를 받는다.

- COM-12 전에는 PC의 Node.js MCP 서버가 `127.0.0.1:18765`의 HTTP Bridge로 앱을 불렀다. 지금은 둘 다 없다.
- 실제 AI 호출은 Codex로 확인했다(PR #15). Claude 계열은 아직 확인하지 않았다.
- PC 없이 연결하는 경로(COM-15)는 없다.

기존 [PoC](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca)에서 기능 단위로 선택 이전 중이다.

## 시작하기

| 항목 | 기준 |
| --- | --- |
| Gradle / AGP | Wrapper 9.6.0 / 9.5.1 |
| Kotlin | 2.4.21 (AGP 내장 Kotlin을 루트 빌드의 KGP로 지정) |
| Android Studio | AGP 9.5를 지원하는 Rabbit 2 (2026.2.2) 이상 |
| JDK | 25 (Java 컴파일 타깃 11) |
| Android SDK | compile·target 37, min 28, Build Tools 36.0.0 |
| Vehicle SDK | 2.3.2 |
| MCP Kotlin SDK / Ktor | 0.16.0 / 3.5.2 (CIO, stateless Streamable HTTP) |
| AI 클라이언트 | 아래 [AI 클라이언트 연결](#ai-클라이언트-연결) |
| Emulator | `Pleos_Connect_v2.1.0`, API 34, 사용자 10, Vehicle Service 2.3.22 |

```powershell
.\gradlew.bat :spotlessCheck :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :vehicle-sweep:test
```

PR과 main push에서 CI가 같은 검사와 Structurizr 모델 검증을 실행한다.

## 연결 제어

MCP 서버는 사용자가 앱 화면에서 시작해야 열리고, 앱 실행이나 프로세스 재시작만으로는 열리지 않는다. 중지하면 서비스가 끝나고 재개 동작이 있는 알림이 남는다. 알림 권한을 거부하면 화면에서만 제어한다.

- 화면의 "연결됨"은 MCP 서버가 요청을 받을 수 있다는 뜻이며, AI가 연결되었다는 뜻이 아니다.
- 앱은 AI 클라이언트가 조회 도중 연결을 끊어도 알지 못한다. 그 조회는 데드라인 안에 끝나고, 그동안 다른 호출은 `BUSY`를 받는다.
- 앱은 ADB 해제도 알지 못한다. 해제되면 다음 호출이 클라이언트 쪽 연결 실패로 끝난다.

시스템이나 사용자가 프로세스를 종료하면 자동으로 다시 열지 않는다. 다음 실행은 중지 상태로 시작한다.

## AI 클라이언트 연결

앱에서 연결을 시작한 뒤 PC에서 forward를 만든다. 기기 하나를 지정한다. `tcp:18766`이 이미 있거나 `--no-rebind`가 실패하면 다른 작업이 쓰는 포트이므로 덮어쓰지 않는다.

```powershell
adb -s <기기 serial> forward --no-rebind tcp:18766 tcp:18766
adb -s <기기 serial> forward --remove tcp:18766   # 끝나면
```

| 클라이언트 | 등록 | 확인 |
| --- | --- | --- |
| Codex CLI / ChatGPT Desktop 로컬 Codex 호스트 | `~/.codex/config.toml`에 `[mcp_servers.pleos-pa-vehicle]`, `url = "http://127.0.0.1:18766/mcp"` | PR #15에서 Codex CLI 0.166.0으로 확인 |
| Claude 계열 | URL(Streamable HTTP) 방식 등록이 되는지 확인하지 않았다 | 확인하지 않음 |
| ChatGPT 웹 커스텀 MCP 앱 | 공개 HTTPS가 필요하다. loopback 서버에 직접 닿지 못한다 | 대상 아님(COM-15에서 결정) |

토큰이나 환경 변수는 필요 없다. COM-12 전의 stdio 등록(`node mcp/src/server.ts`)과 COM-10 모의 진입점(`serve-com10-mock.ts`)은 Node 서버와 함께 없어졌다. 클라이언트에 남은 설정은 지운다.

## MCP 서버 신뢰 경계

1차는 인증이 없다(COM-12에서 결정, [ADR-0008](docs/decisions/0008-embedded-mcp-server.md)). 개발용 연결이므로 쓰지 않을 때는 앱에서 중지하고 `adb forward`를 제거한다.

| 항목 | 내용 |
| --- | --- |
| 접근 가능 | 기기 안의 다른 프로세스, `adb forward`가 있는 동안 PC의 로컬 프로세스 |
| 수신 범위 | 기기 `127.0.0.1:18766`에만 묶는다. `adb forward`도 PC `127.0.0.1`에서만 수신한다 |
| 검사가 막는 것 | 브라우저식 요청(`Origin`이 있거나 Host가 `127.0.0.1`이 아니면 403), 채택되지 않은 도구, 스키마 밖 인자 |
| 검사가 못 막는 것 | 호출 프로세스의 신원 확인, 같은 형식으로 보내는 로컬 프로세스, 평문 HTTP 관찰, 슬롯 점유로 인한 `BUSY`, 서버 사칭 |
| 서버 사칭 | 앱이 포트를 잡고 있지 않을 때 다른 프로세스가 18766에서 수신하면, 클라이언트는 그 응답을 차량 값으로 받는다. 앱 화면이 연결됨이 아니면 AI가 답한 차량 값을 믿지 않는다 |
| 노출 범위 | 카탈로그의 읽기 값뿐이다. 제어 API가 없고 VIN은 제외된다. 서버가 열려 있으면 INTERNET 권한만 있는 기기 내 앱도 이 앱의 `CAR_*` 권한을 거쳐 값을 읽으며, Android 권한 모델은 이 경로를 막지 못한다 |
| 값이 남는 곳 | 앱은 값을 저장하거나 로그에 쓰지 않는다. 앱 로그는 requestId, query, 결과, 처리 시간뿐이다. 다만 SDK가 조회 값을 logcat에 남긴다(문 위치에서 확인) |

## PoC에서 가져온 코드

[PoC `0d288c8`](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca)에서 구조를 옮기고 계약에 맞게 고쳤다. PoC 저장소에는 LICENSE 파일이 없으며, 재사용 근거는 COM-18 PR에서 저장소 소유자 확인으로 기록한다.

| 신규 파일 | PoC 출처 |
| --- | --- |
| `bridge/ReaderBridgeGateway.kt` (COM-12에서 HTTP 서버 부분은 삭제) | `bridge/DoorBridgeServer.kt`, `bridge/DoorBridgeProtocol.kt` |
| `connection/ConnectionSession.kt` | `bridge/BridgeSession.kt` |
| `connection/BridgeService.kt`, `MainActivity.kt` | `BridgeService.kt`, `MainActivity.kt` |
| `vehicle/VehicleReader.kt`, `vehicle/PleosVehicleSdk.kt`, `vehicle/VehicleProperties.kt`, `vehicle/VehicleSdk.kt` | `vehicle/VehicleStatusReader.kt`, `vehicle/PleosVehicleSdk.kt` |

## 문서

- [작업 지침](AGENTS.md): 협업 흐름, 환경, 검증, 문서 맵
- [아키텍처](docs/architecture/AGENTS.md): Structurizr C4 모델과 Mermaid 시나리오
- [결정 기록](docs/decisions/AGENTS.md): ADR
- [차량 조회 계약](contracts/vehicle-read/CONTRACT.md): 도구, 요청·응답 형식, 요청 생명주기
- [vehicle-sweep](tools/vehicle-sweep/README.md): 에뮬레이터 전수 실행과 카탈로그 판정 근거
- [Linear 팀](https://linear.app/wonsub/team/COM/overview): 요구사항과 진행 관리
  - [공모전 1차 Project](https://linear.app/wonsub/project/pleos-connect-mcp-공모전-1차-29bf62b430ba) · [프로젝트 기획서](https://linear.app/wonsub/document/프로젝트-기획서-e1e6374c0bbb) · [요구사항 정의서](https://linear.app/wonsub/document/요구사항-정의서-1b7e6fcd5721) (둘이 충돌하면 최신 결정을 반영한 요구사항 정의서 우선)
