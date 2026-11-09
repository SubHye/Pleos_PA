# 내비 조회 계약

상태: **제안** (COM-24 PR에서 상대 개발자 합의 필요).

이 문서는 규칙만 적는다. 도구·한도 값은 [catalog.json](catalog.json), 판정 사례는 [fixtures/](fixtures/)가 기준이다. 앱의 JVM 테스트가 이 파일들을 읽는다.

## 근거의 구분

| 표시 | 뜻 |
| --- | --- |
| `mock` | 사람이 쓴 사례. SDK 동작의 증거가 아니다 |
| `source_only` | NaviHelper 3.0.5 배포 AAR·공식 문서에서 읽은 경로. 실행하지 않았다 |
| `sdk_emulator` | Pleos 에뮬레이터에서 관찰한 실제 값. 그 환경에 한정한다 |

카탈로그의 `adoption`이 `adopted`인 도구만 `tools/list`에 올린다. `adopted`는 `verifiedOn` 환경의 제품 앱으로 안내 없음·안내 중을 실제 SDK로 조회해 순정 내비 화면과 대조했다는 뜻이다. SDK·내비 앱 버전이 바뀌면 `verifiedOn`을 비우고 `undecided`로 되돌린다.

## 범위

- 대상은 NaviHelper 3.0.5의 `getDestinationInfo()` → `onDestinationInfo(DestinationInfo)` 한 가지다. 권한은 `pleos.car.permission.NAVI_ROUTE`만 쓴다.
- 경로 변경(`requestRoute` 등, `NAVI_ROUTE_SEARCH`), 지정 지점 Trip(`requestTrip`), 그 밖의 `get*Info`는 대상이 아니다. 변경 작업의 규칙은 COM-11이 정한다.
- 목적지 좌표는 앱 안에서 목적지 없음 판정에만 쓰고 결과로 보내지 않는다(NFR-05). 장소 일치 판단에 필요한 정보는 COM-4에서 정한다.
- [ADR-0008](../../docs/decisions/0008-embedded-mcp-server.md)대로 MCP 서버에는 인증이 없다. 연결이 켜져 있는 동안 기기의 다른 앱도 이 도구로 현재 목적지 이름·주소와 남은 시간을 읽을 수 있다.

## 요청 생명주기

1. MCP 서버가 도구 호출마다 `requestId`(UUID)를 만든다. 하나의 호출은 SDK 호출 1회다.
2. 인자는 받지 않는다. 키가 하나라도 있으면 `Input validation error:`로 답하고 SDK를 부르지 않는다.
3. 조회기가 슬롯을 차지하고 `getDestinationInfo()`를 부른다. 슬롯은 앱 전체에 1개이며 **차량 조회 슬롯과 따로**다. 큐는 없다.
4. 첫 `onDestinationInfo`가 이 조회의 답이다. 앱이 판정한 결과를 같은 `requestId`로 돌려준다.

| 상황 | 규칙 |
| --- | --- |
| 서비스 연결 | 연결 시작 때 `initialize(onConnected, onFailed)`를 부른다. `onConnected` 전과 `onFailed` 뒤의 조회는 SDK를 부르지 않고 `NAVI_NOT_CONNECTED`. 다시 연결하지 않는다. 연결 콜백 수신은 `sdk_emulator`(34ms) |
| 동시 요청 | 내비 조회가 진행 중이면 `BUSY`. 차량 조회와 COM-11 변경 작업은 막지 않는다. 변경 작업 중에 읽은 값은 변경 전 목적지일 수 있다 |
| timeout | `deadlineMs` 안에 콜백이 없으면 `TIMEOUT`. MCP 서버는 1초를 더 기다린 뒤 같은 코드로 끝낸다 |
| 재시도 | 어느 계층도 자동 재시도하지 않는다 |
| 늦은 응답 | 아래 절 |
| 중지 | 승인 차단 → 서버 종료 → 진행 중 조회를 `CONNECTION_STOPPED`로 끝냄 → listener 해제와 `release()`. 그 뒤 온 콜백은 버린다 |
| 실행 환경 | Pleos 에뮬레이터가 아니면 SDK에 연결하지 않고 `APP_NOT_READY` |

### 늦은 응답

