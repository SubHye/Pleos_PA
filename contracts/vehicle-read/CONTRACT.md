# 차량 조회 계약

상태: **채택** ([ADR-0004](../../docs/decisions/0004-vehicle-read-contract.md)).

이 문서는 규칙만 적는다. 속성·도구·한도의 값은 [catalog.json](catalog.json), 판정 사례는 [fixtures/](fixtures/)가 기준이다. 앱의 JVM 테스트가 이 파일들을 읽는다. COM-12 전까지는 Node MCP 서버의 테스트도 같은 파일을 읽었다.

## 근거의 구분

| 표시 | 뜻 |
| --- | --- |
| `mock` | 사람이 쓴 사례. SDK 동작의 증거가 아니다 |
| `source_only` | SDK 2.3.2 소스에서 읽은 경로. 실행하지 않았다 |
| `sdk_emulator` | Pleos 에뮬레이터에서 관찰한 실제 값. 그 환경에 한정한다 |

카탈로그에 있어도 동작을 보장하지 않는다. 실행 결과는 카탈로그에 다음과 같이 기록하고, `adoption`이 `adopted`인 도구만 `tools/list`에 올린다.

| 속성 `verification` | 뜻 |
| --- | --- |
| `none` | 실행 근거가 없다. `NOT_READ`나 콜백 없음으로 끝난 속성도 여기 머문다 |
| `executed` | `verifiedOn` 환경의 실제 SDK로 실행해 status로 분류했다. 값 일치를 뜻하지 않는다 |
| `console_matched` | Virtual Console 값을 바꿔 SDK 응답과 일치함을 확인했다 |
| `console_mismatch` | Console 값은 바뀌었지만 SDK 응답이 달랐다 |

| 도구 `adoption` | 뜻 |
| --- | --- |
| `undecided` | 판정 전이다 |
| `adopted` | 모든 속성이 `none`이 아니고(`noCapabilityApi` 속성은 제외), 인자 없는 전체 호출에서 `OK` 항목이 1개 이상이며, 호출이 TIMEOUT 없이 끝났다. `console_mismatch` 속성이 있으면 `adoptionNote`에 원인을 적는다 |
| `rejected` | 위 조건을 만족하지 못했다. `adoptionNote`에 이유를 적는다. 기존 4개는 이 값을 쓰지 않고, 미달이면 `undecided`로 두고 실패로 보고한다 |

`noCapabilityApi: true`는 SDK에 공개 capability API가 없어 Adapter가 읽지 않는 속성이다. 응답은 항상 `CAPABILITY_FAILED`이고 `verification`은 `none`으로 둔다. 도구에 `OK` 항목이 있어도 이 속성의 값을 얻었다고 보지 않는다.

값은 전수 실행 보고서로만 올린다. SDK나 에뮬레이터 버전이 바뀌면 `verifiedOn`을 비우고 전부 `none`과 `undecided`로 되돌리며, 해당 Adapter 호출을 바꾸면 그 속성을 `none`으로 되돌린다.

## 범위

대상은 Vehicle SDK 2.3.2의 공개 일회성 조회다. 제외한 항목과 사유는 카탈로그의 `excluded`, `excludedOperations`에 있다.

- 제어, 구독, 비공개·Deprecated API, 내비게이션(COM-11)은 대상이 아니다.
- VIN은 요구사항 부록 B.1에 따라 제외한다. SDK가 조회 값을 logcat에 남기는 경로이기도 하다.
- 필수 도구는 기존 4개다(요구사항 3.4.3). 나머지 18개는 검증 후 채택하는 후보다.
- 카탈로그는 SDK 2.3.2 소스·클래스와 [API Reference](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/entry)로 대조했다. API Reference는 소스 주석에서 생성된 문서로 보이므로 일치해도 실제 동작의 증거가 아니다.

## 요청 생명주기

