# Android 소스

현재 초기 모듈이다. Activity, Service, Bridge, Vehicle Adapter는 아직 없다. Pleos SDK는 계약 테스트의 클래스 대조용으로만 쓰며 앱에 포함되지 않는다.

| 위치 | 내용 |
| --- | --- |
| `main/` | Manifest, 리소스, `keepRules/rules.keep` |
| `main/java/com/example/pleos_pa/contract/` | [차량 조회 계약](../../contracts/vehicle-read/CONTRACT.md)의 wire 모델과 검증, SDK 실패 분류. SDK와 Android 컴포넌트에 의존하지 않는다 |
| `test/java/com/example/pleos_pa/contract/` | 공용 fixture와 SDK 클래스 대조로 계약을 고정하는 JVM 테스트. SDK를 실행하지 않으므로 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/` | 산술 예제 1개 |
| `androidTest/java/com/example/pleos_pa/` | 기기 테스트. 현재 예제 1개이며 CI 범위 밖이다 |

기능을 추가하면 이 표에 실제 위치를 함께 갱신한다. SDK 접근은 Android Adapter에 두고 Node로 옮기지 않는다 ([ADR-0002](../../docs/decisions/0002-boundaries-and-transport.md)).
