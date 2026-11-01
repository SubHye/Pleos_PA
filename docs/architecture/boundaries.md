# 책임 경계와 계약

## 현재와 참고 구현

| 경계 | Pleos_PA 현재 | 참고 PoC에서 확인한 책임 |
| --- | --- | --- |
| Android app | Manifest·리소스·예제 테스트만 존재, Activity/Service 없음 | BridgeService가 SDK/reader/bridge 세션 생명주기 소유 |
| Vehicle Adapter | 없음 | PleosVehicleSdk, VehicleStatusReader가 Vehicle 2.3.2 읽기 호출·지원 확인·값/오류 변환 |
| HTTP Bridge | 없음 | DoorBridgeServer/Protocol이 loopback 요청 파싱·입장 제한·응답 |
| Node MCP | package.json/lock 없음 | mcp/src/server.mjs 도구 등록, vehicle-client.mjs HTTP 호출·결과 검증 |
| 외부 AI | 연결 설정 없음 | stdio를 실행할 수 있는 로컬 클라이언트 환경 필요; 웹/모바일 일반 지원 보장 아님 |
| 일정·연락 | 없음 | 외부 AI에 연결한 서비스 영역, 차량 앱에 계정 원문을 복제하지 않음 |

참고 근거: [PoC 고정 커밋](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca)의 app/src/main/java/com/example/pleostest/{BridgeService.kt,bridge/,vehicle/}, mcp/src/, Manifest.

## 참고 PoC 통신 계약 (신규 저장소 계약으로 아직 채택하지 않음)

- MCP: Node.js stdio. `get_door_status`, `get_window_status`, `get_energy_level`, `get_front_left_door_position` 네 도구.
- PC `127.0.0.1:18765` → ADB forward → Android `127.0.0.1:18765`.
- HTTP `POST /v2/vehicle-read`; requestId, query와 남은 제한시간 전달. 정확한 JSON은 Protocol과 Node client/test가 원천이다.
- 총 제한시간 10초, 동시 작업 제한과 BUSY, 취소/늦은 결과 폐기. 자동 재시도 없음.
- Host 검사·Origin 거부와 loopback 제한이 있지만 프로세스별 인증은 아니다. 현 소스에는 토큰 인증이 없다. 이 경로를 네트워크에 공개하지 않는다.
- Service는 specialUse foreground, 알림/화면 중지와 START_NOT_STICKY 사용. Android 버전·정책·실차에서 생명주기 동작은 별도 검증한다.

Linear 공통 반환 스키마는 **제안**이다. status/source 의미를 기존 PoC의 code/outcome/source에 임의로 덮어씌우지 않는다. 이전 PR에서 도구 이름·입력·응답·오류·시각·권한 매핑 표와 양쪽 회귀 검사를 확정한다. 수신 시각은 차량 측 측정 시각이 아니다.

## 앞으로의 책임

외부 AI는 일정 해석·도구 선택·승인 대화를, MCP는 프로토콜·입력 검증·요청/오류 변환을, Android는 SDK 권한·실행·생명주기·결과 가시화를 맡는다.
차량 SDK 로직을 Node에 옮기지 않는다. 목적지 변경/연락은 승인·권한 확인 뒤 실행하고, SDK 전달 뒤 timeout은 결과 불명일 수 있으므로 중복 실행하지 않는다.
장기 원격 중계는 사용자-차량 연결, 인증/권한, 암호화, 세션 만료·폐기·연결 중지, 최소 로그를 설계해야 한다. 현재 구현 범위에는 포함하지 않는다.