1. 앱의 MCP 서버가 도구 호출마다 데드라인을 시작하고 `requestId`(UUID)를 만든다. 하나의 도구 호출은 게이트웨이 조회 1회다.
2. 서버가 도구와 인자를 검사한다. 잘못되면 조회하지 않는다.
3. 게이트웨이가 요청을 검사하고 조회 슬롯을 차지한다. 슬롯은 에이전트별이 아니라 앱 전체에 1개이며 큐는 없다.
4. 속성마다 capability를 한 번 확인한 뒤, 읽을 수 있는 항목을 순차로 읽는다. 동시에 읽은 스냅샷이 아니다.
5. 게이트웨이가 전체 결과를 같은 `requestId`로 돌려주고, 서버는 계약 검증을 통과한 결과만 MCP 응답으로 쓴다.

| 상황 | 규칙 |
| --- | --- |
| 동시 요청 | 진행 중이면 새 요청은 시작하지 않고 `BUSY`. 진행 중인 조회는 계속한다 |
| 재시도 | 어느 계층도 자동 재시도하지 않는다. 다시 묻는 것은 사용자의 새 요청이다 |
| timeout | 전체 상한은 `deadlineMs`. 서버가 남은 시간을 `remainingMs`로 넘기고 게이트웨이는 그 안에서 끝낸다 |
| 클라이언트 이탈 | MCP 서버(Ktor CIO)는 클라이언트가 HTTP 연결을 끊어도 조회를 멈추지 않는다. 조회는 데드라인 안에 끝나고, 그동안 슬롯을 차지해 다른 호출은 `BUSY`를 받는다. stateless 전송이라 MCP 취소 알림도 진행 중인 호출에 닿지 않는다 |
| 중지 | 승인 차단 → 서버 종료 → 대기 요청 취소 순서. 중지 중 들어온 요청은 `CONNECTION_STOPPED`. 서버가 닫힌 뒤에는 MCP 클라이언트가 연결 실패를 직접 받는다 |
| 늦은 응답 | 콜백은 요청별 토큰으로 대조해 끝난 요청의 것은 버린다. `requestId`가 다른 응답은 `INVALID_RESPONSE` |
| 콜백 없음 | 바인더 미준비 때 SDK는 호출을 보관하고 콜백을 주지 않는다(`source_only`). 항목은 timeout으로 끝나며, 나중에 실행돼도 버린다 |

조회는 상태를 바꾸지 않으므로 늦은 실행이 차량을 바꾸지 않는다. 변경 요청의 결과 불명 처리는 COM-11에서 정한다.

## 게이트웨이 요청·응답 형식

앱 안에서 MCP 서버와 게이트웨이가 주고받는 형식이다. COM-12 전에는 `127.0.0.1:18765`의 HTTP Bridge가 이 형식을 PC의 Node 서버에 보냈다. 지금은 소켓을 거치지 않으며, MCP 서버가 `VehicleReadValidator`로 검사한 뒤에만 결과로 쓴다. 상태 코드는 HTTP의 의미를 빌린 내부 표기다. 크기 한도와 데드라인은 카탈로그 `bridge`를 따른다.

### 요청

```json
{"requestId": "<uuid>", "remainingMs": 9000, "query": "seat_status",
 "properties": ["SEAT_OCCUPANCY"], "areas": ["ROW_1_LEFT"]}
```

- 그 밖의 키는 거부한다. `properties`, `areas`는 선택이며 비어 있거나 중복되면 거부한다.
- 기존 4개 query는 선택 인자를 받지 않는다.
- `properties`는 그 query의 속성이어야 한다. `areas`는 그 query 속성의 영역 표기여야 한다.

### 응답 (상태 200)

```json
{"requestId": "<uuid>", "query": "energy_level", "outcome": "PARTIAL",
 "source": "pleos_emulator", "sdkVersion": "2.3.2", "items": []}
```

