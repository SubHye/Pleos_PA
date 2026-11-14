# Android 소스

Pleos Connect에서 실행되는 차량 앱이다. 앱 안의 MCP 서버(`127.0.0.1:18766/mcp`)가 AI의 도구 호출을 받아 차량 조회와 내비 조회·변경을 실행한다. 도구의 이름·입출력·오류는 [차량 조회 계약](../../contracts/vehicle-read/CONTRACT.md)과 [내비 계약](../../contracts/navigation/CONTRACT.md)이 원천이다.

## 구조

Kotlin 소스는 `main/java/com/example/pleos_pa/` 아래에 있다. 표의 앞 일곱 줄은 그 아래의 위치다.

| 위치 | 맡는 일 |
| --- | --- |
| `MainActivity.kt`, `ui/` | 차량 화면. 연결 시작·중지 버튼 하나, 연결 상태, AI 요청과 앱 응답을 대화 형식 카드로 보이기(`ChatCards`, `ChatViews`), Car UX 제한에 따른 값 숨김 |
| `connection/` | Foreground Service, 알림, 세션과 명령 순서 |
| `mcp/` | MCP 서버와 도구. 입력 스키마와 인자 검사, 결과 형식. `RecordedNavigationTool`은 내비 도구 호출과 끝난 상태를 화면 기록에 넘긴다 |
| `bridge/` | 도구가 조회기를 부르는 게이트웨이와 응답 형식 |
| `vehicle/` | Vehicle SDK 경계(`VehicleSdk`), 조회 슬롯, 속성 호출 표 |
| `navigation/` | NaviHelper 경계(`NaviSdk`), 조회 슬롯, 목적지 설정, 경유지 변경, 변경 작업 조정기(`NavTaskCoordinator`), 주행 규제 가드(`CarNavigationGuard`) |
| `contract/` | 계약의 요청·응답 모델과 검증, 카탈로그, 고정 장소 목록 |
| `main/keepRules/rules.keep` | release 빌드의 R8 보존 규칙 |
| `debug/` | 모의 화면과 진단 화면 |
| `test/` | JVM 테스트. SDK 대역과 공용 fixture를 쓴다 |
| `androidTest/` | 기기 테스트. 별도 `.com5` 패키지에서 실행한다 |

기능을 추가하면 이 표를 함께 갱신한다.

## 지킬 것

- SDK는 `vehicle/`과 `navigation/`의 경계 인터페이스로만 부른다([ADR-0008](../../docs/decisions/0008-embedded-mcp-server.md)). `contract/`에는 SDK와 Android 의존을 넣지 않는다.
- 도구는 카탈로그에서 `adopted`인 것만 올린다. 도구를 더하거나 바꾸면 계약 문서, 카탈로그, 공용 fixture, 대조 테스트를 같은 PR에서 고친다.
- 내비 변경은 모두 `NavTaskCoordinator` 하나를 거친다. 한 번에 하나만 실행하고 처리 중에는 `BUSY`를 돌려준다.
- 내비 변경 전에 `CarNavigationGuard`로 주행 규제를 확인하고 SDK 호출 직전에 다시 확인한다. 규제 중이거나 상태를 확인하지 못하면 막는다. 규제가 풀려도 자동으로 실행하지 않는다.
- 변경한 뒤에는 다시 조회해 반영을 확인한다. 반영을 확인하지 못하면 내비 계약의 결과 불명으로 돌려준다. 성공, 실패, 취소로 단정하지 않고 자동으로 재시도하지 않는다.
- 미지원이거나 알 수 없는 값을 0이나 false로 채우지 않는다.
- MCP 서버는 사용자가 화면에서 시작할 때만 연다. 앱 실행이나 프로세스 재시작으로 자동으로 열지 않는다.
- 서버는 `127.0.0.1`에만 묶는다. `Origin`이 있거나 Host가 `127.0.0.1`이 아닌 요청은 403으로 거절한다. 인증은 없다.
- VIN과 제어 API는 도구로 내놓지 않는다.
- 요청 기록은 메모리에만 두고 디스크와 로그에 쓰지 않는다. 차량 조회는 요청의 선택 인자와 검증된 결과만 남기며, MCP 원문, 인증 헤더, SDK 오류 문장은 남기지 않는다. 내비 도구는 도구 이름과 끝난 상태만 남기고 인자, 장소, 결과 글은 남기지 않는다.
- 화면은 최근 요청 5건의 도구 제목과 끝난 상태만 보이고 기록을 넘겨 보지 않는다. 차량 값이 든 줄(`headline`)은 기본으로 막혀 있다. 실행 인자나 빌드 플래그로 풀지 않는다.
- `debug/`의 모의 화면은 실제 SDK, MCP 서버, 공용 기록과 분리해 둔다. 진단 화면은 값을 주입하거나 바꾸지 않는다.

## 알아 둘 것

- 읽을 수 있는 속성은 카탈로그의 97개다. `SMART_GLASS_CONFIG`는 공개 capability API가 없어 읽지 않는다. `CAR_TOLL` 권한은 Manifest에 없어 `ETCS_VOICE_GUIDANCE_VOLUME`은 `PERMISSION_DENIED`가 된다.
- `ui/NavTaskHistory`는 내비 작업 상태를 요청 기록에 전달하는 어댑터다. 아직 실제 내비 실행 경로에 연결되지 않았다. 화면의 내비 카드는 `mcp/RecordedNavigationTool`이 만든다.
- 클라이언트가 조회 도중 연결을 끊거나 `adb forward`가 해제돼도 앱은 알지 못한다. 그 조회는 데드라인까지 슬롯을 쓰고, 그동안 다른 호출은 `BUSY`를 받는다.
- 화면의 "연결됨"은 서버가 요청을 받을 수 있다는 뜻이며, AI가 연결됐다는 뜻이 아니다.
- 서버가 열려 있는 동안에는 같은 기기의 다른 앱과 `adb forward`가 있는 PC의 프로그램도 조회 값을 읽을 수 있다. SDK는 조회 값을 logcat에 남긴다.

## 실행과 검증

- AI 클라이언트를 붙여 보는 절차는 [README의 데모 실행](../../README.md#데모-실행start)에 있다.
- 진단 빌드는 `.\gradlew.bat -Pcom5Diagnostic=true :app:assembleDebug :app:assembleDebugAndroidTest`다. 별도 `.com5` 패키지로 설치돼 기존 앱을 지우지 않는다. 주행 규제 가드의 기기 테스트는 `-Pcom9GuardDiagnostic=true`로 만든 `.com9guard` 패키지에서 실행한다.
- Windows에서의 설치·화면 검사·프로세스 재시작은 [실행 절차](../../scripts/COM5-WINDOWS.md)를 따른다.
- JVM 테스트와 일반 에뮬레이터의 기기 테스트는 SDK를 실행하지 않거나 실제 Pleos 결과가 아니다. 차량 기능 검증으로 보고하지 않는다.
