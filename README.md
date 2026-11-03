# Pleos_PA

Pleos Connect MCP: 평소 쓰는 AI와 연결된 외부 서비스를 Pleos 차량 기능까지 확장하는 MCP 기반 연결 서비스. 현대차·기아 차량용 앱 개발 공모전 2인 팀 저장소다.

1차 목표는 외부 AI(ChatGPT·Claude)에서 외부 일정 확인 → 순정 내비 목적지 설정 → ETA 기반 지연 판단 → 필요 시 승인 후 연락을 수행하고, 기존 차량 조회 4개 도구와 연결 중지를 재검증하는 것이다. 충전소·주유소 검색, 경유지 추가, 주행 가능 거리는 필수가 아닌 조건부 기능으로, SDK 지원이 확인되면 포함한다. 제출 데모는 PC의 Node.js MCP와 ADB 연결을 쓰는 에뮬레이터 환경이며, SDK·AI·외부 서비스 지원은 검증 전제다. Gleo 연동과 차량 직접 제어는 포함하지 않는다.

## 현재 상태

Android 앱에 연결 제어 화면, Foreground Service, `/v3/vehicle-read` Bridge가 있다. Vehicle Adapter는 카탈로그의 97개 속성을 읽으며, 속성별 실행 결과는 카탈로그의 `verification`에 있다. [MCP 서버](mcp/README.md)의 `tools/list`는 비어 있으므로 AI에서는 아직 차량 값을 읽을 수 없다. 기존 [PoC](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca)에서 기능 단위로 선택 이전 중이다.

## 시작하기

| 항목 | 기준 |
| --- | --- |
| Gradle / AGP | Wrapper 9.6.0 / 9.4.1 |
| JDK | 25 (Java 컴파일 타깃 11) |
| Android SDK | compile·target 37, min 28, Build Tools 36.0.0 |
| Vehicle SDK | 2.3.2 |
| Node | 24.15.0 (`mcp/.node-version`), TypeScript 소스를 빌드 없이 실행 |
| TypeScript / oxlint / oxfmt | 7.0.2 / 1.90.0 / 0.75.0 ([ADR-0005](docs/decisions/0005-mcp-typescript-and-tooling.md)) |
| MCP SDK / zod | 2.4.0 / 4.2.0 |
| ChatGPT, Claude | 연결 경로는 [mcp/README.md](mcp/README.md), 실제 연결 결과는 COM-19 PR |
| Emulator | `Pleos_Connect_v2.1.0`, API 34, 사용자 10, Vehicle Service 2.3.22 |

```powershell
.\gradlew.bat :spotlessCheck :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
npm --prefix mcp ci
npm --prefix mcp run typecheck
npm --prefix mcp run lint
npm --prefix mcp run format:check
npm --prefix mcp test
```

PR과 main push에서 CI가 같은 검사와 Structurizr 모델 검증을 실행한다.

## 연결 제어

Bridge는 사용자가 앱 화면에서 시작해야 열리고, 앱 실행이나 프로세스 재시작만으로는 열리지 않는다. 중지하면 서비스가 끝나고 재개 동작이 있는 알림이 남는다. 알림 권한을 거부하면 화면에서만 제어한다.

화면의 "연결됨"은 Bridge가 요청을 받을 수 있다는 뜻이며 AI가 연결되었다는 뜻이 아니다. 조회 중 연결이 끊기면 오류 문구가 나오고 다음 요청을 받으면 사라진다. 앱은 AI 쪽 취소와 ADB 해제를 구분하지 못해 같은 문구를 쓴다. 요청이 없을 때의 ADB 해제는 앱이 알 수 없어 다음 호출의 `ANDROID_UNREACHABLE`로만 나타난다.

시스템이나 사용자가 프로세스를 종료하면 자동으로 다시 열지 않는다. 다음 실행은 중지 상태로 시작한다.

## Bridge 신뢰 경계

Bridge는 개발용 연결이고 인증이 없다. 쓰지 않을 때는 앱에서 중지하고 `adb forward`를 제거한다.