| 필드 | 규칙 |
| --- | --- |
| `outcome` | COMPLETE(모두 OK), PARTIAL(일부 OK), FAILED(OK 없음), TIMEOUT, CANCELLED. 앞의 셋은 항목 1개 이상이고 `NOT_READ`가 없어야 하며, 항목에서 다시 계산한 값과 같아야 한다 |
| `source` | `pleos_emulator`, `real_vehicle`, `simulation`. 실제 실행 환경이며 테스트 대역은 `simulation`만 쓴다 |
| `items` | `(property, area)`당 1개. `fixedItems`는 정확히 포함한다. TIMEOUT·CANCELLED는 비어 있어도 된다 |

### 항목

`property`, `area`, `status`, `capability`, `rawValue`, `meaning`, `unit`, `receivedAt`, `errorType`, `errorCode` 10개를 항상 보낸다. 없으면 `null`이다.

- `area`: 영역이 없는 속성은 `null`. 묶음 영역은 capability가 보고한 대로 `A+B+C`로 적고 나누지 않는다. API 문서의 `DoorArea`는 하나 이상의 영역을 받고, 에뮬레이터의 `DOOR_LOCK` capability가 이 형태로 보고한다(`sdk_emulator`). 묶음 영역 조회 자체는 아직 실행하지 않았다.
- `capability`: `{access, matchingAreas, reportedAreas, minimum, maximum}`. 범위는 capability가 보고할 때만 있다.
- `rawValue`: 카탈로그 `valueType`을 따른다. `int`는 SDK의 `Int`와 같은 32비트 범위이고, `float`는 유한한 수다. enum은 이름 문자열, `smart_glass_config`는 `{zoneCount, levelCount}`.
- `meaning`: `DOOR_POSITION`(0=closed, 1=open, 범위 0..1일 때만), `DOOR_LOCK`(true=locked)에만 쓴다. 둘 다 API 문서에 값 의미가 없다. `DOOR_POSITION`은 공식 샘플 README와 PoC 앱의 에뮬레이터 대조에서, `DOOR_LOCK`은 메서드 이름에서 온 해석이다. 다른 속성의 값 의미는 해석하지 않으며, 문서 설명은 카탈로그 `note`에 참고로만 둔다.
- `unit`: 카탈로그 `unit`과 같아야 한다. 값이 capability가 보고한 범위나 문서 범위를 벗어나거나 단위를 확정할 수 없으면 `UNKNOWN_VALUE`로 보낸다.
- `receivedAt`: 앱이 콜백을 받은 UTC ISO 8601 시각. 측정 시각이 아니며 `observedAt`은 SDK가 제공하지 않아 보내지 않는다.
- `errorType`: 진단용 클래스 이름. 배포 AAR의 `VehicleException`은 `a`로 난독화되어 있어(`sdk_emulator`에서 관찰) 판정에 쓰지 않는다. 예외 메시지 원문은 내보내지 않는다.
- `errorCode`: 확인한 SDK `ErrorCode` 이름. 없으면 `null`.

| status | 값 | capability | 오류 필드 |
| --- | --- | --- | --- |
| `OK` | 있음, 의미·단위 확정 | 읽기 가능 | 없음 |
| `NO_VALUE` | `null`, 수신 시각 있음 | 있음 | 없음, 또는 `ERROR_EMPTY_RESULT` |
| `UNKNOWN_VALUE` | 있음, 의미·단위 미확정 | 있음 | 없음 |
| `CAPABILITY_FAILED` | 없음 | 없음 | `errorType` 필수 |
| `READ_UNAVAILABLE` | 없음 | 읽기 불가 | 없음 |
| `AREA_UNSUPPORTED` | 없음 | 읽기 가능 | capability 대조면 없음, 읽기 실패면 `errorType` 필수 |
| `SUPPORT_UNCONFIRMED` | 없음 | 읽기 가능 | 없음 |
| `READ_FAILED` | 없음 | 읽기 가능 | `errorType` 필수 |
| `PERMISSION_DENIED` | 없음 | 무관 | `errorType` 필수 |
| `PROPERTY_UNSUPPORTED` | 없음 | 무관 | `errorType`, `errorCode` 필수 |
| `DRIVING_RESTRICTED` | 없음 | 무관 | `errorType`, `errorCode` 필수 |
| `SERVICE_UNAVAILABLE` | 없음 | 무관 | `errorType`, `errorCode` 필수 |
| `NOT_READ` | 없음 | 무관 | 없음. TIMEOUT·CANCELLED에서만 |