`onDestinationInfo`에는 요청 식별자가 없다. 그래서 다음과 같이 처리한다.

- 진행 중인 조회가 없을 때 온 콜백은 버린다.
- `TIMEOUT`으로 끝난 조회가 있으면, 그 조회의 늦은 콜백이 오거나 `staleHoldMs`가 지날 때까지 새 조회를 SDK에 보내지 않는다. 그동안 들어온 조회는 `PREVIOUS_RESPONSE_PENDING`으로 끝낸다. 늦은 콜백은 버린다. 그 호출이 예외로 끝났으면 콜백이 오지 않으므로 막힘을 바로 푼다.
- **잔여 위험:** `staleHoldMs`보다 늦게 온 콜백은 다음 조회의 답으로 쓰일 수 있다. 다른 앱의 조회도 콜백을 부른다(아래 관찰). 어느 경우든 콜백 내용은 그 시점의 현재 목적지다.

## 에뮬레이터 관찰 (`sdk_emulator`, 2026-11-10)

`verifiedOn` 환경에서 제품 앱으로 확인했다. 실차와 다른 내비 앱 버전으로 일반화하지 않는다.

| 항목 | 관찰 |
| --- | --- |
| 연결 | `initialize` 뒤 약 34ms에 `onConnected` |
| 안내 없음 | 빈 이름, (0, 0), 0초·0m → `NO_DESTINATION`. 순정 화면 "어디로 갈까요?" |
| 안내 중 | 2040초·26000m → 화면 "34분 · 26km"와 일치. 추정 도착 02:14:23 KST, 화면 "2:14 AM" |
| 취소 뒤 | `NO_DESTINATION`, 순정 화면 안내 없음 |
| 응답 시간 | 안내 없음 약 7~30ms, 안내 중 약 1.0초(6회 모두). 요청은 바로 내비 서비스에 닿고 순정 내비 앱이 약 1초 뒤 답한다. 원인은 앱 밖이며 확인하지 않았다 |
| 동시 조회 | 안내 중 두 호출을 겹치면 두 번째가 `BUSY`. 같은 때 `get_energy_level`은 막히지 않았다 |
| 콜백 전달 | `getDestinationInfo()` 한 번에 내비 서비스가 등록된 클라이언트 6개 프로세스 모두에 `onDestinationInfo`를 보냈다. 다른 앱의 조회도 이 앱의 콜백을 부를 수 있다 |
| 좌표 | 안내 중 순정 내비 앱 로그의 내부 목적지 좌표가 (0.0/0.0) 경고와 함께 찍혔다. 이 앱이 받은 좌표는 결과로 보내지 않아 기록하지 않았다. 그래서 목적지 없음 판정은 빈 이름을 함께 요구한다 |
| 로그 | 시스템 앱 하나와 순정 내비 앱이 목적지 이름·주소를 logcat에 남긴다. 이 앱은 `requestId`, 상태, 경과 시간만 남긴다 |
| 중지 | 중지 뒤 호출은 연결 실패. NaviHelper `removeListener`, `release`, `unregisterCallbackV3` 실행 |
| release(R8) | 같은 조회가 동작했다 |

실제 SDK로 재현하지 않은 것: `TIMEOUT`, 늦은 콜백과 `PREVIOUS_RESPONSE_PENDING`, `NAVI_NOT_CONNECTED`, 이 앱의 `PERMISSION_DENIED`(COM-8에서 별도 앱으로 관찰), 필드 일부 누락(`PARTIAL`). 이들은 JVM 테스트의 SDK 대역으로만 확인했다.

## 판정

| 경우 | `status` | `isError` |
| --- | --- | --- |
| 이름이 빈 값(공백 포함)이고 좌표가 (0, 0) | `NO_DESTINATION` | 없음 |
| 그 밖에 모든 값이 있음 | `COMPLETE` | 없음 |
| 그 밖에 일부 값이 없음 | `PARTIAL` | `true` |
| 호출 단위 실패 | 실패 코드 | `true` |

