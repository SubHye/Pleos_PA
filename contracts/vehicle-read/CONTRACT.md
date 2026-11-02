# 차량 조회 계약

상태: **제안**. [ADR-0004](../../docs/decisions/0004-vehicle-read-contract.md)와 함께 상대 개발자가 승인하면 채택한다.

이 문서는 규칙만 적는다. 속성·도구·한도의 값은 [catalog.json](catalog.json), 판정 사례는 [fixtures/](fixtures/)가 기준이다. Android와 Node 테스트가 같은 파일을 읽는다.

## 근거의 구분

| 표시 | 뜻 |
| --- | --- |
| `mock` | 사람이 쓴 사례. SDK 동작의 증거가 아니다 |
| `source_only` | SDK 2.3.2 소스에서 읽은 경로. 실행하지 않았다 |
| `sdk_emulator` | Pleos 에뮬레이터에서 관찰한 실제 값. 그 환경에 한정한다 |

카탈로그의 `verification: "none"`은 실행 검증 전이라는 뜻이다. 카탈로그에 있어도 동작을 보장하지 않으며, 실행 검증을 통과한 도구만 `tools/list`에 올린다.

## 범위

대상은 Vehicle SDK 2.3.2의 공개 일회성 조회다. 제외한 항목과 사유는 카탈로그의 `excluded`, `excludedOperations`에 있다.

- 제어, 구독, 비공개·Deprecated API, 내비게이션(COM-11)은 대상이 아니다.
- VIN은 요구사항 부록 B.1에 따라 제외한다. SDK가 조회 값을 logcat에 남기는 경로이기도 하다.
- 필수 도구는 기존 4개다(요구사항 3.4.3). 나머지 18개는 검증 후 채택하는 후보다.

## 요청 생명주기

1. Node가 핸들러 진입 시 데드라인을 시작하고 `requestId`(UUID)를 만든다. 하나의 도구 호출은 HTTP 요청 1회다.
2. Node가 도구와 인자를 검사한다. 잘못되면 Bridge를 호출하지 않는다.
3. Android가 요청을 검사하고 조회 슬롯을 차지한다. 슬롯은 1개이고 큐는 없다.
4. 속성마다 capability를 한 번 확인한 뒤, 읽을 수 있는 항목을 순차로 읽는다. 동시에 읽은 스냅샷이 아니다.
5. Android가 전체 결과를 같은 `requestId`로 돌려주고, Node가 검증한 결과만 MCP 응답으로 쓴다.

| 상황 | 규칙 |
| --- | --- |
| 동시 요청 | 진행 중이면 새 요청은 시작하지 않고 `409 BUSY`. 진행 중인 조회는 계속한다 |
| 재시도 | 어느 계층도 자동 재시도하지 않는다. 다시 묻는 것은 사용자의 새 요청이다 |
| timeout | 전체 상한은 `deadlineMs`. Node가 남은 시간을 `remainingMs`로 넘기고 Android는 그 안에서 끝낸다 |
| 취소 | MCP 취소 또는 연결 종료 시 해당 요청만 끝낸다. 이미 받은 항목은 유지하고 나머지는 `NOT_READ` |
| 중지 | 승인 차단 → 소켓 종료 → 대기 요청 취소 순서. 중지 중 새 요청은 `503 CONNECTION_STOPPED` |
| 늦은 응답 | 콜백은 요청별 토큰으로 대조해 끝난 요청의 것은 버린다. `requestId`가 다른 응답은 `INVALID_RESPONSE` |
| 콜백 없음 | 바인더 미준비 때 SDK는 호출을 보관하고 콜백을 주지 않는다(`source_only`). 항목은 timeout으로 끝나며, 나중에 실행돼도 버린다 |

조회는 상태를 바꾸지 않으므로 늦은 실행이 차량을 바꾸지 않는다. 변경 요청의 결과 불명 처리는 COM-11에서 정한다.

## Bridge wire

주소·경로·크기 한도는 카탈로그 `bridge`를 따른다. loopback 전용이며 Host/Origin 검사는 프로세스 인증이 아니다.

### 요청

```json
{"requestId": "<uuid>", "remainingMs": 9000, "query": "seat_status",
 "properties": ["SEAT_OCCUPANCY"], "areas": ["ROW_1_LEFT"]}
```

- 그 밖의 키는 거부한다. `properties`, `areas`는 선택이며 비어 있거나 중복되면 거부한다.
- 기존 4개 query는 선택 인자를 받지 않는다.
- `properties`는 그 query의 속성이어야 한다. `areas`는 그 query 속성의 영역 표기여야 한다.

### 응답 (HTTP 200)

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

- `area`: 영역이 없는 속성은 `null`. 묶음 영역은 capability가 보고한 대로 `A+B+C`로 적고 나누지 않는다. 에뮬레이터의 `DOOR_LOCK`이 이 형태다(`sdk_emulator`).
- `capability`: `{access, matchingAreas, reportedAreas, minimum, maximum}`. 범위는 capability가 보고할 때만 있다.
- `rawValue`: 카탈로그 `valueType`을 따른다. enum은 이름 문자열, `smart_glass_config`는 `{zoneCount, levelCount}`.
- `meaning`: `DOOR_POSITION`(0=closed, 1=open, 범위 0..1일 때만), `DOOR_LOCK`(true=locked)에만 쓴다. 다른 속성의 값 의미는 해석하지 않는다. `DOOR_LOCK`의 의미도 메서드 이름에서 온 해석이다.
- `unit`: 카탈로그 `unit`과 같아야 한다. 값이 문서 범위를 벗어나거나 단위를 확정할 수 없으면 `UNKNOWN_VALUE`로 보낸다.
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