### 호출 단위 실패

| 상태 | 본문 | MCP 결과 |
| --- | --- | --- |
| 409 | `{requestId, ok:false, code:"BUSY"}` | `origin:"android"` |
| 503 | `{requestId, ok:false, code:"APP_NOT_READY" 또는 "CONNECTION_STOPPED"}` | `origin:"android"` |
| 400 | `{ok:false, code:"INVALID_REQUEST"}` (고른 영역에 읽을 항목이 없음) | `origin:"android"` |
| 응답이 계약 검증을 통과하지 못함 | 보내지 않음 | `INVALID_RESPONSE`, `origin:"android"` |
| 데드라인보다 1초 더 기다려도 답이 없음 | 없음 | `TIMEOUT`, `origin:"android"` |

검증기는 COM-12 전의 HTTP 실패(403, 413, 415)도 그대로 판정하지만, 지금 게이트웨이는 이를 만들지 않는다. `ANDROID_UNREACHABLE`과 `origin:"pc"`인 실패는 Node 경로에만 있었다. `tool-results.json`에 남아 있는 `pc` 사례는 결과 글의 형식을 고정하려는 것이다.

## MCP 도구와 응답

앱의 MCP 서버(`app/.../mcp/`)가 담당한다. 사례는 [fixtures/tool-inputs.json](fixtures/tool-inputs.json), [fixtures/tool-results.json](fixtures/tool-results.json), [fixtures/tool-list.json](fixtures/tool-list.json)에 있다. 아래 규칙은 PoC `0d288c8`에서 추출하고 달라지는 점만 고쳤다.

- `tool-list.json`은 COM-12에서 Node 서버의 `tools/list` 결과로 만들었다. 지금 서버는 이 목록과 같아야 한다. 한 가지만 다르다. Kotlin MCP SDK의 `ToolSchema`가 `additionalProperties`를 담지 못해, `inputSchema`에 `additionalProperties: false`가 없다. 알 수 없는 키는 인자 검사에서 거부한다.
- `tool-results.json`의 `expect.text`는 Node 서버가 쓰던 결과 글 전체다. 지금 서버도 같은 글을 쓴다.

**입력과 힌트**

- 기존 4개 도구는 인자가 없다. 후보 도구는 선택 인자 `properties`와, 영역이 있는 속성을 가진 도구에 한해 `areas`를 받는다. 그 밖의 키는 거부한다.
- 모든 도구는 `readOnlyHint: true`, `destructiveHint: false`, `idempotentHint: false`, `openWorldHint: false`다.
- 도구 설명은 한 번 읽는다는 점과 확인되지 않은 값을 추정하지 말라는 점을 적는다. 도구별 `title`과 `description`은 카탈로그 `tools[]`에 있고 두 서버가 그대로 쓴다.
- 인자가 스키마를 벗어나면 `isError: true`이고 `structuredContent`가 없는 결과로 답한다. 글은 `Input validation error:`로 시작하며, 이유 문구는 서버마다 다를 수 있다. 차량을 읽지 않는다.
- `tools/list`에 없는 도구(카탈로그에 없거나 `adoption`이 `adopted`가 아닌 도구)를 부르면 JSON-RPC 오류 `-32602`다.

**오류 표시**

