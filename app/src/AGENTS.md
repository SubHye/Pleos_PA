# Android 소스

연결 제어와 차량 조회 앱이다. 읽을 수 있는 속성은 카탈로그의 97개이며, `SMART_GLASS_CONFIG`는 공개 capability API가 없어 읽지 않는다. 제외 도구만 쓰는 `CAR_TOLL`은 Manifest에 없어 `ETCS_VOICE_GUIDANCE_VOLUME`은 `PERMISSION_DENIED`가 된다.

| 위치 | 내용 |
| --- | --- |
| `main/` | Manifest, 리소스, `keepRules/rules.keep` |
| `main/java/com/example/pleos_pa/MainActivity.kt` | 연결 시작·중지·재개와 상태를 보여 주는 최소 화면 |
| `main/java/com/example/pleos_pa/connection/` | Foreground Service, 알림, 세션과 명령 순서, 메모리 상태 |
| `main/java/com/example/pleos_pa/bridge/` | 조회기로 넘기는 게이트웨이와 그 응답 형식. COM-12 전의 HTTP Bridge에서 남은 앱 안 경계다 |
| `main/java/com/example/pleos_pa/vehicle/` | 앱 전체 조회 슬롯, SDK 경계와 97개 속성의 호출 표, 실행 환경 판별 |
| `main/java/com/example/pleos_pa/navigation/` | 내비 변경 요청 하나를 한 작업으로 처리하는 조정기. 실행 조건 검사, 처리 중 BUSY, 첫 실패 중단, 연결 중지 후 미실행 동작 차단. SDK에 의존하지 않고 조건과 동작을 주입받으며, 아직 연결 경로에 연결하지 않았다 |
| `main/java/com/example/pleos_pa/mcp/` | MCP 서버(`127.0.0.1:18766/mcp`, stateless Streamable HTTP). 카탈로그의 채택 도구, 입력 스키마와 인자 검사, 결과 글과 structuredContent |
| `main/java/com/example/pleos_pa/contract/` | [차량 조회 계약](../../contracts/vehicle-read/CONTRACT.md)의 요청·응답 모델과 검증, SDK 실패 분류. SDK와 Android 컴포넌트에 의존하지 않는다 |
| `test/java/com/example/pleos_pa/mcp/` | 공용 `tool-list.json`·`tool-inputs.json`·`tool-results.json` 대조와, 실제 소켓 + SDK 대역으로 MCP 서버를 검사하는 JVM 테스트. 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/contract/` | 공용 fixture와 SDK 클래스 대조로 계약을 고정하는 JVM 테스트. SDK를 실행하지 않으므로 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/{connection,vehicle}/` | SDK 대역으로 세션·조회기를 검사하는 JVM 테스트. 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/navigation/` | 모의 조건·동작으로 작업 상태 전이와 호출 순서를 검사하는 JVM 테스트. 내비 SDK와 실제 반영 검증이 아니다 |
| `test/java/com/example/pleos_pa/` | 산술 예제 1개 |
| `androidTest/java/com/example/pleos_pa/` | 기기 테스트. 현재 예제 1개이며 CI 범위 밖이다 |

기능을 추가하면 이 표에 실제 위치를 함께 갱신한다. SDK 접근은 Android Adapter에 둔다 ([ADR-0008](../../docs/decisions/0008-embedded-mcp-server.md)).