| HTTP | 본문 | Node 결과 |
| --- | --- | --- |
| 409 | `{requestId, ok:false, code:"BUSY"}` | `origin:"android"` |
| 503 | `{requestId, ok:false, code:"APP_NOT_READY" 또는 "CONNECTION_STOPPED"}` | `origin:"android"` |
| 400, 413, 415 | `{ok:false, code:"INVALID_REQUEST"}` | `origin:"android"` |
| 403 | `{ok:false, code:"REQUEST_FORBIDDEN"}` | `origin:"android"` |
| 연결 실패 | 없음 | `ANDROID_UNREACHABLE`, `origin:"pc"` |
| 형식 위반, 다른 `requestId`, 크기 초과 | 없음 | `INVALID_RESPONSE`, `origin:"pc"` |
| 데드라인 초과, MCP 취소 | 없음 | `TIMEOUT`, `CANCELLED`, `origin:"pc"` |

## MCP 도구와 응답

Node만 담당하는 구간이다. 사례는 [fixtures/tool-inputs.json](fixtures/tool-inputs.json), [fixtures/tool-results.json](fixtures/tool-results.json)에 있다. 아래 규칙은 PoC `0d288c8`에서 추출하고 달라지는 점만 고쳤다.

**입력과 힌트**

- 기존 4개 도구는 인자가 없다. 후보 도구는 선택 인자 `properties`와, 영역이 있는 속성을 가진 도구에 한해 `areas`를 받는다. 그 밖의 키는 거부한다.
- 모든 도구는 `readOnlyHint: true`, `destructiveHint: false`, `idempotentHint: false`, `openWorldHint: false`다.
- 도구 설명은 한 번 읽는다는 점과 확인되지 않은 값을 추정하지 말라는 점을 적는다. 구체적 문구는 COM-23에서 정한다.

**오류 표시**

| Bridge 결과 | `isError` | 이미 받은 값 |
| --- | --- | --- |
| `COMPLETE` | 없음 | 전달 |
| `PARTIAL`, `FAILED` | `true` | 전달 |
| Android `TIMEOUT`, `CANCELLED` | `true` | 전달 |
| 호출 단위 실패 | `true` | 없음 |

`isError: true`인 응답도 항목 값을 함께 보낸다. 받은 값을 AI가 실제로 사용하는지는 ChatGPT·Claude에서 확인하지 않았다. COM-23의 검증 대상이다.

**`structuredContent`** (카탈로그 `resultForm`으로 구분)

| 형식 | 결과 | 내용 |
| --- | --- | --- |
| `items` | Bridge 응답 | 검증한 응답 그대로 |
| `items` | 호출 단위 실패 | `{ok:false, origin, code, requestId, query}` |
| `scalar` | 값 확인 | `{ok:true, requestId, source, area, status, state, rawValue, receivedAt, sdkVersion, capability}`. `status`는 `OPEN`/`CLOSED`, `state`는 `open`/`closed` |
| `scalar` | 항목 실패 | `{ok:false, origin:"android", code, requestId, source, area, status, sdkVersion, capability, errorType, errorCode, observation}`. 값을 받았으면 `observation`이 `{rawValue, receivedAt}`, 아니면 `null` |
| `scalar` | 호출 단위 실패 | `{ok:false, origin, code, requestId, area}` |

`scalar`의 TIMEOUT·CANCELLED는 값이 확인됐으면 `isError` 없는 값 확인으로, 아니면 `code`를 outcome으로 둔 항목 실패로 보낸다. 항목이 하나뿐이라 확인된 값이 곧 도구의 답이기 때문이다. `requestId`는 Node가 만든 값을 항상 넣는다.

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
| disconnected | `ANDROID_UNREACHABLE`, `APP_NOT_READY`, `CONNECTION_STOPPED`, 항목 `SERVICE_UNAVAILABLE` |
| timeout, cancelled | `outcome`, 또는 호출 단위 `TIMEOUT`, `CANCELLED` |
| permission_denied | 항목 `PERMISSION_DENIED` |
| invalid_argument | `INVALID_REQUEST`, MCP 입력 검증 실패 |
| error | `READ_FAILED`, `CAPABILITY_FAILED`, `INVALID_RESPONSE` |
| no_destination, outcome_unknown, changeId, undoAvailable | 내비·변경 전용. COM-11에서 정한다 |
| observedAt | SDK가 제공하지 않아 보내지 않는다 |

## 기존 PoC(`0d288c8`)와 달라지는 점

| 변경 | 영향 |
| --- | --- |
| 경로 `/v2/vehicle-read` → `/v3/vehicle-read` | 옛 빌드와 섞이면 거부된다 |
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