| 게이트웨이 결과 | `isError` | 이미 받은 값 |
| --- | --- | --- |
| `COMPLETE` | 없음 | 전달 |
| `PARTIAL`, `FAILED` | `true` | 전달 |
| Android `TIMEOUT`, `CANCELLED` | `true` | 전달 |
| 호출 단위 실패 | `true` | 없음 |

`isError: true`인 응답도 항목 값을 함께 보낸다. 받은 값을 AI가 실제로 사용하는지는 ChatGPT·Claude에서 확인하지 않았다. COM-26의 검증 대상이다.

**`structuredContent`** (카탈로그 `resultForm`으로 구분)

| 형식 | 결과 | 내용 |
| --- | --- | --- |
| `items` | 게이트웨이 응답 | 검증한 응답 그대로 |
| `items` | 호출 단위 실패 | `{ok:false, origin, code, requestId, query}` |
| `scalar` | 값 확인 | `{ok:true, requestId, source, area, status, state, rawValue, receivedAt, sdkVersion, capability}`. `status`는 `OPEN`/`CLOSED`, `state`는 `open`/`closed` |
| `scalar` | 항목 실패 | `{ok:false, origin:"android", code, requestId, source, area, status, sdkVersion, capability, errorType, errorCode, observation}`. 값을 받았으면 `observation`이 `{rawValue, receivedAt}`, 아니면 `null` |
| `scalar` | 호출 단위 실패 | `{ok:false, origin, code, requestId, area}` |

`scalar`의 TIMEOUT·CANCELLED는 값이 확인됐으면 `isError` 없는 값 확인으로, 아니면 `code`를 outcome으로 둔 항목 실패로 보낸다. 항목이 하나뿐이라 확인된 값이 곧 도구의 답이기 때문이다. `requestId`는 MCP 서버가 만든 값을 항상 넣는다.

**`content` 글**

글과 `structuredContent`는 같은 검증 결과에서 만든다. 문구는 고정하지 않지만 다음을 지킨다.

- 항목마다 속성, 영역, status를 적고, OK면 값과 단위를 적는다. 실패 항목을 값처럼 적지 않는다.
- 값을 받았으면 수신 시각이 측정 시각이 아니라고 알린다. 항목이 여럿이면 동시에 읽은 스냅샷이 아니라고 알린다.
- `isError`이면 확인하지 못한 값을 추정하지 말라고 알리고, 자동 재시도하지 않았다고 알린다.
- `DOOR_LOCK` 값이 있으면 잠김 해석이 메서드 이름에서 왔다고 알린다.
- `source`가 `simulation`이면 실제 차량 값이 아니라고 알린다.
- 예외 메시지 원문을 넣지 않는다.

## SDK 실패 분류

[공식 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code)도 `onFailed`가 항상 `VehicleException`인 것은 아니라고 한다. 실제 에뮬레이터 조회 실패도 일반 `Exception`에 담긴 문자열이었다. 또한 배포 AAR에서는 `VehicleException`이 난독화되어 문서 예제의 `e is VehicleException`, `e.errorCode`를 컴파일할 수 없다. 그래서 SDK 타입 없이 원인 체인 전체를 다음 순서로 검사한다. 사례는 [fixtures/failures.json](fixtures/failures.json)에 있다.

1. `SecurityException`은 `PERMISSION_DENIED`.
2. 메시지가 숫자 ID이거나 `ERROR_*` 이름이면 아래 표로 매핑한다.
3. 메시지가 `area ID: … not supported for property ID` 형식이면 `AREA_UNSUPPORTED`.
4. 그 밖에는 단계별 기본값: capability는 `CAPABILITY_FAILED`, 읽기는 `READ_FAILED`.