- `NO_DESTINATION`이면 시간·거리·도착 예상은 모두 `null`이다. 남은 시간이 있어도 쓰지 않는다. 빈 응답은 `sdk_emulator`(COM-8, COM-24)다.
- 이름·주소가 빈 값이면 그 필드가 `null`이다.
- `remainDistance` ≤ 0이면 `remainDistanceMeters`가 `null`이다.
- `remainTime` ≤ 0이면 `remainTimeSeconds`와 `estimatedArrivalAt`이 `null`이다. 0초를 곧 도착으로 보내지 않는다.
- `PARTIAL`에서도 확인된 값은 보낸다. `missing`에 `null`인 필드 이름을 적는다.
- SDK 필드는 null이 될 수 없는 `int`·`String`이다(`source_only`). 필드 자체가 없는 응답은 SDK 모델상 생기지 않는다. 그래서 "미제공"은 위 빈 값·0 이하 규칙으로만 판정한다. 실제 미지원 응답은 재현하지 못했다(COM-8 `NOT_RUN`).

## 시간

- 단위: `remainTime`은 초, `remainDistance`는 m다(navi-data 3.0.4 공식 주석, COM-8에서 화면과 일치).
- `receivedAt`: 앱이 콜백을 받은 UTC ISO 8601 시각. 내비가 값을 계산한 시각이 아니다. 측정 시각은 SDK가 주지 않아 보내지 않는다.
- `estimatedArrivalAt`: `receivedAt + remainTime초`를 UTC ISO 8601로 보낸 앱의 추정값이다. 교통 변화를 반영한 보장값이 아니다. 시간대 변환은 받는 쪽이 한다.
- 화면 표시(예: "2시간 24분")와는 반올림 때문에 다를 수 있다.

## MCP 도구와 응답

- 이름 `get_navigation_destination`. 입력 스키마는 빈 `properties`. 힌트는 차량 도구와 같다(`readOnlyHint: true`, `destructiveHint: false`, `idempotentHint: false`, `openWorldHint: false`). 목록 사례는 [fixtures/tool-list.json](fixtures/tool-list.json).
- 결과 글과 `structuredContent`의 사례는 [fixtures/tool-results.json](fixtures/tool-results.json).

**`structuredContent`**

| 결과 | 내용 |
| --- | --- |
| 조회됨 | `{ok:true, requestId, source, sdkVersion, status, receivedAt, destination, remainDistanceMeters, remainTimeSeconds, estimatedArrivalAt, missing}`. `destination`은 `{name, address}`, `NO_DESTINATION`이면 `null` |
| 호출 단위 실패 | `{ok:false, origin:"android", code, requestId}` |

`source`는 차량 계약과 같이 `pleos_emulator`, `real_vehicle`, `simulation` 중 하나다. 지금 앱은 `real_vehicle`을 보고하지 않는다.

**실패 코드**

| 코드 | 뜻 |
| --- | --- |
| `BUSY` | 다른 내비 조회가 진행 중 |
| `APP_NOT_READY` | Pleos 에뮬레이터가 아님 |
| `CONNECTION_STOPPED` | 연결 중지 중이거나 중지됨 |
| `NAVI_NOT_CONNECTED` | 내비 서비스 연결 전 또는 연결 실패 |
| `PREVIOUS_RESPONSE_PENDING` | 시간 초과한 이전 조회의 응답을 기다리는 중 |
| `PERMISSION_DENIED` | `SecurityException`(원인 체인 포함). `NAVI_ROUTE` 없음은 COM-8에서 `sdk_emulator` |
| `TIMEOUT` | 데드라인 안에 콜백 없음 |
| `READ_FAILED` | 그 밖의 SDK 예외. 예외 메시지는 내보내지 않는다 |

**`content` 글**

- 목적지 이름·주소, 남은 거리·시간, 추정 도착을 적고, 확인하지 못한 값은 "확인 못 함"으로 적는다. 좌표는 적지 않는다.
- 수신 시각이 계산 시각이 아니라고 알린다. 추정 도착이 있으면 추정이며 화면과 반올림이 다를 수 있다고 알린다.
- `isError`이면 확인하지 못한 값을 추정하지 말라고 알리고, 자동 재시도하지 않았다고 알린다.
- `source`가 `simulation`이면 실제 값이 아니라고 알린다.
- 목적지 값은 결과에만 들어가며 앱 로그에는 `requestId`, 상태 코드, 경과 시간만 남긴다.
