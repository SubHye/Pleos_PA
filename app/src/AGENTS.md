# Android 소스

현재 초기 모듈이다. Activity, Pleos SDK, Service, Bridge는 아직 없다.

| 위치 | 내용 |
| --- | --- |
| `main/` | Manifest, 리소스, `keepRules/rules.keep`. Kotlin 소스 없음 |
| `test/java/com/example/pleos_pa/` | JVM 단위 테스트. 현재 산술 예제 1개로 차량 기능 검증이 아니다 |
| `androidTest/java/com/example/pleos_pa/` | 기기 테스트. 현재 예제 1개이며 CI 범위 밖이다 |

기능을 추가하면 이 표에 실제 위치를 함께 갱신한다. SDK 접근은 Android Adapter에 두고 Node로 옮기지 않는다 ([ADR-0002](../../docs/decisions/0002-boundaries-and-transport.md)).
