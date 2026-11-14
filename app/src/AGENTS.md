# Android 소스

연결 제어, 차량 조회, 내비 현재 목적지 조회, 고정 장소 목록 안의 목적지 설정과 경유지 조회·추가·삭제 앱이다. 읽을 수 있는 속성은 카탈로그의 97개이며, `SMART_GLASS_CONFIG`는 공개 capability API가 없어 읽지 않는다. 제외 도구만 쓰는 `CAR_TOLL`은 Manifest에 없어 `ETCS_VOICE_GUIDANCE_VOLUME`은 `PERMISSION_DENIED`가 된다.

| 위치 | 내용 |
| --- | --- |
| `main/` | Manifest, 리소스, `keepRules/rules.keep` |
| `main/java/com/example/pleos_pa/MainActivity.kt` | 연결 상태·기능 목록·요청별 메모리 기록·Car UX 제한·연결 시작/중지/재개 화면 |
| `main/java/com/example/pleos_pa/ui/` | 메모리 요청 기록, 검증된 조회 결과 표시, 현재 화면의 Car UX 관찰과 만료 정책, 선택적인 읽기 전용 플랫폼 관찰 메타데이터. `NavTaskHistory`는 COM-11 상태를 기록으로 전달하는 표시 어댑터이며 실제 내비 실행 경로에는 아직 연결되지 않았다 |
| `debug/java/com/example/pleos_pa/ui/` | 모의 화면, .com5 전용 공용 기록 재시작 probe와 실제 Car UX 점검 화면. 모의 화면/재시작 probe는 SDK·MCP 서버를 사용하지 않는다. 실제 점검 화면은 공개 Car UX 조회와 기존 실제 서비스 제어만 사용하고 값 주입/변경은 하지 않는다 |
| `main/java/com/example/pleos_pa/connection/` | Foreground Service, 알림, 세션과 명령 순서, 메모리 상태 |
| `main/java/com/example/pleos_pa/bridge/` | 조회기로 넘기는 게이트웨이와 그 응답 형식. COM-12 전의 HTTP Bridge에서 남은 앱 안 경계다 |
| `main/java/com/example/pleos_pa/navigation/` | 내비 조회 슬롯(`DestinationReader`)과 NaviHelper 경계(`NaviSdk`, `PleosNaviSdk`). [내비 계약](../../contracts/navigation/CONTRACT.md)을 따른다. SDK 호출은 `getDestinationInfo`, `requestRoute`, `getWaypointInfo`, `addWaypoint`, `removeWaypoint`다. 경유지 조회 슬롯(`WaypointReader`)은 목적지 조회기가 연결을 쥐고 받은 콜백을 넘겨 받는다. 내비 변경 요청 하나를 한 작업으로 처리하는 조정기(`NavTaskCoordinator`)는 실행 조건 검사, 처리 중 BUSY, 첫 실패 중단, 연결 중지 후 미실행 동작 차단을 맡으며, SDK에 의존하지 않고 조건과 동작을 주입받는다. 목적지 설정(`DestinationSetter`)과 경유지 추가·삭제(`WaypointChanger`)가 요청마다 자기 실행 조건을 들고 조정기 하나를 함께 써서 변경이 하나씩만 실행되고, 목적지 설정은 그 조정기로 요청 전 조회 → `requestRoute` → 재조회 좌표 확인을 한 작업으로 처리한다. 서비스 소유 `CarNavigationGuard`가 최신 공개 Car UX와 DRIVER/MAIN 매핑을 실행 전 확인하며 `NavigationDrivingPolicy`가 규제/정보 상실을 기본 차단한다. 서비스는 가드의 답을 `guardRestrictionCode`로만 넘긴다(가드가 없을 때만 차단, 가드의 통과 `null`은 그대로 통과). 사전 조회 대기 뒤와 SDK 호출 직전 재검사한다. 실제 가드의 Pleos 검수는 별도다 |
| `main/java/com/example/pleos_pa/vehicle/` | 앱 전체 조회 슬롯, SDK 경계와 97개 속성의 호출 표, 실행 환경 판별 |
| `main/java/com/example/pleos_pa/mcp/` | MCP 서버(`127.0.0.1:18766/mcp`, stateless Streamable HTTP). 두 카탈로그의 채택 도구, 입력 스키마와 인자 검사, 결과 글과 structuredContent. 내비 도구는 `NavigationTool` 구현(`NavigationDestinationTool` 조회, `NavigationPlaceTool` 장소 찾기, `NavigationSetDestinationTool` 목적지 설정, `NavigationWaypointsTool` 경유지 조회, `NavigationWaypointChangeTool` 경유지 추가·삭제)과 `NavigationToolResult`다. 카탈로그에서 `adopted`인 도구만 올린다 |
| `main/java/com/example/pleos_pa/contract/` | [차량 조회 계약](../../contracts/vehicle-read/CONTRACT.md)의 요청·응답 모델과 검증, SDK 실패 분류. 내비 카탈로그와 목적지 판정(`NavigationDestination`), 고정 장소 목록과 장소 대조(`PlaceCatalog`), 재조회 좌표 일치 판정(`DestinationMatch`). SDK와 Android 컴포넌트에 의존하지 않는다 |
| `test/java/com/example/pleos_pa/mcp/` | 공용 `tool-list.json`·`tool-inputs.json`·`tool-results.json` 대조와, 실제 소켓 + SDK 대역으로 MCP 서버를 검사하는 JVM 테스트. 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/contract/` | 공용 fixture·고정 장소 목록·SDK 클래스 대조로 계약을 고정하는 JVM 테스트. SDK를 실행하지 않으므로 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/{connection,vehicle,ui}/` | SDK 대역으로 세션·조회기·기록 표시 모델을 검사하는 JVM 테스트. 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/navigation/` | NaviHelper 대역으로 내비 조회 슬롯·경유지 조회 슬롯·목적지 설정(공용 `set-destination-cases.json` 포함)·경유지 추가·삭제·규제 실행 경계를, 모의 조건·동작으로 작업 상태 전이와 호출 순서를 검사하는 JVM 테스트. 내비 SDK와 실제 반영 검증이 아니다 |
| `test/java/com/example/pleos_pa/` | 산술 예제 1개 |
| `androidTest/java/com/example/pleos_pa/` | 기기 테스트. 모의 UX/값의 실제 위젯 검사와 실제 서비스·MCP tools/call→공용 기록·알림 권한/중지 검사. 별도 .com5 패키지에서 실행한다. 일반 Android APP_NOT_READY와 실제 Pleos SDK 결과를 구분한다. 별도 `.com9guard`의 CarNavigationGuardRuntimeTest는 내비 SDK 없이 공개 Car 정책과 운전자 MAIN 매핑만 읽는다 |

기능을 추가하면 이 표에 실제 위치를 함께 갱신한다. SDK 접근은 Android Adapter에 둔다 ([ADR-0008](../../docs/decisions/0008-embedded-mcp-server.md)).

## 실행 동작

2026-11-14에 루트 README를 사람이 읽는 소개 문서로 바꾸면서, 개발과 검수에 쓰는 아래 내용을 이곳으로 옮겼다. 문장은 옮기기 전 그대로다.

### 연결 제어

내비 변경은 서비스가 최신 Car UX와 현재 사용자의 DRIVER/MAIN 표시 영역을 확인한 뒤 실행한다. 규제 활성·정보 상실·영역 미확인은 SDK 호출 전 차단하며, 사전 목적지 조회 뒤와 호출 직전에도 재검사한다. 규제 해제 시 자동 실행하지 않는다. 화면의 상세 기록 허용과 서비스의 변경 실행 조건은 별개다.

MCP 서버는 사용자가 앱 화면에서 시작해야 열리고, 앱 실행이나 프로세스 재시작만으로는 열리지 않는다. 중지하면 서비스가 끝나고 재개 동작이 있는 알림이 남는다. 알림 권한을 거부하면 화면에서만 제어한다.

- 화면의 "연결됨"은 MCP 서버가 요청을 받을 수 있다는 뜻이며, AI가 연결되었다는 뜻이 아니다.
- 앱은 AI 클라이언트가 조회 도중 연결을 끊어도 알지 못한다. 그 조회는 데드라인 안에 끝나고, 그동안 다른 호출은 `BUSY`를 받는다.
- 앱은 ADB 해제도 알지 못한다. 해제되면 다음 호출이 클라이언트 쪽 연결 실패로 끝난다.

시스템이나 사용자가 프로세스를 종료하면 자동으로 다시 열지 않는다. 다음 실행은 중지 상태로 시작한다.

클라이언트 이탈은 Ktor CIO에서 조회 취소/화면 상태 변경 신호로 전달되지 않는다. 조회는 데드라인까지 슬롯을 쓰며, 화면의 연결됨은 MCP 서버 준비 상태만 뜻한다. 앱의 명시적 중지는 서버·슬롯·SDK를 종료한다.

### 차량 화면과 요청 기록

요청 기록은 차량 조회 계약을 검증한 뒤 추가한다. 요청마다 별도 식별자를 사용하므로 중복 requestId, 역순·늦은 응답이 다른 행을 갱신하지 않는다. 수신 시각과 출처를 표시하고, 미지원·알 수 없는 값을 0/false로 채우지 않는다. 클라이언트 인증 정보가 없으므로 AI 이름은 "알 수 없음"이다. 내비 변경·되돌리기는 아직 지원하지 않으며 버튼을 비활성화하고 이유를 표시한다.

현재 화면의 공개 Car UX API를 관찰한다. COM-9에서 운전자 표시 영역·규제 중 화면 정책을 검증하지 못했으므로 실제 앱의 상세 기록 허용은 기본 차단한다. DO=false·제한 0만으로 이 차단을 해제하지 않으며 실행 인자·빌드 플래그로 우회할 수 없다. 검증 후 코드 리뷰를 거쳐 정책을 연결해야 한다. 모의 화면은 별도 검수 정책으로 동작한다. 규제 활성·연결 상실·화면 변경·3초간 관찰 갱신 없음은 상세 기록을 숨기고 짧은 처리 상태와 연결 중지만 유지한다. UX 제한 해제는 정차나 차량 변경 허가의 증거가 아니다. 텍스트 입력과 차량 변경 경로는 없다. 실제 Pleos의 규제 전환 검수는 별도로 필요하다.

기록에는 요청 선택 인자와 검증된 차량 조회 결과만 남긴다. 메일·일정 원문, MCP 원문, 신원·인증 헤더는 받지 않는다. 최근 100건·인자 4,096자·결과 16,384자 상한과 10건씩 탐색은 검수용 초기값이다. OQ-09의 최종 상한은 실제 기기의 UI·힙 검수 후 정한다. 기록 삭제 후 늦게 도착한 응답은 기록을 복구하지 않는다. Android 화면 상태 저장에도 값과 선택을 맡기지 않는다.

`debug` 전용 검수 화면의 모의 기록·규제·연결 제어는 실제 SDK·MCP 서버·공용 기록과 분리된다. `.com5` 진단 빌드에만 켜지는 별도 probe는 공용 기록에 모의 값을 추가하고 건수/PID만 출력한다. 기본 debug나 release에는 이 probe가 활성화되지 않는다. COM-22 실제 플랫폼 점검 화면은 공개 Car UX 관찰과 기존 실제 MCP 서비스 제어를 사용한다. 정책 허용을 바꾸거나 기어/UX 값을 주입하지 않는다. PermissionTest는 진단 앱의 실제 알림 권한 창에서 거부 후 화면 중지를 검사한다. ServiceTest는 실제 서비스/MCP tools/list·게시 알림 PendingIntent·소켓 종료를 차량 조회 없이 검사한다. HistoryTest는 실제 MCP tools/call→게이트웨이→공용 기록을 검사한다. 실제 알림 패널 터치와 규제 전환 검수는 별도다.

Windows 설치·화면 검사·프로세스 재시작은 [실행 절차](../../scripts/COM5-WINDOWS.md)를 따른다. 키트는 Python 없이 PowerShell에서 실행하며, 기존 앱 삭제 없이 별도 패키지를 사용한다. 소스 빌드는 다음과 같다.

```powershell
.\gradlew.bat -Pcom5Diagnostic=true :app:assembleDebug :app:assembleDebugAndroidTest
```

CI는 일반 API34 에뮬레이터에서 `DriverUiTest`·`BridgeServiceRuntimeTest`·별도 `BridgeHistoryRuntimeTest`·`NotificationPermissionRuntimeTest`와 `scripts/check-com5-device.py`를 실행한다. 공용 메모리 기록을 모의 데이터로 채운 뒤 실제 프로세스를 종료하고 새 PID·기록0·중지 초기 상태를 확인한다. 이는 새 모델 인스턴스 검사와 구분하며, 실제 내비 이전 상태나 Pleos 규제 전환의 증거로 사용하지 않는다. 검사 결과와 처리 시간·Java 힙 측정은 Actions의 `com5-android-ui-evidence` artifact에 남는다.

### 내비 작업 표시 준비

COM-11의 `NavTaskCoordinator`를 감싸는 `ui/NavTaskHistory`는 접수·검사·실행·성공·실패·차단 및 BUSY를 공용 메모리 기록에 전달한다. 연결 중지 당시 이미 전송한 동작은 `RESULT_UNKNOWN`으로 표시하고 취소·복원 성공으로 단정하지 않는다. 목적지·인자 원문·SDK 오류 문장은 저장하지 않는다.

이 어댑터는 모의 조건/동작으로 검증하며 아직 실제 내비 변경/MCP 경로에 연결하지 않았다. 이후 실행 어댑터가 연결 수명과 함께 생성·종료해야 한다. 기존 `get_navigation_destination`은 읽기 전용 도구이며 내비 변경 성공이나 상태 복원 기능을 뜻하지 않는다. 화면의 기능 목록은 차량·내비 두 카탈로그를 함께 표시한다. 실제 표시 허용은 기존 Car UX 정책을 따른다.

### 확인한 AI 클라이언트

- COM-12 전에는 PC의 Node.js MCP 서버가 `127.0.0.1:18765`의 HTTP Bridge로 앱을 불렀다. 지금은 둘 다 없다.
- 실제 AI 호출은 Codex로 확인했다(PR #15). 내비 목적지 설정·조회의 종단간 실행도 Codex CLI로 확인했다(COM-3, [내비 계약](../../contracts/navigation/CONTRACT.md#외부-ai-종단간-관찰-sdk_emulator-2026-11-11-com-3)). Claude 계열은 MCP 연결과 도구 목록까지만 확인했다.

앱에서 연결을 시작한 뒤 PC에서 forward를 만든다. 기기 하나를 지정한다. `tcp:18766`이 이미 있거나 `--no-rebind`가 실패하면 다른 작업이 쓰는 포트이므로 덮어쓰지 않는다.

```powershell
adb -s <기기 serial> forward --no-rebind tcp:18766 tcp:18766
adb -s <기기 serial> forward --remove tcp:18766   # 끝나면
```

| 클라이언트 | 등록 | 확인 |
| --- | --- | --- |
| Codex CLI / ChatGPT Desktop 로컬 Codex 호스트 | `~/.codex/config.toml`에 `[mcp_servers.pleos-pa-vehicle]`, `url = "http://127.0.0.1:18766/mcp"` | PR #15에서 Codex CLI 0.166.0으로 확인. 비대화형 `codex exec`(승인 정책 `never`)는 `set_navigation_destination`을 클라이언트에서 거절하므로, 목적지 설정에는 `-c mcp_servers.pleos-pa-vehicle.default_tools_approval_mode=approve`가 필요했다(COM-3) |
| Claude Code CLI | `claude -p --mcp-config <파일> --strict-mcp-config`, 파일 내용 `{"mcpServers":{"pleos-pa-vehicle":{"type":"http","url":"http://127.0.0.1:18766/mcp"}}}` | COM-3에서 2.1.302로 연결과 도구 23개 노출만 확인. 계정 크레딧 부족으로 도구 호출은 확인하지 못했다 |
| ChatGPT 웹 커스텀 MCP 앱 | 공개 HTTPS가 필요하다. loopback 서버에 직접 닿지 못한다 | 대상 아님(COM-15에서 결정) |

토큰이나 환경 변수는 필요 없다. COM-12 전의 stdio 등록(`node mcp/src/server.ts`)과 COM-10 모의 진입점(`serve-com10-mock.ts`)은 Node 서버와 함께 없어졌다. 클라이언트에 남은 설정은 지운다.

### MCP 서버 신뢰 경계

1차는 인증이 없다(COM-12에서 결정, [ADR-0008](../../docs/decisions/0008-embedded-mcp-server.md)). 개발용 연결이므로 쓰지 않을 때는 앱에서 중지하고 `adb forward`를 제거한다.

| 항목 | 내용 |
| --- | --- |
| 접근 가능 | 기기 안의 다른 프로세스, `adb forward`가 있는 동안 PC의 로컬 프로세스 |
| 수신 범위 | 기기 `127.0.0.1:18766`에만 묶는다. `adb forward`도 PC `127.0.0.1`에서만 수신한다 |
| 검사가 막는 것 | 브라우저식 요청(`Origin`이 있거나 Host가 `127.0.0.1`이 아니면 403), 채택되지 않은 도구, 스키마 밖 인자 |
| 검사가 못 막는 것 | 호출 프로세스의 신원 확인, 같은 형식으로 보내는 로컬 프로세스, 평문 HTTP 관찰, 슬롯 점유로 인한 `BUSY`, 서버 사칭 |
| 서버 사칭 | 앱이 포트를 잡고 있지 않을 때 다른 프로세스가 18766에서 수신하면, 클라이언트는 그 응답을 차량 값으로 받는다. 앱 화면이 연결됨이 아니면 AI가 답한 차량 값을 믿지 않는다 |
| 노출 범위 | 카탈로그의 읽기 값뿐이다. 제어 API가 없고 VIN은 제외된다. 서버가 열려 있으면 INTERNET 권한만 있는 기기 내 앱도 이 앱의 `CAR_*` 권한을 거쳐 값을 읽으며, Android 권한 모델은 이 경로를 막지 못한다 |
| 값이 남는 곳 | 앱은 검증된 조회 결과·선택 인자만 프로세스 메모리에 보관한다. 디스크/로그에는 쓰지 않고 재시작하면 사라진다. 앱 로그는 requestId, query, 결과, 처리 시간뿐이다. 다만 SDK가 조회 값을 logcat에 남긴다(문 위치에서 확인) |
