# Android 소스

차량 조회 계약과 COM-9 실행 경계 프로토타입이 있다. debug 전용 Activity·UX 관찰·중지 진단이 있으며 Service, Bridge, Vehicle Adapter와 실제 승인 UI는 아직 없다. Pleos SDK는 계약 테스트의 클래스 대조용으로만 쓰며 앱에 포함되지 않는다. 미검증 capability는 false, 내비 변경은 비활성 상태를 유지한다.

| 위치 | 내용 |
| --- | --- |
| `main/` | Manifest, 리소스, `keepRules/rules.keep` |
| `main/java/com/example/pleos_pa/contract/` | [차량 조회 계약](../../contracts/vehicle-read/CONTRACT.md)의 wire 모델과 검증, SDK 실패 분류. SDK와 Android 컴포넌트에 의존하지 않는다 |
| `test/java/com/example/pleos_pa/contract/` | 공용 fixture와 SDK 클래스 대조로 계약을 고정하는 JVM 테스트. SDK를 실행하지 않으므로 차량 기능 검증이 아니다 |
| `main/java/com/example/pleos_pa/safety/` | 승인 인자·UX 신선도·단회 실행·중지 경계 프로토타입. 실제 SDK/승인 엔드포인트 미연결 |
| `debug/` | COM-9 진단 Activity, Car UX 관찰, 앱/알림 중지. release에 포함하지 않음 |
| `test/java/com/example/pleos_pa/safety/` | 실행 경계 및 debug 진단 세션 JVM 테스트 |
| `test/java/com/example/pleos_pa/` | 산술 예제 1개 |
| `androidTest/java/com/example/pleos_pa/` | 예제 및 `diagnostics/CarUxRuntimeTest.java` 실제 Automotive UX 조회·중지 테스트. 기기 실행은 CI 범위 밖이다 |

기능을 추가하면 이 표에 실제 위치를 함께 갱신한다. SDK 접근은 Android Adapter에 두고 Node로 옮기지 않는다 ([ADR-0002](../../docs/decisions/0002-boundaries-and-transport.md)).
