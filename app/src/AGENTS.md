# Android 소스

연결 제어와 차량 조회 앱이다. 읽을 수 있는 속성은 카탈로그의 97개이며, `SMART_GLASS_CONFIG`는 공개 capability API가 없어 읽지 않는다. 제외 도구만 쓰는 `CAR_TOLL`은 Manifest에 없어 `ETCS_VOICE_GUIDANCE_VOLUME`은 `PERMISSION_DENIED`가 된다.

| 위치 | 내용 |
| --- | --- |
| `main/` | Manifest, 리소스, `keepRules/rules.keep` |
| `main/java/com/example/pleos_pa/MainActivity.kt` | 연결 시작·중지·재개와 상태를 보여 주는 연결 상태·기능 목록·요청별 기록·Car UX 제한 화면 |
| `main/java/com/example/pleos_pa/ui/` | 메모리 요청 기록, 검증된 조회 결과 표시, 현재 화면의 Car UX 관찰과 만료 정책 |
| `debug/java/com/example/pleos_pa/ui/` | 별도 모의 기록·규제·연결 상태를 쓰는 화면 검수 Activity. 실제 SDK·Bridge·공용 기록을 사용하지 않는다 |
| `main/java/com/example/pleos_pa/connection/` | Foreground Service, 알림, 세션과 명령 순서, 메모리 상태 |
| `main/java/com/example/pleos_pa/bridge/` | loopback HTTP 서버, 요청 문법·헤더 검사, 조회기로 넘기는 게이트웨이 |
| `main/java/com/example/pleos_pa/vehicle/` | 앱 전체 조회 슬롯, SDK 경계와 97개 속성의 호출 표, 실행 환경 판별 |
| `main/java/com/example/pleos_pa/contract/` | [차량 조회 계약](../../contracts/vehicle-read/CONTRACT.md)의 wire 모델과 검증, SDK 실패 분류. SDK와 Android 컴포넌트에 의존하지 않는다 |
| `test/java/com/example/pleos_pa/contract/` | 공용 fixture와 SDK 클래스 대조로 계약을 고정하는 JVM 테스트. SDK를 실행하지 않으므로 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/{bridge,connection,vehicle,ui}/` | 실제 소켓과 SDK 대역으로 Bridge·세션·조회기를 검사하는 JVM 테스트. 차량 기능 검증이 아니다 |
| `test/java/com/example/pleos_pa/` | 산술 예제 1개 |
| `androidTest/java/com/example/pleos_pa/` | 기기 테스트. 예제와 모의 Car UX 기반 실제 위젯 검사. APK 빌드는 CI에서 검사하지만 기기 실행은 CI 범위 밖이다 |

기능을 추가하면 이 표에 실제 위치를 함께 갱신한다. SDK 접근은 Android Adapter에 두고 Node로 옮기지 않는다 ([ADR-0002](../../docs/decisions/0002-boundaries-and-transport.md)).
