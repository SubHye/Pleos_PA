# 내비 계약

상태: **제안**. 조회는 COM-24 PR, [목적지 설정](#목적지-설정)은 COM-21 PR, [경유지](#경유지)는 COM-41 PR에서 상대 개발자 합의가 필요하다.

이 문서는 규칙만 적는다. 도구·한도 값은 [catalog.json](catalog.json), 고정 장소는 [places.json](places.json), 판정 사례는 [fixtures/](fixtures/)가 기준이다. 앱의 JVM 테스트가 이 파일들을 읽는다.

## 근거의 구분

| 표시 | 뜻 |
| --- | --- |
| `mock` | 사람이 쓴 사례. SDK 동작의 증거가 아니다 |
| `source_only` | NaviHelper 3.0.5 배포 AAR·공식 문서에서 읽은 경로. 실행하지 않았다 |
| `sdk_emulator` | Pleos 에뮬레이터에서 관찰한 실제 값. 그 환경에 한정한다 |

카탈로그의 `adoption`이 `adopted`인 도구만 `tools/list`에 올린다. `adopted`는 `verifiedOn` 환경의 제품 앱으로 실제 SDK를 실행해 순정 내비 화면과 대조했다는 뜻이다. 조회는 안내 없음·안내 중을, 목적지 설정은 설정·교체를 대조했다. SDK·내비 앱 버전이 바뀌면 `verifiedOn`을 비우고 `undecided`로 되돌린다.

## 범위

- 조회 대상은 NaviHelper 3.0.5의 `getDestinationInfo()` → `onDestinationInfo(DestinationInfo)` 한 가지다. 권한은 `pleos.car.permission.NAVI_ROUTE`다.
- 변경 대상은 `requestRoute(RouteInfo)` 한 가지이며 [고정 장소 목록](#고정-장소-목록)의 장소만 보낸다([목적지 설정](#목적지-설정)). 권한은 `NAVI_ROUTE_SEARCH`다. 호출 코드가 앱에 있으므로 도구가 `rejected`가 아닌 한 Manifest에 선언한다(lint가 요구). 도구 공개 여부는 `adoption`이 따로 정한다.
- 경유지 조회 `getWaypointInfo()` → `onWayPointInfo(List<WaypointInfo>)`, 추가 `addWaypoint(RequestWaypointInfo)`, 삭제 `removeWaypoint(WaypointIndex)`는 [경유지](#경유지)가 정한다. 추가·삭제는 고정 장소 목록의 장소만 쓴다. 권한은 `NAVI_ROUTE`와 `NAVI_ROUTE_SEARCH` 중 무엇인지 구분하지 못했다.
- 안내 취소(`cancelRoute`), 경유지 전체 삭제와 일괄 설정(`requestRouteWithWaypoints`), 경로 옵션, 재탐색, 지정 지점 Trip(`requestTrip`), 그 밖의 `get*Info`는 대상이 아니다. 변경 작업의 공통 규칙은 COM-11이 정한다.
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
| 동시 요청 | 내비 조회가 진행 중이면 `BUSY`. 차량 조회와 목적지 설정 작업은 막지 않는다. 설정 작업 중에 읽은 값은 변경 전 목적지이거나 목적지 없음일 수 있다(교체 중 약 5초, COM-36 `sdk_emulator`). 설정 작업의 확인용 재조회 응답이 이 조회의 답이 될 수 있으며, 그 값도 그 시점의 현재 목적지다 |
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

## 목적지 설정

상태: **제안** (COM-21). 두 도구로 나눈다(COM-21 결정 기록, 방식 C). 둘 다 차량 앱 MCP 서버의 도구이며 Pleos SDK API가 아니다. 한도 값은 [catalog.json](catalog.json)의 `limits`다.

| 도구 | 입력 | 하는 일 |
| --- | --- | --- |
| `find_navigation_place` | `query`(문자열, 필수) | [고정 장소 목록](#고정-장소-목록)에서 후보를 찾는다. SDK를 부르지 않고 내비를 바꾸지 않는다 |
| `set_navigation_destination` | `candidateId`(문자열, 필수) | 후보 ID의 목록 값으로 `requestRoute`를 부르고, 재조회로 반영을 확인한 뒤 답한다 |

두 도구는 함께 채택한다. 스키마 밖의 키, 빠진 값, 문자열이 아닌 값은 `Input validation error:`로 답하고 SDK를 부르지 않는다(FR-CON-02). 목록 사례는 [fixtures/tool-list.json](fixtures/tool-list.json), 결과 사례는 [fixtures/destination-tool-results.json](fixtures/destination-tool-results.json)이다.

**힌트**: `find_navigation_place`는 조회 도구와 같다. `set_navigation_destination`은 `readOnlyHint: false`, `destructiveHint: true`(기존 안내를 대체), `idempotentHint: false`, `openWorldHint: false`다.

### 사용자 재확인 없음

사용자의 명시적 내비 요청은 실행 의사로 본다(요구사항 정의서 v1.5, FR-NAV-05 삭제). 도구는 별도 승인을 요구하지 않고 아래 실행 조건만 검사한다. 사용자가 목적지 변경을 요청했을 때만 부르라고 도구 설명에 적는다. [ADR-0008](../../docs/decisions/0008-embedded-mcp-server.md)대로 MCP 서버에는 인증이 없으므로, 연결이 켜져 있는 동안 기기의 다른 앱도 이 도구로 목록 안의 장소를 목적지로 설정할 수 있다.

### 생명주기

하나의 호출은 COM-11의 변경 작업 하나이고, 그 작업의 동작은 목적지 설정 하나다. `requestId`(UUID)는 MCP 서버가 만든다.

1. `candidateId`를 목록에서 찾는다. 없으면 `UNKNOWN_CANDIDATE`로 끝낸다.
2. 작업을 접수한다. 변경 작업이 진행 중이면 `BUSY`, 중지됐으면 `CONNECTION_STOPPED`다. 큐는 없다.
3. 실행 조건을 순서대로 검사한다. 하나라도 걸리면 `requestRoute`를 부르지 않고 차단으로 끝낸다. 조건이 풀려도 자동으로 실행하지 않는다.
4. `requestRoute`를 한 번 부른다. `RouteInfo`는 [고정 장소 목록](#고정-장소-목록)의 구성 표를 따른다.
5. `confirmPollMs`마다 `getDestinationInfo()`를 보내고, 그동안 받은 모든 `onDestinationInfo`를 판정한다.
6. 좌표가 일치하는 응답이 하나 오면 `APPLIED`, `confirmTimeoutMs` 안에 없으면 `OUTCOME_UNKNOWN`으로 끝낸다.

어느 계층도 자동으로 다시 실행하지 않는다.

**실행 조건**

| 순서 | 조건 | 걸리면 |
| --- | --- | --- |
| 1 | 연결이 중지되지 않았다 | `CONNECTION_STOPPED` |
| 2 | Pleos 에뮬레이터다 | `APP_NOT_READY` |
| 3 | 내비 서비스에 연결됐다 | `NAVI_NOT_CONNECTED` |
| 4 | 현재 공개 Car UX가 DO=false/flags=0이고 현재 사용자의 DRIVER/MAIN 영역이 기본 display0임을 확인 | `DRIVING_RESTRICTED` 또는 `DRIVING_STATE_UNKNOWN` |
| 5 | 요청 전 목적지를 읽었다(`baselineDeadlineMs`, 조회 슬롯 사용) | `BASELINE_UNAVAILABLE`. 다른 조회가 진행 중일 때(`BUSY`)도 같다 |
| 6 | 요청 전 목적지가 요청한 장소가 아니다 | `ALREADY_SET`. 오류가 아니며 바꾼 것이 없다 |

### 반영 판정

근거는 COM-36(`sdk_emulator`)이다. 이 순정 내비는 `onRoutingCompleted`·`onDestinationChanged`를 보내지 않고 `isRouting`은 안내 중에도 `false`다. `requestRoute`의 반환과 `onRouteStarted`는 요청한 지점인지를 증명하지 못한다. 그래서 재조회 좌표만 쓴다.

- **일치**: 응답의 위도·경도가 각각 목록 값과 `matchToleranceDegrees` 안에 있다. 이름·주소는 쓰지 않는다. 순정 내비가 이름을 바꾸고 주소를 동 단위로 답하기 때문이다.
- **반영됨**은 "요청 전 목적지는 이 장소가 아니었고, 요청 뒤 응답 하나가 이 장소다"이다. 그래서 요청 전 조회가 조건이다.
- 빈 목적지, 다른 장소, 무응답은 어느 것도 실패의 근거가 아니다. 교체 중에는 약 5초 동안 빈 목적지가 온다. 제한 시간까지 일치가 없으면 **결과 불명**이며 실패로 단정하지 않는다.
- 콜백에는 요청 식별자가 없고 다른 앱의 조회 응답도 온다. 응답은 그 시점의 현재 목적지를 담으므로, 누가 조회했든 일치 응답은 근거로 쓴다.
- **잔여 위험:** 같은 시간에 다른 앱이나 사용자가 같은 장소를 설정하면 구분하지 못한다. 확인한 것은 목적지 좌표이며 안내가 계속되는지는 확인하지 않는다.

판정 사례는 [fixtures/set-destination-cases.json](fixtures/set-destination-cases.json)이다. 사례의 근거 표시는 모두 `mock`이며 COM-36 관찰 순서를 본뜬 것이다.

### 응답

**`structuredContent`**

| 결과 | 내용 |
| --- | --- |
| 장소 찾기 | `{ok:true, requestId, status, candidates}`. `status`는 `NO_MATCH`·`SINGLE`·`MULTIPLE`, `candidates`는 `{candidateId, name, address}`의 목록 순서 배열 |
| 설정 확인됨 | `{ok:true, requestId, source, sdkVersion, status, destination, elapsedMs}`. `status`는 `APPLIED`·`ALREADY_SET`, `destination`은 `{candidateId, name, address}`(목록 값) |
| 확인되지 않음 | `{ok:false, origin:"android", code, requestId, sent}` |

- 좌표는 어느 결과에도 넣지 않는다(NFR-05).
- `sent: false`는 `requestRoute`를 부르지 않았다는 뜻이다. 순정 내비는 그대로다.
- `sent: true`는 요청을 SDK에 넘겼고 효과를 모른다는 뜻이다. 성공도 실패도 아니며, 취소나 복원을 뜻하지 않는다(FR-CON-04, FR-NAV-06). 글은 다시 호출하지 말고 `get_navigation_destination`으로 확인하라고 알린다.
- `elapsedMs`는 도구가 요청을 받은 때부터 답할 때까지다.

**설정 코드**

| 코드 | `sent` | 뜻 |
| --- | --- | --- |
| `UNKNOWN_CANDIDATE` | false | 목록에 없는 후보 ID |
| `BUSY` | false | 다른 변경 작업이 진행 중. 그 작업은 계속된다 |
| `CONNECTION_STOPPED` | false | 접수 전 또는 조건 검사 중 중지 |
| `APP_NOT_READY`, `NAVI_NOT_CONNECTED` | false | 조회와 같은 뜻 |
| `BASELINE_UNAVAILABLE` | false | 요청 전 목적지를 읽지 못함 |
| `DRIVING_RESTRICTED` | false | DO 또는 활성 UX 제한이 있어 실행 전 차단 |
| `DRIVING_STATE_UNKNOWN` | false | 최신 UX/운전자 표시 영역 확인 실패 또는 검사 미연결 |
| `PERMISSION_DENIED` | false | `requestRoute`가 `SecurityException`(원인 체인 포함) |
| `OUTCOME_UNKNOWN` | true | 제한 시간 안에 일치 없음, 또는 `requestRoute`의 그 밖의 예외, 또는 응답 유실 |
| `CONNECTION_STOPPED` | true | 요청을 넘긴 뒤 중지 |

**중지**: 연결 중지는 변경 작업을 먼저 닫는다. 그 뒤로는 재조회도 보내지 않는다.

**로그**: 앱은 `requestId`, 도구 라벨, 상태 코드, 작업 상태(접수·검사 중·실행 중·성공·실패·차단), 경과 시간만 남긴다. 질의 원문, 후보 ID, 장소 이름·주소, 좌표는 남기지 않는다(NFR-05, FR-DAT-05).

### 에뮬레이터 관찰 (`sdk_emulator`, 2026-11-10)

`verifiedOn` 환경에서 제품 앱(debug, 두 도구 `adopted`)의 MCP 서버를 직접 호출했다. 실차와 다른 내비 버전으로 일반화하지 않는다. 남은 거리·시간은 에뮬레이터 위치를 드러낼 수 있어 적지 않는다.

| 항목 | 관찰 |
| --- | --- |
| 목록 | `tools/list` 23개. 두 도구의 스키마·힌트가 fixture와 같다 |
| 장소 찾기 | "판교역" `SINGLE`, "판교" `MULTIPLE`(2개), "부산역" `NO_MATCH`(빈 배열). 약 30ms, SDK 호출 없음 |
| 거절 | 모르는 후보 ID는 `UNKNOWN_CANDIDATE`, 스키마 밖 키와 빠진 인자는 `Input validation error`. 앞뒤 조회 모두 목적지 없음 |
| 설정 | 목적지 없음 → 판교역, 판교역 → 코엑스, 코엑스 → 서울역 모두 `APPLIED`. 매번 순정 화면의 목적지 이름·남은 시간·거리가 직후 `get_navigation_destination` 값과 같다 |
| 소요 시간 | `elapsedMs` 7025, 8028, 9022. 세 번 모두 5초를 넘는다. 안내 중일 때는 요청 전 조회에 약 1초가 든다 |
| 작업 상태 | 로그에 접수 → 검사 중 → 실행 중 → 성공이 `requestId`와 함께 남는다 |
| 동시 요청 | 설정이 진행되는 동안 다른 장소 설정을 부르면 `BUSY`. 진행 중이던 작업은 `APPLIED`로 끝났고 `requestRoute` 로그는 한 번뿐이다 |
| 이미 설정됨 | 코엑스 안내 중 코엑스를 다시 부르면 62ms에 `ALREADY_SET`. 작업은 차단으로 끝나고 `requestRoute` 로그가 없다 |
| 이름 | 장소 ID를 넣은 서울역은 재조회 이름이 "서울역 KTX"다. 판정은 좌표로 했다 |
| 앱 유지 | `requestRoute` 때 순정 내비가 앞으로 뜬다. 요청 2분 20초쯤 뒤에도 MCP 서버가 답했고 프로세스는 동결되지 않았다(Foreground Service). COM-36의 백그라운드 샘플 앱 동결은 재현되지 않았다 |
| 복구 | 끝에 `tools/nav-place-check`의 `cancel`로 안내를 취소했고 목적지 없음을 확인했다 |

실제 SDK로 재현하지 않은 것: `OUTCOME_UNKNOWN`, `PERMISSION_DENIED`, `BASELINE_UNAVAILABLE`, `NAVI_NOT_CONNECTED`, 작업 중 연결 중지, `onError`, 좌표가 허용 오차 안에서 다르게 돌아오는 경우. 이들은 JVM 테스트의 SDK 대역으로만 확인했다. 외부 AI 클라이언트를 거친 종단간 실행도 하지 않았다(COM-27).

### 한계

- 주행 규제는 서비스 수명의 `CarNavigationGuard`가 실제 Car UX/occupant 매핑을 매번 새로 읽어 검사한다. DO 또는 하나라도 있는 제한은 차단하고, 초기 연결 중/정보 상실/권한·API 오류/운전자 MAIN 영역 미확인은 기본 차단한다. Activity가 가려져도 검사는 유지된다. 사전 조회 완료 뒤와 SDK 호출 직전에도 재검사하며, 해제 시 자동 재실행하지 않는다. 가드가 통과(`null`)를 돌려주면 서비스는 그 값을 그대로 쓰고, 가드가 없을 때만 `DRIVING_STATE_UNKNOWN`으로 막는다(`drivingBlockCode`). 처음 연결한 코드는 가드의 통과를 "가드 없음"으로 바꿔 모든 변경을 막았고, 2026-11-12 에뮬레이터에서 발견해 고쳤다(경유지 절의 "가드 수정 후 관찰"). 고친 뒤 에뮬레이터 주차 상태에서 목적지 설정이 통과했다(2회 `APPLIED`). 주행 중 차단과 실제 Pleos 기기 검증은 아직 수행하지 않았으므로 과거 목적지 설정 실측을 이 변경의 AT-11 통과로 재사용하지 않는다.
- 설정 한 번에 7.0~9.0초가 걸렸다(위 관찰). 도구 시간만으로 종단간 5초 목표(요구사항 6.2)를 넘는다.
- 결과를 차량 화면이나 음성으로 알리지 않는다. 되돌리기는 없다(COM-44).

## 경유지

상태: **제안** (COM-41). 세 도구는 제품 앱의 MCP 서버를 에뮬레이터에서 직접 호출해 확인한 뒤 `adopted`로 올렸다([제품 앱 관찰](#제품-앱-관찰-sdk_emulator-2026-11-11)). 그 아래 관찰은 COM-47의 조사 도구(`tools/nav-place-check`의 `WaypointActivity`)로 얻은 것이며 제품 앱의 결과가 아니다. 한도 값은 [catalog.json](catalog.json)의 `limits`다.

| 도구 | 입력 | 하는 일 |
| --- | --- | --- |
| `get_navigation_waypoints` | 없음 | 현재 경유지를 설정된 순서대로 조회한다 (`getWaypointInfo`, `NAVI_ROUTE`) |
| `add_navigation_waypoint` | `candidateId`(문자열, 필수) | 후보 ID의 목록 값을 경유지로 추가하고, 재조회로 반영을 확인한 뒤 답한다 (`addWaypoint`, `NAVI_ROUTE_SEARCH`) |
| `remove_navigation_waypoint` | `candidateId`(문자열, 필수) | 그 장소를 경유지에서 삭제하고, 재조회로 반영을 확인한 뒤 답한다 (`removeWaypoint`, `NAVI_ROUTE_SEARCH`) |

세 도구는 함께 채택한다. 스키마 밖의 키, 빠진 값, 문자열이 아닌 값은 `Input validation error:`로 답하고 SDK를 부르지 않는다. 경유지에 쓰는 장소도 [고정 장소 목록](#고정-장소-목록)의 장소뿐이다. 목록에 없는 장소는 경유지로 쓰지 않는다. 힌트는 목적지 설정과 같다. 경유지 API가 요구하는 권한(`NAVI_ROUTE`와 `NAVI_ROUTE_SEARCH` 중)은 구분하지 못했다. COM-47은 두 권한을 모두 선언한 앱으로만 실행했다. 카탈로그의 권한은 읽기와 변경의 분류를 따른 값이다.

목록 사례는 [fixtures/tool-list.json](fixtures/tool-list.json)이다. 전체 삭제(`removeWaypoint(ALL)`)와 일괄 설정(`requestRouteWithWaypoints`)은 대상이 아니다. 후자는 기존 경유지를 덮어쓰고 재조회 좌표가 (0, 0)이라 기존 경유지를 되돌려 보낼 수 없다.

### 읽기 값

`onWayPointInfo(List<WaypointInfo>)`의 이름·주소·`poiId`만 쓴다. 순정 내비는 경유지 좌표를 (0, 0), `poiSubId`를 null로 답하므로 좌표로 대조할 수 없다(`sdk_emulator`, COM-47). 경유지는 `poiId`와 이름으로만 구분한다.

- 목록의 장소가 경유지로 읽혔는지: `poiId`가 있는 장소는 `poiId`가 같을 때, 없는 장소는 보낸 이름과 같을 때다. 공백은 지우고 소문자로 바꿔 비교한다.
- 두 번의 읽기가 같은 경유지를 보이는지: 둘 다 `poiId`가 있으면 `poiId`로, 아니면 이름으로 본다.
- `onWayPointInfo`에도 요청 식별자가 없고, 같은 서비스에 등록된 모든 클라이언트가 받는다. 늦은 응답과 `PREVIOUS_RESPONSE_PENDING`은 목적지 조회와 같은 규칙이며 경유지 슬롯이 따로 있다. 슬롯은 목적지·차량 조회와 막지 않는다.

### 생명주기

하나의 호출은 COM-11의 변경 작업 하나이고, 그 작업의 동작은 경유지 추가 또는 삭제 하나다. 목적지 설정과 같은 조정기를 쓰므로 어느 변경이든 하나가 진행 중이면 다른 변경은 `BUSY`다. `requestId`(UUID)는 MCP 서버가 만든다.

1. `candidateId`를 목록에서 찾는다. 없으면 `UNKNOWN_CANDIDATE`로 끝낸다.
2. 작업을 접수한다. 변경 작업이 진행 중이면 `BUSY`, 중지됐으면 `CONNECTION_STOPPED`다. 큐는 없다.
3. 실행 조건을 순서대로 검사한다. 하나라도 걸리면 SDK의 변경을 부르지 않고 차단으로 끝낸다. 조건이 풀려도 자동으로 실행하지 않는다.
4. `addWaypoint` 또는 `removeWaypoint`를 한 번 부른다.
5. `confirmPollMs`마다 `getWaypointInfo()`와 `getDestinationInfo()`를 보내고, 그동안 받은 모든 `onWayPointInfo`와 `onDestinationInfo`를 판정한다.
6. 판정이 맞으면 `APPLIED`, `confirmTimeoutMs` 안에 맞지 않으면 `OUTCOME_UNKNOWN`으로 끝낸다.

어느 계층도 자동으로 다시 실행하지 않는다.

**실행 조건**

| 순서 | 조건 | 걸리면 |
| --- | --- | --- |
| 1 | 연결이 중지되지 않았다 | `CONNECTION_STOPPED` |
| 2 | Pleos 에뮬레이터다 | `APP_NOT_READY` |
| 3 | 내비 서비스에 연결됐다 | `NAVI_NOT_CONNECTED` |
| 4 | 현재 공개 Car UX가 DO=false/flags=0이고 현재 사용자의 DRIVER/MAIN 영역이 기본 display0임을 확인. 목적지 설정과 같은 `CarNavigationGuard`를 쓴다 | `DRIVING_RESTRICTED` 또는 `DRIVING_STATE_UNKNOWN` |
| 5 | 요청 전 목적지를 읽었다(`baselineDeadlineMs`) | `BASELINE_UNAVAILABLE` |
| 6 | 안내 중인 목적지가 있다 | `NO_DESTINATION`. 순정 내비는 목적지가 없으면 경유지를 조용히 무시한다 |
| 7 | 요청 전 경유지를 읽었다(`baselineDeadlineMs`) | `WAYPOINTS_UNAVAILABLE` |
| 8a | 추가: 그 장소가 아직 경유지가 아니다 | `ALREADY_WAYPOINT`. SDK는 같은 장소의 중복 추가를 막지 않는다 |
| 8b | 추가: 경유지가 `maxWaypoints`개 미만이다 | `WAYPOINT_LIMIT`. 세 번째는 오류 없이 무시된다 |
| 9a | 삭제: 그 장소가 경유지에 있다 | `WAYPOINT_NOT_FOUND` |
| 9b | 삭제: 그 장소가 경유지에 한 번만 나온다 | `AMBIGUOUS_WAYPOINT` |
| 9c | 삭제: 그 장소가 첫 번째나 두 번째다 | `WAYPOINT_POSITION_UNSUPPORTED` |

**보내는 값**

- 추가: 목록 값으로 `RequestWaypointInfo`를 구성한다(`poiSubId`·`poiProvider`는 `""`). 위치는 끝에 붙인다: 경유지가 없으면 `FIRST`, 하나 있으면 `SECOND`. 이 두 경우만 관찰했다.
- 삭제: 요청 직전에 읽은 목록에서 그 장소의 위치를 찾아 `FIRST` 또는 `SECOND`를 보낸다. 읽기와 호출 사이에 경유지가 바뀌면 확인이 맞지 않아 결과 불명이 된다.

### 반영 판정

순정 내비는 쓸 만한 완료 신호를 보내지 않는다. 추가·삭제 때 `onWaypointChanged`는 약 6~7초 늦게 오고 `index`가 요청과 무관하게 항상 `FIRST`다(`sdk_emulator`, COM-47). 그래서 재조회만 쓴다.

- **추가됨**: 읽은 경유지가 요청 전보다 하나 많고, 그 장소가 들어 있고, 요청 전의 경유지가 모두 그대로 있다. 그리고 읽은 목적지의 좌표가 요청 전 목적지와 허용 오차 안에서 같다.
- **삭제됨**: 읽은 경유지가 요청 전보다 하나 적고, 그 장소가 없고, 나머지 경유지가 모두 그대로 있다. 그리고 목적지가 그대로다.
- 응답이 없거나 조건 일부만 맞는 것은 어느 쪽의 근거도 아니다. 제한 시간까지 맞지 않으면 **결과 불명**이며 실패로 단정하지 않는다.
- 두 읽기는 서로 다른 콜백이라 최신 응답 하나씩을 모아 함께 본다. 목적지가 잠시 비어 있다가 돌아오면 돌아온 뒤에 판정한다.
- **잔여 위험:** 같은 시간에 다른 앱이나 사용자가 경유지를 바꾸면 구분하지 못한다.

### 응답

**`structuredContent`**

| 결과 | 내용 |
| --- | --- |
| 조회됨 | `{ok:true, requestId, source, sdkVersion, status, receivedAt, waypoints}`. `status`는 `NO_WAYPOINTS`·`HAS_WAYPOINTS`, `waypoints`는 `{order, name, address, poiId, candidateId}` 배열이다. `candidateId`는 고정 장소 목록의 장소일 때만 값이 있고, 순정 화면에서 직접 넣은 경유지는 `null`이다 |
| 변경 확인됨 | `{ok:true, requestId, source, sdkVersion, status:"APPLIED", waypoint, elapsedMs}`. `waypoint`는 `{candidateId, name, address}`(목록 값) |
| 확인되지 않음 | `{ok:false, origin:"android", code, requestId, sent}` |
| 조회 실패 | 목적지 조회와 같은 `{ok:false, origin:"android", code, requestId}` |

- 좌표는 어느 결과에도 넣지 않는다(NFR-05).
- `sent: false`는 `addWaypoint`·`removeWaypoint`를 부르지 않았다는 뜻이다. 순정 내비는 그대로다.
- `sent: true`는 요청을 SDK에 넘겼고 효과를 모른다는 뜻이다. 성공도 실패도 아니며, 취소나 복원을 뜻하지 않는다(FR-CON-04, FR-NAV-06). 글은 다시 호출하지 말고 `get_navigation_waypoints`로 확인하라고 알린다.
- 경유지 값은 결과에만 들어가며 앱 로그에는 `requestId`, 도구 라벨, 상태 코드, 작업 상태, 경과 시간만 남긴다.

**변경 코드** (조회 코드는 위 [MCP 도구와 응답](#mcp-도구와-응답)와 같다)

| 코드 | `sent` | 뜻 |
| --- | --- | --- |
| `UNKNOWN_CANDIDATE` | false | 목록에 없는 후보 ID |
| `BUSY` | false | 다른 변경 작업이 진행 중. 그 작업은 계속된다 |
| `CONNECTION_STOPPED` | false | 접수 전 또는 조건 검사 중 중지 |
| `APP_NOT_READY`, `NAVI_NOT_CONNECTED` | false | 조회와 같은 뜻 |
| `BASELINE_UNAVAILABLE`, `WAYPOINTS_UNAVAILABLE` | false | 요청 전 목적지 또는 경유지를 읽지 못함 |
| `DRIVING_RESTRICTED`, `DRIVING_STATE_UNKNOWN` | false | 목적지 설정과 같은 뜻. 규제 검사가 연결되지 않았으면 `DRIVING_STATE_UNKNOWN`으로 차단한다 |
| `NO_DESTINATION`, `ALREADY_WAYPOINT`, `WAYPOINT_LIMIT` | false | 추가 조건에 걸림 |
| `WAYPOINT_NOT_FOUND`, `AMBIGUOUS_WAYPOINT`, `WAYPOINT_POSITION_UNSUPPORTED` | false | 삭제 조건에 걸림 |
| `PERMISSION_DENIED` | false | `addWaypoint`·`removeWaypoint`가 `SecurityException`(원인 체인 포함) |
| `OUTCOME_UNKNOWN` | true | 제한 시간 안에 판정이 맞지 않음, 또는 호출의 그 밖의 예외, 또는 응답 유실 |
| `CONNECTION_STOPPED` | true | 요청을 넘긴 뒤 중지 |

**중지**: 연결 중지는 변경 작업을 먼저 닫는다. 그 뒤로는 재조회도 보내지 않는다.

### 제품 앱 관찰 (`sdk_emulator`, 2026-11-11)

`verifiedOn` 환경에서 제품 앱(debug, 세 도구 `adopted`, 주행 규제 가드가 병합되기 전의 빌드)의 MCP 서버를 `adb forward`로 직접 호출했다. 장소는 서울역(목적지), 코엑스, 판교역, 현대백화점 판교점이다. 실차와 다른 내비 버전으로 일반화하지 않는다. 남은 거리·시간은 에뮬레이터 위치를 드러낼 수 있어 적지 않는다.

| 항목 | 관찰 |
| --- | --- |
| 목록 | `tools/list` 26개. 세 도구가 fixture 순서대로 나온다 |
| 기준 | 목적지 없음, `NO_WAYPOINTS`. 목적지 없을 때 코엑스 추가는 6ms에 `NO_DESTINATION`, `sent:false`이고 조회는 여전히 `NO_WAYPOINTS` |
| 추가 | 서울역 안내 중 코엑스 → `APPLIED`(조회에 코엑스, `candidateId` `coex`). 이어서 판교역 → `APPLIED`(코엑스, 판교역 순서). 순정 화면 진행 막대에 경유 점 2개 |
| 삭제 | 두 번째(판교역) → `APPLIED`, 코엑스만 남음. 판교역을 다시 추가한 뒤 첫 번째(코엑스) → `APPLIED`, 판교역만 남음. 두 번째 삭제는 COM-47에서 실행하지 않은 조합이다 |
| 차단 | 같은 장소 추가 `ALREADY_WAYPOINT`, 세 번째 추가 `WAYPOINT_LIMIT`, 없는 장소 삭제 `WAYPOINT_NOT_FOUND`. 모두 `sent:false`이고 약 2초(요청 전 읽기 두 번)에 끝났으며 조회한 경유지는 그대로였다 |
| 동시 요청 | 추가가 진행되는 동안 삭제를 부르면 0ms에 `BUSY`. 진행 중이던 추가는 `APPLIED`로 끝났다 |
| 소요 시간 | 변경 8회의 `elapsedMs`는 9872~11040(대부분 약 10.0초). 요청 전 읽기 약 2초를 빼면 요청 뒤 반영 확인까지 약 8~9초. 당시 `confirmTimeoutMs` 15초 안이다 |
| 읽은 값 | 코엑스는 `poiId`가 있고, 판교역·현대백화점 판교점은 `poiId`가 없다(`null`). 주소는 동 수준이다 |
| 작업 상태 | 로그에 접수 → 검사 중 → 실행 중 → 성공이, 차단은 접수 → 검사 중 → 차단이 `requestId`와 함께 남는다. 로그에 후보 ID·장소 이름·주소·좌표는 없다 |
| 복구 | 남은 경유지를 `remove_navigation_waypoint`로 지우고 `tools/nav-place-check`의 `cancel`로 안내를 취소했다. 목적지 없음과 `NO_WAYPOINTS`를 조회로 확인했다 |

실제 SDK로 재현하지 않은 것(이 실행 기준): `OUTCOME_UNKNOWN`(2026-11-12에 1회 관찰, 아래 "가드 수정 후 관찰"), `PERMISSION_DENIED`, `BASELINE_UNAVAILABLE`, `WAYPOINTS_UNAVAILABLE`, `NAVI_NOT_CONNECTED`, 작업 중 연결 중지, 목적지 설정과 경유지 변경 사이의 `BUSY`(2026-11-12에 확인, 아래 "가드 수정 후 관찰"), 경유지 도착 이벤트, 주행 중 변경, 두 경유지가 같은 장소일 때의 `AMBIGUOUS_WAYPOINT`. 이들은 JVM 테스트의 SDK 대역으로만 확인했거나 확인하지 않았다. 외부 AI 클라이언트를 거친 종단간 실행도 하지 않았다(COM-27). `NAVI_ROUTE`와 `NAVI_ROUTE_SEARCH`는 두 권한을 모두 선언한 앱으로만 실행해 구분하지 못했다.

### 가드 수정 후 관찰 (`sdk_emulator`, 2026-11-12)

`verifiedOn`과 같은 환경을 Cold boot로 다시 켜고, 주행 규제 가드를 병합한 제품 앱(debug)을 MCP로 직접 호출했다. 차량은 주차 상태이고 Car UX는 display 0에서 DO=false, 제한 0, 사용자 10이 운전자 영역·MAIN display 0이었다.

| 항목 | 관찰 |
| --- | --- |
| 가드 연결 결함 | 병합한 그대로의 빌드는 목적지 없을 때 경유지 추가에 `NO_DESTINATION` 대신 `DRIVING_STATE_UNKNOWN`(`sent:false`, 8ms)을 돌려줬다. 임시 진단 로그로 가드 자체는 통과(`null`)를 돌려줬음을 확인했다. 원인은 서비스의 `driving?.blockCode() ?: DRIVING_STATE_UNKNOWN`이 통과를 "가드 없음"으로 바꾼 것이며, 목적지 설정과 경유지 변경 모두 항상 막혔다. `drivingBlockCode`로 고쳤고 JVM 테스트를 더했다. 진단 로그는 커밋하지 않았다 |
| 고친 뒤 기준 | 목적지 없을 때 코엑스 추가 → `NO_DESTINATION`, `sent:false` |
| 첫 실행 | 서울역 목적지 `APPLIED`(11.0초). 이어서 코엑스 추가가 `OUTCOME_UNKNOWN`(`sent:true`, 17.0초)으로 끝났다. 응답 1초 뒤 조회는 `NO_WAYPOINTS`였고 응답 약 19초 뒤 조회에 코엑스가 있었다. 순정 내비가 당시 확인 제한 시간 15초보다 늦게 반영한 것이며, 앱은 단정하지 않고 `OUTCOME_UNKNOWN`으로 답했다. 이어서 복구용 코엑스 삭제는 `APPLIED`(13.1초) |
| 반복 측정 | 목적지 서울역 `APPLIED`(9.1초) 뒤 코엑스 추가 → 판교역 추가 → 판교역 삭제 → 코엑스 삭제를 3회. 12회 모두 `APPLIED`, `elapsedMs` 8965~11022. `OUTCOME_UNKNOWN` 없음 |
| 확인 제한 시간 | 위 관찰로 `confirmTimeoutMs`를 15초에서 30초로 올렸다(정상 범위 약 9~11초의 약 3배, 15초를 넘긴 1회를 포함). 느린 1회의 원인은 확정하지 못했다(Cold boot 직후 첫 경유지 변경이었다) |
| 30초 빌드 | 목적지 서울역 `APPLIED`(9.0초), 코엑스·판교역 추가 `APPLIED`(각 10.0초). 이어서 같은 장소 추가 `ALREADY_WAYPOINT`, 세 번째 추가 `WAYPOINT_LIMIT`, 없는 장소 삭제 `WAYPOINT_NOT_FOUND`. 셋 다 `sent:false`, 약 2.0초이고 조회한 경유지는 그대로였다 |
| 동시 요청 | 판교역 삭제가 실행 중일 때 경유지 추가 → 2ms에 `BUSY`. 코엑스 삭제가 실행 중일 때 `set_navigation_destination` → 1ms에 `BUSY`(목적지 설정과 경유지 변경이 조정기 하나를 쓴다). 두 삭제는 모두 `APPLIED`(각 약 10.0초) |
| 복구 | 매 실행 끝에 남은 경유지를 지우고 `tools/nav-place-check`의 `cancel`로 안내를 취소했다. 목적지 없음과 `NO_WAYPOINTS`를 조회로 확인했다 |

가드 수정 뒤에도 실행하지 않은 것: 주행 중 차단(`DRIVING_RESTRICTED`), AT-11, 30초 확인 시간 안에서 늦게 반영되는 경우(이번 30초 빌드 실행에서는 `OUTCOME_UNKNOWN`이 나오지 않았다).

### 조사 도구 관찰 (`sdk_emulator`, 2026-11-11, `WaypointActivity`)

`verifiedOn`과 같은 환경이다. 제품 앱이 아니라 COM-47의 조사 도구로 얻은 값이다. 각 시나리오 1회 관찰이며 실차와 다른 내비 버전으로 일반화하지 않는다.

| 항목 | 관찰 |
| --- | --- |
| 목적지 없을 때 추가 | 오류·이벤트 없이 무시됨. 8초 뒤에도 0개 |
| 추가 | 목적지 서울역에 코엑스 `FIRST`, 이어서 판교역 `SECOND` → 재조회 1개, 2개. 목적지 유지 |
| 세 번째 추가 | 오류 없이 무시됨. 2개 그대로 |
| 중복 추가 | 같은 장소(판교역)가 2개로 늘어남 |
| 삭제 | `removeWaypoint(FIRST)` → 코엑스가 지워지고 판교역이 남음. `removeWaypoint(ALL)` → 0개, 목적지 유지 |
| 읽은 값 | 경유지의 좌표는 (0, 0), `poiSubId`는 null. 장소 ID가 빈 장소는 이름만 남는다 |
| 이벤트 | `onRouteCancelled`가 먼저 오고 약 6~7초 뒤 `onWaypointChanged`와 `onRouteEnded`. `onWaypointChanged`의 `index`는 항상 `FIRST` |
| 화면 | 경로 카드의 시간·거리가 SDK 값과 같고 진행 막대에 경유 지점이 보임 |

실행하지 않은 것: 두 번째 경유지 삭제(제품 앱으로는 위에서 확인), 경유지 도착 이벤트(`onWayPointArrived`), 주행 중 변경, 제품 앱(MCP)에서의 실행, 외부 AI 클라이언트를 거친 종단간 실행(COM-27), `NAVI_ROUTE`와 `NAVI_ROUTE_SEARCH`의 구분. 이들은 JVM 테스트의 SDK 대역으로만 확인했거나 확인하지 않았다.

### 한계

- 주행 규제는 목적지 설정과 같은 `CarNavigationGuard`가 검사한다. 읽기 전, 읽기 뒤, SDK 호출 직전에 다시 묻고, 규제나 정보 상실은 차단하며 해제돼도 자동으로 다시 실행하지 않는다. 가드를 거친 추가·삭제는 에뮬레이터 주차 상태에서 통과했다("가드 수정 후 관찰"). 주행 중 차단과 AT-11은 아직 수행하지 않았다.
- 변경 한 번에 약 10초가 걸리고, 한 번은 15초를 넘겼다(위 관찰). 순정 내비가 경로를 다시 만들기 때문이며 도구 시간만으로 종단간 5초 목표(요구사항 6.2)를 넘는다.
- 경유지는 2개까지다. 이보다 많은 경유지는 `requestRouteWithWaypoints`로만 들어가며(COM-47에서 3개가 반영됨) 이 앱은 그 호출을 쓰지 않는다. 3개 이상일 때 세 번째 이후는 삭제할 수 없다(`WAYPOINT_POSITION_UNSUPPORTED`).
- 결과를 차량 화면이나 음성으로 알리지 않는다. 되돌리기는 없다(COM-44).

## 고정 장소 목록

상태: **제안** (COM-52). 두 개발자 합의(2026-11-09)대로 1차 목적지는 에뮬레이터에서 검증한 [places.json](places.json)의 장소만 쓴다. 이 절은 목록과 대조 규칙을 정한다. 도구는 [목적지 설정](#목적지-설정)이 정한다. 장소 검색 API로 바꾸는 일은 COM-53이다.

**규칙**

- **목록에 없는 장소는 목적지로 설정하지 않는다.** 대조 결과가 없음이면 SDK를 부르지 않고, 목록 전체를 대신 돌려주지 않는다(FR-APT-01, AT-06). 두 도구의 설명에도 같은 문장을 쓴다.
- AI는 좌표를 만들거나 옮기지 않는다. 장소 값은 차량 앱이 목록에서 꺼낸다.
- 목록에는 검증된 장소만 둔다. 각 항목은 `evidence.status`가 `sdk_emulator`이고 실행 기록이 2회 이상이며 모두 `PASS`다. JVM 테스트(`PlaceCatalogTest`)가 이를 강제한다.

**항목**

| 필드 | 뜻 |
| --- | --- |
| `key` | 후보 ID. `set_navigation_destination`의 `candidateId`다 |
| `displayName`, `aliases` | 대조 대상. 영문·한글 표기를 별칭으로 둔다 |
| `address` | 순정 내비 장소 상세 화면의 도로명 주소. `RouteInfo.address`로 보낸다 |
| `latitude`, `longitude` | 순정 내비 `getDestinationInfo()`가 준 좌표(`coordinateSource: navi_capture`) |
| `poiId`, `poiSubId` | 순정 내비가 직접 준 값일 때만 넣는다. 외부 지도 서비스 ID는 넣지 않는다 |
| `capture` | 좌표를 얻은 순정 내비 검색과 그때 읽은 이름·좌표·장소 ID |
| `evidence.runs` | 실행 날짜, 빌드, 에뮬레이터 환경, 요청 장소 ID, 재조회 값, 취소 뒤 상태, 판정 |

**`RouteInfo` 구성** (NaviHelper 3.0.5, Kotlin 이름 인자)

| 인자 | 값 |
| --- | --- |
| `latitude`·`longitude`, `displayLatitude`·`displayLongitude` | 목록 좌표 |
| `poiName` | `displayName` |
| `address` | `address` |
| `poiId` | 목록 값. 없으면 `""` |
| `poiSubId` | `""`. 순정 내비가 `null`로 답해 넣을 값이 없다 |
| `routeOption`, `poiProvider`, `isMobileRequest` | 기본값 |

**대조 규칙** (`PlaceCatalog.find`, 사례는 [fixtures/place-cases.json](fixtures/place-cases.json))

1. 질의와 표시명·별칭에서 모든 공백을 지우고 소문자로 바꾼다(`Locale.ROOT`).
2. 정규화한 질의가 비면 없음이다.
3. 질의가 표시명·별칭을 포함하거나 표시명·별칭이 질의를 포함하면 후보다. 유사도 검색은 하지 않는다.
4. 후보 수에 따라 `NO_MATCH`, `SINGLE`, `MULTIPLE`이다. 후보는 목록 순서다.
5. AI는 한 개면 설정하고, 여러 개면 사용자에게 묻고, 없음이면 설정하지 않고 알린다. 일정 문자열의 층·회의실 같은 군더더기는 AI가 덜어 장소 부분만 질의한다.
6. 질의는 일정에서 나온 값일 수 있으므로 앱 로그·호출 기록에 원문을 남기지 않는다(NFR-05).

**에뮬레이터 관찰** (`sdk_emulator`, 2026-11-10, 판독 앱 `tools/nav-place-check`)

`verifiedOn`과 같은 환경이다. 실차와 다른 내비 버전으로 일반화하지 않는다.

| 항목 | 관찰 |
| --- | --- |
| 좌표 | 4곳 × 2회 모두 재조회 좌표가 목록과 같다(차이 0). 장소 ID를 넣은 곳(서울역·코엑스)과 비운 곳(판교역·현대백화점 판교점) 모두 같다 |
| 이름 | 장소 ID를 넣으면 순정 내비 이름("서울역 KTX"), 비우면 보낸 `poiName`("판교역")으로 읽힌다 |
| 장소 ID | 넣은 ID가 그대로 읽히고, 비우면 빈 값이다 |
| 주소 | 보낸 도로명이 아니라 동 수준("용산구 동자동")으로 읽힌다. 판정에 쓰지 않는다 |
| `poiSubId` | 항상 `null` |
| 화면 | 안내 중 순정 화면의 목적지 이름·남은 시간·거리가 SDK 값과 같다. 거리 값은 에뮬레이터 위치를 드러낼 수 있어 적지 않는다 |
| 취소 | 매번 `cancelRoute` 뒤 목적지 없음 |

확인하지 않은 것: 좌표와 장소 ID가 서로 다른 곳을 가리킬 때의 동작(COM-8에서 ID 우선 관찰), 실차, 다른 순정 내비 버전, 목록 장소의 순정 내비 장소 ID가 바뀌는 경우.