| ErrorCode | 읽기 단계 | capability 단계 |
| --- | --- | --- |
| 30001 `ERROR_INTERNAL_SERVICE`, 99999 `ERROR_UNDEFINED` | `READ_FAILED` | `CAPABILITY_FAILED` |
| 40001 `ERROR_SERVICE_CONNECTION_FAILURE` | `SERVICE_UNAVAILABLE` | 같음 |
| 50001 `ERROR_DRIVING_RESTRICTED` | `DRIVING_RESTRICTED` | 같음 |
| 60001 `ERROR_NOT_SUPPORTED_PROPERTY`, 60002 `ERROR_NOT_SUPPORTED_VALUE_TYPE` | `PROPERTY_UNSUPPORTED` | 같음 |
| 60003 `ERROR_NOT_SUPPORTED_AREA` | `AREA_UNSUPPORTED` | `CAPABILITY_FAILED` |
| 70001 `ERROR_EMPTY_RESULT` | `NO_VALUE` | `CAPABILITY_FAILED` |
| 800001 `ERROR_SECURITY_PERMISSION` | `PERMISSION_DENIED` | 같음 |

## 요구사항 5.2와의 대응

5.2 상태명은 프로젝트 내부 제안이며 SDK나 MCP의 공식 enum이 아니다. 이 표는 대응 관계만 나타낸다.

| 5.2 제안 | 이 계약 |
| --- | --- |
| success, partial | `outcome` COMPLETE, PARTIAL |
| unsupported | 항목 `PROPERTY_UNSUPPORTED`, `AREA_UNSUPPORTED`, `READ_UNAVAILABLE` |
| disconnected | `APP_NOT_READY`, `CONNECTION_STOPPED`, MCP 연결 실패, 항목 `SERVICE_UNAVAILABLE` |
| timeout, cancelled | `outcome`, 또는 호출 단위 `TIMEOUT`, `CANCELLED` |
| permission_denied | 항목 `PERMISSION_DENIED` |
| invalid_argument | `INVALID_REQUEST`, MCP 입력 검증 실패 |
| error | `READ_FAILED`, `CAPABILITY_FAILED`, `INVALID_RESPONSE` |
| no_destination, outcome_unknown, changeId, undoAvailable | 내비·변경 전용. COM-11에서 정한다 |
| observedAt | SDK가 제공하지 않아 보내지 않는다 |

## 기존 PoC(`0d288c8`)와 달라지는 점

| 변경 | 영향 |
| --- | --- |
| 경로 `/v2/vehicle-read` → `/v3/vehicle-read` | COM-12에서 HTTP Bridge를 없애 지금은 경로가 없다 |
| 요청 `properties`, `areas` 추가, 본문 1KB → 4KB | 기존 4개는 인자 없음 유지 |
| 응답 16KB → 64KB | Seat는 최악 15속성 × 9영역 |
| 실패 위치 `source`(android/pc) → `origin` | MCP `structuredContent` 키가 바뀐다 |
| 응답 `source`(실행 환경), 항목 `errorCode` 추가 | 엄격 검증이라 양쪽 동시 갱신 필요. 단일 문 도구의 응답에도 들어간다 |
| 호출 단위 실패의 `requestId`를 항상 채움 | PoC는 400 계열에서 `null`이었다. 요청과 결과를 항상 연결한다(FR-DAT-01) |
| 단일 문 도구의 TIMEOUT·CANCELLED가 확인된 값을 유지 | PoC는 값을 버렸다. 항목 유지 변경과 같은 근거 |
| 항목 상태 5개, `CONNECTION_STOPPED` 추가 | 중지와 미준비를 구분한다 |
| **Android TIMEOUT·CANCELLED가 받은 항목을 유지** | PoC는 전부 버렸다. FR-VEH-06 근거의 가장 큰 변경 |
| **`door_status`의 `DOOR_LOCK`은 capability가 보고한 영역으로 읽는다** | PoC는 문 4개를 고정해 에뮬레이터에서 항상 `AREA_UNSUPPORTED`였다 |

도구 4개의 이름과 `get_front_left_door_position`의 스칼라 형식은 위 변경 외에는 유지한다.
