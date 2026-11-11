# Android 소스

연결 제어, 차량 조회, 내비 현재 목적지 조회 앱이다. 읽을 수 있는 속성은 카탈로그의 97개이며, `SMART_GLASS_CONFIG`는 공개 capability API가 없어 읽지 않는다. 제외 도구만 쓰는 `CAR_TOLL`은 Manifest에 없어 `ETCS_VOICE_GUIDANCE_VOLUME`은 `PERMISSION_DENIED`가 된다.

| 위치 | 내용 |
| --- | --- |
| `main/` | Manifest, 리소스, `keepRules/rules.keep` |
| `main/java/com/example/pleos_pa/MainActivity.kt` | 연결 상태·기능 목록·요청별 메모리 기록·Car UX 제한·연결 시작/중지/재개 화면 |
| `main/java/com/example/pleos_pa/ui/` | 메모리 요청 기록, 검증된 조회 결과 표시, 현재 화면의 Car UX 관찰과 만료 정책, 선택적인 읽기 전용 플랫폼 관찰 메타데이터. `NavTaskHistory`는 COM-11 상태를 기록으로 전달하는 표시 어댑터이며 실제 내비 실행 경로에는 아직 연결되지 않았다 |
| `debug/java/com/example/pleos_pa/ui/` | 모의 화면, .com5 전용 공용 기록 재시작 probe와 실제 Car UX 점검 화면. 모의 화면/재시작 probe는 SDK·MCP 서버를 사용하지 않는다. 실제 점검 화면은 공개 Car UX 조회와 기존 실제 서비스 제어만 사용하고 값 주입/변경은 하지 않는다 |
| `main/java/com/example/pleos_pa/connection/` | Foreground Service, 알림, 세션과 명령 순서, 메모리 상태 |
| `main/java/com/example/pleos_pa/bridge/` | 조회기로 넘기는 게이트웨이와 그 응답 형식. COM-12 전의 HTTP Bridge에서 남은 앱 안 경계다 |
| `main/java/com/example/pleos_pa/navigation/` | 내비 조회 슬롯(`DestinationReader`)과 NaviHelper 경계(`NaviSdk`, `PleosNaviSdk`). [내비 조회 계약](../../contracts/navigation/CONTRACT.md)을 따른다. `NAVI_ROUTE`만 쓰고 경로를 바꾸지 않는다. 내비 변경 요청 하나를 한 작업으로 처리하는 조정기(`NavTaskCoordinator`)도 여기에 있다. 실행 조건 검사, 처리 중 BUSY, 첫 실패 중단, 연결 중지 후 미실행 동작 차단을 맡으며, SDK에 의존하지 않고 조건과 동작을 주입받는다. 아직 연결 경로에 연결하지 않았다 |
| `main/java/com/example/pleos_pa/vehicle/` | 앱 전체 조회 슬롯, SDK 경계와 97개 속성의 호출 표, 실행 환경 판별 |
| `main/java/com/example/pleos_pa/mcp/` | MCP 서버(`127.0.0.1:18766/mcp`, stateless Streamable HTTP). 두 카탈로그의 채택 도구, 입력 스키마와 인자 검사, 결과 글과 structuredContent. 내비 도구는 `NavigationDestinationTool`·`NavigationToolResult` |
| `main/java/com/example/pleos_pa/contract/` | [차량 조회 계약](../../contracts/vehicle-read/CONTRACT.md)의 요청·응답 모델과 검증, SDK 실패 분류. 내비 카탈로그와 목적지 판정(`NavigationDestination`). SDK와 Android 컴포넌트에 의존하지 않는다 |
| `test/java/com/example/pleos_pa/mcp/` | 공용 `tool-list.json`·`tool-inputs.json`·`tool-results.json` 대조와, 실제 소켓 + SDK 대역으로 MCP 서버를 검사하는 JVM 테스트. 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/contract/` | 공용 fixture와 SDK 클래스 대조로 계약을 고정하는 JVM 테스트. SDK를 실행하지 않으므로 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/{connection,vehicle,ui}/` | SDK 대역으로 세션·조회기·기록 표시 모델을 검사하는 JVM 테스트. 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/navigation/` | NaviHelper 대역으로 내비 조회 슬롯을, 모의 조건·동작으로 작업 상태 전이와 호출 순서를 검사하는 JVM 테스트. 내비 SDK와 실제 반영 검증이 아니다 |
| `test/java/com/example/pleos_pa/` | 산술 예제 1개 |
| `androidTest/java/com/example/pleos_pa/` | 기기 테스트. 모의 UX/값의 실제 위젯 검사와 실제 서비스·MCP tools/call→공용 기록·알림 권한/중지 검사. 별도 .com5 패키지에서 실행한다. 일반 Android APP_NOT_READY와 실제 Pleos SDK 결과를 구분하며 주행 정책 검수는 별도다 |

기능을 추가하면 이 표에 실제 위치를 함께 갱신한다. SDK 접근은 Android Adapter에 둔다 ([ADR-0008](../../docs/decisions/0008-embedded-mcp-server.md)).