| 항목 | 내용 |
| --- | --- |
| 접근 가능 | 기기 안의 다른 프로세스(`adb shell`의 다른 UID에서 응답을 확인), `adb forward`가 있는 동안 PC의 로컬 프로세스 |
| 수신 범위 | 기기 `127.0.0.1:18765`에만 묶고 상대 주소가 loopback이 아니면 닫는다. `adb forward`도 PC `127.0.0.1`에서만 수신한다 |
| 검사가 막는 것 | 브라우저식 요청(Origin 거부, Host 일치, JSON `Content-Type`), 다른 경로와 메서드, 크기 초과, 계약 밖 본문 |
| 검사가 못 막는 것 | 호출 프로세스의 신원 확인, 같은 형식으로 보내는 로컬 프로세스, 평문 HTTP 관찰, 슬롯 점유로 인한 `BUSY`, Bridge 사칭 |
| Bridge 사칭 | 앱이 포트를 잡고 있지 않을 때 다른 프로세스가 18765에서 수신하면 Node는 계약 형식의 응답과 `source`를 그대로 믿는다. 앱 화면이 연결됨이 아니면 AI가 답한 차량 값을 믿지 않는다 |
| 노출 범위 | 카탈로그의 읽기 값뿐이다. 제어 API가 없고 VIN은 제외된다. Bridge가 열려 있으면 INTERNET 권한만 있는 기기 내 앱도 이 앱의 `CAR_*` 권한을 거쳐 값을 읽으며, Android 권한 모델은 이 경로를 막지 못한다 |
| 값이 남는 곳 | 앱은 값을 저장하거나 로그에 쓰지 않는다. 앱 로그는 requestId, query, 결과, 처리 시간뿐이다. 다만 SDK가 조회 값을 logcat에 남긴다(문 위치에서 확인) |

## PoC에서 가져온 코드

[PoC `0d288c8`](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca)에서 구조를 옮기고 계약에 맞게 고쳤다. PoC 저장소에는 LICENSE 파일이 없으며, 재사용 근거는 COM-18 PR에서 저장소 소유자 확인으로 기록한다.

| 신규 파일 | PoC 출처 |
| --- | --- |
| `bridge/BridgeServer.kt`, `bridge/BridgeHttp.kt`, `bridge/StrictRequestSyntax.kt` | `bridge/DoorBridgeServer.kt`, `bridge/DoorBridgeProtocol.kt` |
| `connection/ConnectionSession.kt` | `bridge/BridgeSession.kt` |
| `connection/BridgeService.kt`, `MainActivity.kt` | `BridgeService.kt`, `MainActivity.kt` |
| `vehicle/VehicleReader.kt`, `vehicle/PleosVehicleSdk.kt`, `vehicle/VehicleProperties.kt`, `vehicle/VehicleSdk.kt` | `vehicle/VehicleStatusReader.kt`, `vehicle/PleosVehicleSdk.kt` |

## 문서

- [작업 지침](AGENTS.md): 협업 흐름, 환경, 검증, 문서 맵
- [아키텍처](docs/architecture/AGENTS.md): Structurizr C4 모델과 Mermaid 시나리오
- [결정 기록](docs/decisions/AGENTS.md): ADR
- [차량 조회 계약](contracts/vehicle-read/CONTRACT.md): 도구, wire 형식, 요청 생명주기
- [MCP 서버](mcp/README.md): 실행 환경, ADB·클라이언트 연결
- [Linear 팀](https://linear.app/wonsub/team/COM/overview): 요구사항과 진행 관리
  - [공모전 1차 Project](https://linear.app/wonsub/project/pleos-connect-mcp-공모전-1차-29bf62b430ba) · [프로젝트 기획서](https://linear.app/wonsub/document/프로젝트-기획서-e1e6374c0bbb) · [요구사항 정의서](https://linear.app/wonsub/document/요구사항-정의서-1b7e6fcd5721) (둘이 충돌하면 최신 결정을 반영한 요구사항 정의서 우선)
