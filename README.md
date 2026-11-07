# Pleos_PA

Pleos Connect MCP: 평소 쓰는 AI와 연결된 외부 서비스를 Pleos 차량 기능까지 확장하는 MCP 기반 연결 서비스. 현대차·기아 차량용 앱 개발 공모전 2인 팀 저장소다.

1차 목표는 외부 AI(ChatGPT·Claude)에서 외부 일정 확인 → 순정 내비 목적지 설정 → ETA 기반 지연 판단 → 필요 시 승인 후 연락을 수행하고, 기존 차량 조회 4개 도구와 연결 중지를 재검증하는 것이다. 충전소·주유소 검색, 경유지 추가, 주행 가능 거리는 필수가 아닌 조건부 기능으로, SDK 지원이 확인되면 포함한다. 제출 데모는 PC의 Node.js MCP와 ADB 연결을 쓰는 에뮬레이터 환경이며, SDK·AI·외부 서비스 지원은 검증 전제다. Gleo 연동과 차량 직접 제어는 포함하지 않는다.

## 현재 상태

Android 앱에 연결 상태·요청 기록 화면, Car UX 제한 관찰, Foreground Service, `/v3/vehicle-read` Bridge가 있다. Vehicle Adapter에는 카탈로그 97개 속성의 호출 표가 있고, 속성별 실행 결과는 카탈로그의 `verification`에 있다. `SMART_GLASS_CONFIG`는 공개 capability API가 없어 읽지 않고, `ETCS_VOICE_GUIDANCE_VOLUME`은 `CAR_TOLL`을 Manifest에서 빼서 `PERMISSION_DENIED`를 받는다. [MCP 서버](mcp/README.md)는 실행 검증을 통과한 도구 20개를 제공한다. ChatGPT·Claude 호출 검증은 COM-26이다. 기존 [PoC](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca)에서 기능 단위로 선택 이전 중이다.

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

PR과 main push에서 CI가 같은 검사와 Structurizr 모델 검증을 실행한다. 별도 android-ui 작업은 일반 API34 에뮬레이터에서 모의 UI와 실제 프로세스 재시작을 검증하며, Pleos SDK/정책 검수와 구분한다.

## 연결 제어

Bridge는 사용자가 앱 화면에서 시작해야 열리고, 앱 실행이나 프로세스 재시작만으로는 열리지 않는다. 중지하면 서비스가 끝나고 재개 동작이 있는 알림이 남는다. 알림 권한을 거부하면 화면에서만 제어한다.

화면의 "연결됨"은 Bridge가 요청을 받을 수 있다는 뜻이며 AI가 연결되었다는 뜻이 아니다. 조회 중 연결이 끊기면 오류 문구가 나오고 다음 요청을 받으면 사라진다. 앱은 AI 쪽 취소와 ADB 해제를 구분하지 못해 같은 문구를 쓴다. 요청이 없을 때의 ADB 해제는 앱이 알 수 없어 다음 호출의 `ANDROID_UNREACHABLE`로만 나타난다.

시스템이나 사용자가 프로세스를 종료하면 자동으로 다시 열지 않는다. 다음 실행은 중지 상태로 시작한다.

## 차량 화면과 요청 기록

요청 기록은 차량 조회 계약을 검증한 뒤 추가한다. 요청마다 별도 식별자를 사용하므로 중복 requestId, 역순·늦은 응답이 다른 행을 갱신하지 않는다. 수신 시각과 출처를 표시하고, 미지원·알 수 없는 값을 0/false로 채우지 않는다. 클라이언트 인증 정보가 없으므로 AI 이름은 "알 수 없음"이다. 내비 변경·되돌리기는 아직 지원하지 않으며 버튼을 비활성화하고 이유를 표시한다.

현재 화면의 공개 Car UX API를 관찰한다. COM-9에서 운전자 표시 영역·규제 중 화면 정책을 검증하지 못했으므로 실제 앱의 상세 기록 허용은 기본 차단한다. DO=false·제한 0만으로 이 차단을 해제하지 않으며 실행 인자·빌드 플래그로 우회할 수 없다. 검증 후 코드 리뷰를 거쳐 정책을 연결해야 한다. 모의 화면은 별도 검수 정책으로 동작한다. 규제 활성·연결 상실·화면 변경·3초간 관찰 갱신 없음은 상세 기록을 숨기고 짧은 처리 상태와 연결 중지만 유지한다. UX 제한 해제는 정차나 차량 변경 허가의 증거가 아니다. 텍스트 입력과 차량 변경 경로는 없다. 실제 Pleos의 규제 전환 검수는 별도로 필요하다.

기록에는 요청 선택 인자와 검증된 차량 조회 결과만 남긴다. 메일·일정 원문, HTTP 원문, 신원·인증 헤더는 받지 않는다. 최근 100건·인자 4,096자·결과 16,384자 상한과 10건씩 탐색은 검수용 초기값이다. OQ-09의 최종 상한은 실제 기기의 UI·힙 검수 후 정한다. 기록 삭제 후 늦게 도착한 응답은 기록을 복구하지 않는다. Android 화면 상태 저장에도 값과 선택을 맡기지 않는다.

`debug` 전용 검수 화면의 모의 기록·규제·연결 제어는 실제 SDK·Bridge·공용 기록과 분리된다. `.com5` 진단 빌드에만 켜지는 별도 probe는 공용 기록에 모의 값을 추가하고 건수/PID만 출력한다. 기본 debug나 release에는 이 probe가 활성화되지 않는다. COM-22 실제 플랫폼 점검 화면은 공개 Car UX 관찰과 기존 실제 서비스 제어를 사용한다. 정책 허용을 바꾸거나 기어/UX 값을 주입하지 않는다. PermissionTest는 진단 앱의 실제 알림 권한 창에서 거부 후 화면 중지를 검사한다. ServiceTest는 실제 서비스/게시 알림 PendingIntent/소켓 종료를 차량 조회 요청 없이 검사한다. 실제 알림 패널 터치와 규제 전환 검수는 별도다.

Windows 설치·화면 검사·프로세스 재시작은 [실행 절차](scripts/COM5-WINDOWS.md)를 따른다. 키트는 Python 없이 PowerShell에서 실행하며, 기존 앱 삭제 없이 별도 패키지를 사용한다. 소스 빌드는 다음과 같다.

```powershell
.\gradlew.bat -Pcom5Diagnostic=true :app:assembleDebug :app:assembleDebugAndroidTest
```

CI는 일반 API34 에뮬레이터에서 `DriverUiTest`·`BridgeServiceRuntimeTest`·별도 `BridgeHistoryRuntimeTest`·`NotificationPermissionRuntimeTest`와 `scripts/check-com5-device.py`를 실행한다. 공용 메모리 기록을 모의 데이터로 채운 뒤 실제 프로세스를 종료하고 새 PID·기록0·중지 초기 상태를 확인한다. 이는 새 모델 인스턴스 검사와 구분하며, 실제 내비 이전 상태나 Pleos 규제 전환의 증거로 사용하지 않는다. 검사 결과와 처리 시간·Java 힙 측정은 Actions의 `com5-android-ui-evidence` artifact에 남는다.

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
| 값이 남는 곳 | 앱은 검증된 조회 결과와 선택 인자만 프로세스 메모리 기록에 보관한다. 디스크·로그에 쓰지 않고 프로세스 재시작 때 사라진다. 앱 로그는 requestId, query, 결과, 처리 시간뿐이다. 다만 SDK가 조회 값을 logcat에 남긴다(문 위치에서 확인) |

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
