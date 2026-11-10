# Pleos 차량 시뮬레이션과 SDK·MCP 검증

## Virtual Console

**공식 범위:** `adb forward`로 8088을 연결하고 `http://127.0.0.1:8088/` 웹 UI를 쓴다 ([설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator), [사용](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide)). 최소 에뮬레이터는 v2.1.0이다. 공식 문서에 HTTP API는 없다.

**비공식 내부 HTTP:** 아래 경로는 페이지 소스에서 찾은 것이며 v2.1.0에서 실행해 확인했다. 공개 계약이 아니므로 버전이 바뀌면 다시 확인하고, 동작하지 않으면 웹 UI로 돌아간다.

| 요청 | 용도 |
| --- | --- |
| `GET /vehicle/catalog` | 기능 목록. `write_endpoint`, 필드, area, 값, CarProperty ID |
| `GET /vehicle/state` | L0 값, `browser.clients`, `availability` |
| `PUT <write_endpoint>?<field>=<value>` | 값 변경 |

### 활성화 조건 (실측)

Console은 **페이지가 열린 브라우저가 있을 때만 쓰기를 받는다.** 페이지가 1초마다 heartbeat를 보내고 timeout은 3초다.

| 상황 | `browser.clients` | `availability` | `PUT` 결과 |
| --- | --- | --- | --- |
| 탭 없음 | 0 | `available=false`, `web-deactivation` | `{"error":"emulator_inactive"}`, 값 불변 |
| 탭 열림 | 1 이상 | `available=true`, `web-activation` | 200, SDK까지 반영 |

- 탭을 연 직후에는 잠시 비활성일 수 있다. 시간을 고정하지 말고 `clients>=1`이고 `available=true`일 때까지 폴링한다.
- 탭을 닫아도 마지막 값은 SDK에 **유지된다.** 문을 연 채 탭을 닫으면 열린 상태로 남으니, 복구한 다음 닫는다.
- heartbeat를 직접 흉내 내지 않는다. 실제 페이지를 연다.

### 준비

```powershell
adb -s <serial> forward --no-rebind tcp:8088 tcp:8088
cmd /c "agent-browser --session pleos-vc open http://127.0.0.1:8088/ > NUL 2>&1"
```

이미 같은 serial로 매핑되어 있으면 `--no-rebind`가 실패해도 정상이다. preflight의 `forward_console`로 먼저 확인한다.

### 값 변경

두 방식 모두 앞좌측 문에서 SDK 반영을 확인했다. 기본은 catalog에서 필드를 찾는 내부 `PUT`이다. 기능 수가 많아 UI 선택자보다 안정적이기 때문이다. 공식 조작 경로 자체를 시험할 때는 웹 UI를 쓴다.

```powershell
# 내부 PUT: 필드는 catalog의 control.field 또는 control.field_by_area에서 얻는다
Invoke-WebRequest -UseBasicParsing -Method Put -TimeoutSec 5 "http://127.0.0.1:8088/vehicle/body?door_fl=true"
# 웹 UI: 클릭은 값을 토글하므로 클릭 전에 현재 값을 확인한다
agent-browser --session pleos-vc click ".door-fl"
```

PUT이 4xx를 반환하면 `Invoke-WebRequest`는 예외를 던진다. 예외의 응답 본문을 판정에 사용한다.

catalog에는 기능 63개가 있으나 이번에 SDK까지 대조한 것은 **문 위치뿐이다.** 다른 기능은 시나리오별로 확인 전까지 지원으로 간주하지 않는다. PoC 기록에는 Console의 Window Lock 값이 바뀌지 않았다는 보고가 있다.

## SDK 판독 경로

지원 범위는 `app/src/AGENTS.md`와 `contracts/`를 본다. 현재 쓸 수 있는 경로는 다음과 같다. 항상 판독한 앱을 기록한다.

| 경로 | 읽는 것 | 비고 |
| --- | --- | --- |
| Pleos_PA `com.example.pleos_pa` | 차량 조회, 내비 현재 목적지 | 앱 내장 MCP `127.0.0.1:18766/mcp`([ADR-0008](../../../../docs/decisions/0008-embedded-mcp-server.md)). 화면에서 연결을 시작해야 열린다. 내비는 `get_navigation_destination`이며 좌표는 결과에 없다 |
| `tools/nav-place-check` `com.example.pleos_pa.placecheck` | 내비 목적지 설정·재조회·취소 | 테스트 지원 APK. 좌표·장소 ID까지 읽는다. **제품 앱 결과가 아니다** |
| 공식 샘플 `ai.pleos.playground.sdk.navidevapp` | 내비 API 전반 | 기본 선택이 `requestRoute`라 실수로 목적지가 바뀔 수 있다. 위 두 경로로 안 되는 API만 쓴다 |
| 공식 샘플 `ai.pleos.playground.sample.vehicle` | 차량 조회 | 앱 UI로 직접 조회 (미실행) |
| PoC `com.example.pleostest` | 차량 4개 도구 | SDK 2.3.2, Bridge·MCP 포함. **Pleos_PA의 결과가 아니다** |

**L2 확인:** logcat에서 앱 프로세스의 SDK 콜백을 찾는다. 서비스 쪽 로그가 아니라 대상 앱 pid의 로그를 쓴다.

```text
[Pleos][SDK]b: getProperty_onResult eventPropKey DOOR_POS eventAreaId 1 eventValue 1
```

## 시나리오: 운전석 문 열림·닫힘

전제: preflight에 `FAIL`이 없고 Console 탭이 활성 상태이며 판독 앱이 실행 중이다.

| 단계 | 조작 | L0 `body.doors.front_left` | L2·L3 `DOOR_POSITION/ROW_1_LEFT` | L4 `get_front_left_door_position` |
| --- | --- | --- | --- | --- |
| 기준 | 없음 | `false` | `raw=0`, `closed` | `CLOSED`, `rawValue=0` |
| 열림 | `door_fl=true` | `true` | `raw=1`, `open` | `OPEN`, `rawValue=1` |
| 닫힘 | `door_fl=false` | `false` | `raw=0`, `closed` | `CLOSED`, `rawValue=0` |

앱 UI 반영을 시험하려면 L1을 추가한다. 포그라운드 앱을 탭으로 다시 조회해 표시값을 읽는다. 값 변경 후 자동 갱신은 앱 구현에 따라 다르므로 별도 기대값으로 정의한다. 차량 설정 앱은 순정 UI이므로 대상 앱의 반영 증거로 쓰지 않는다.

## Pleos_PA MCP (현재 앱)

구조: `MCP 클라이언트(PC) → 127.0.0.1:18766 → adb forward → 앱 Foreground Service 안의 MCP 서버 → SDK`([ADR-0008](../../../../docs/decisions/0008-embedded-mcp-server.md)). 인증이 없으므로 연결을 켠 동안만 포워딩을 둔다.

```powershell
adb -s <serial> forward --no-rebind tcp:18766 tcp:18766
adb -s <serial> shell am start --user 10 -W -n com.example.pleos_pa/.MainActivity
# 화면에서 연결을 시작한 뒤
adb -s <serial> shell "netstat -ltn | grep 18766 | grep LISTEN"
```

- 전 도구 판정은 `tools/vehicle-sweep`(README 참고)를 쓴다. 차량·내비 조회 슬롯은 각각 1개라 병렬 호출은 `BUSY`다.
- 끝나면 `forward --remove tcp:18766` 하고 앱 화면에서 연결을 중지한다. 시작 전에 없던 포워딩을 남기지 않는다.

## Bridge·MCP 통합 (PoC 참고 경로)

**PoC 전용이다. Pleos_PA는 위 절을 쓴다.** 구조: `MCP stdio(PC) → 127.0.0.1:18765 → adb forward → 앱 Foreground Service → SDK`([ADR-0002](../../../../docs/decisions/0002-boundaries-and-transport.md), ADR-0008 이전).

**시작:** 화면을 열어야 서비스가 시작된다.

```powershell
adb -s <serial> forward --no-rebind tcp:18765 tcp:18765
adb -s <serial> shell am start --user 10 -W -n com.example.pleostest/.MainActivity
adb -s <serial> shell "netstat -ltn | grep 18765 | grep LISTEN"
```

**L3 직접 호출:** 도구 호출당 HTTP 1회, 자동 재시도 없음. 바디 키는 정확히 3개이고 `Origin` 헤더는 거부된다. `query`는 `door_status`, `window_status`, `energy_level`, `front_left_door_position`이다.

```powershell
$body = @{ requestId = [guid]::NewGuid().ToString(); remainingMs = 9000; query = 'front_left_door_position' } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18765/v2/vehicle-read -ContentType 'application/json' -Body $body -TimeoutSec 12
```

**L4:** MCP 클라이언트에서 도구를 호출한다. Claude Code에서 PoC 서버가 등록되어 있으면 `mcp__pleostest-vehicle__*`로 호출할 수 있다. **조회 슬롯이 하나이므로 병렬로 호출하지 않는다.** 동시 호출은 `BUSY`로 거부된다.

**오류·복구 기대값:** 오류를 값으로 바꾸지 않고 전달해야 한다.

| 유발 | 기대 결과 | 복구 |
| --- | --- | --- |
| `adb forward --remove tcp:18765` | MCP `ANDROID_UNREACHABLE` | `forward --no-rebind` 후 재조회 |
| 앱의 `AI 연결 종료` | LISTEN 사라짐, MCP `ANDROID_UNREACHABLE` | `am force-stop --user 10` 후 `am start` |
| 동시 호출 | `BUSY` | 끝난 뒤 사용자 요청으로 다시 호출 |

`AI 연결 종료` 후 같은 Activity에서 `am start`만 하면 서비스가 재시작되지 않았다. `KEYCODE_HOME`도 효과가 없었다.

끝나면 포워딩을 지우고(`forward --remove tcp:18765`) 앱을 종료한다. 시작 전에 없던 포워딩을 남기지 않는다.

## 내비게이션

SDK 지원 범위는 Linear의 SDK 지원 기능표(COM-8)가 원본이다. 규칙과 관찰은 [내비 계약](../../../../contracts/navigation/CONTRACT.md)에 있다. 여기에 복제하지 않는다.

### 관찰 계층 (내비)

차량용 L0(Console)·L3(Bridge)는 내비에 없다. 내비 시나리오는 아래 계층으로 적는다.

| 계층 | 관찰 지점 | 증명하는 것 | 증명하지 못하는 것 |
| --- | --- | --- | --- |
| N1 | 순정 내비 화면 | 표시된 목적지·남은 시간·거리 | SDK 응답 |
| N2 | 판독 앱의 `onDestinationInfo` (nav-place-check 로그) | SDK가 그 앱에 준 값. 좌표·장소 ID 포함 | 제품 앱 출력 |
| N4 | Pleos_PA `get_navigation_destination` | MCP 입출력. 좌표 없음 | 외부 AI 동작 |

### 읽기만 하기

- 제품 앱 결과가 필요하면 N4(MCP 도구)를 쓴다.
- 좌표·장소 ID가 필요하면 nav-place-check `capture`를 쓴다(상태를 바꾸지 않는다).
- 공식 샘플은 위 둘로 안 되는 API에만 쓴다.
  1. `am start --user 10 -W -n ai.pleos.playground.sdk.navidevapp/ai.pleos.playground.navidevapp.MainActivity`
  2. **샘플의 기본 선택은 `requestRoute`다. `Execute`를 바로 누르면 목적지가 설정된다.**
  3. 드롭다운에서 `text=/^getDestinationInfo$/`를 선택하고, 레이아웃에서 선택값을 다시 확인한 뒤에만 `Execute`를 누른다.
  4. `Listener Response`의 `onDestinationInfo`를 읽는다. 샘플의 `Initialized` 표시는 연결 증거가 아니다.

목적지가 없으면 빈 이름, 좌표 (0, 0), 시간·거리 0이 온다. 이는 실제 값이 아니다.

### 목적지 설정·취소 (nav-place-check)

사용법은 `tools/nav-place-check/README.md`가 원본이다. **실행마다 승인이 필요하다**(SKILL.md 안전 경계).

시나리오 표 예시(장소 하나, 1회):

| 단계 | 조작 | N2 기대 | N1 기대 |
| --- | --- | --- | --- |
| 기준 | `capture` | 목적지 없음 | "어디로 갈까요?" |
| 설정 | `route --es place <key>` | 재조회 좌표 = 목록 좌표(차이 ≤ 1e-6°), `verdict: PASS` | 안내 화면, 목적지 이름·남은 시간·거리가 N2와 같음 |
| 복구 | `cancel` | `noDestination: true` | 안내 없음 |

- `route`는 `requestRoute` 뒤 10초를 기다린다. COM-8에서 `onRouteStarted`까지 5.7~8.1초였다.
- 판정은 좌표로만 한다. 재조회 주소는 보낸 도로명이 아니라 동 수준("용산구 동자동")이고, `poiSubId`는 `null`이다.
- 재조회 이름: 장소 ID를 넣으면 순정 내비 이름("서울역 KTX"), 비우면 보낸 `poiName`이다. 이름만 대조하지 않는다(COM-8에서 좌표·ID 충돌 시 ID 쪽 위치로 설정됨).
- `onDestinationInfo`에는 요청 식별자가 없고, 다른 앱의 조회도 이 콜백을 부른다. 재조회 값은 그 시점의 현재 목적지일 뿐이다.
- 끝에 `capture`로 목적지 없음을 확인하고 기록한다.
- `am start`가 `Activity not started, its current task has been brought to the front`나 `result code=2`를 남기면 이전 화면이 재사용되어 실행되지 않은 것이다. 로그에 이번 실행 줄이 없으면 PASS로 쓰지 않는다.

### 순정 내비 화면 조작 (3.3.2-rc5-hotfix.3, 2560x1440)

좌표는 이 버전 화면에서 관찰한 값이다. 버전이 바뀌면 다시 확인한다.

| 화면 | 관찰 |
| --- | --- |
| 홈 | 왼쪽 아래 검색창 "어디로 갈까요?" 약 (1165, 1110) |
| 검색창 | **"최근" 목록에 이전 검색·목적지가 보인다.** 스크린샷에 다른 사람의 목적지가 남을 수 있다 |
| 한글 입력 | `adb shell input text`는 한글을 못 친다. 화면 두벌식 키보드의 자모 키를 차례로 누르면 조합된다. `android input --device=<serial> tap "text=ㅋ"`처럼 자모마다 선택자로 누른다(ㅋㅗㅇㅔㄱㅅㅡ → "코엑스" 확인). 키는 `android layout`의 `INPUT_METHOD` 창에 있고 `uiautomator dump`에는 없다. 검색창 비우기는 `text=지우기` |
| 검색 결과 | 항목마다 오른쪽 화살표(약 x=1400)가 길안내, 항목 글자를 누르면 상세. 목록의 주소는 잘려 보인다 |
| 상세 | 도로명 전체 주소·전화가 보인다. "목적지로" 버튼은 목적지를 바꾼다 |
| 경로 미리보기 | **"안내 시작" 버튼에 카운트다운이 있어 그대로 두면 안내가 시작된다.** 길안내 화살표를 누르는 것 자체가 내비 변경이다 |
| 안내 중 | 아래 카드에 도착 시각, "N분 · N.Nkm" 형식의 남은 시간·거리, 목적지 이름, "안내 종료" |

- 좌표가 필요하면 `android layout -o <파일>`의 `center` 값을 쓴다. 위 표의 좌표는 그 값과 일치했다(ㅂ 557,919).
- 장소 좌표를 새로 얻을 때: 검색 → 길안내 → "안내 시작" → `capture` → `cancel` → 상세 화면에서 도로명 주소 확인. 순서와 승인 범위는 README의 "새 장소 추가"를 따른다.

## 검증 기록 (2026-11-10, COM-52)

환경: `emulator-5554`, `Pleos_Connect_v2.1.0`, API 34, 사용자 10, Navi Service 3.0.4, 순정 내비 3.3.2-rc5-hotfix.3. 판독 앱은 `tools/nav-place-check`이며 제품 앱 결과가 아니다.

| 항목 | 상태 | 관찰 |
| --- | --- | --- |
| nav-place-check `capture`·`route`·`cancel` | PASS | 4곳 × 2회 재조회 좌표 차이 0, 취소 뒤 목적지 없음 |
| 순정 내비 한글 검색(화면 키보드 탭) | PASS | 서울역·판교역·코엑스 검색 |
| 순정 화면과 N2 대조 | PASS | 판교역 안내 화면의 남은 시간·거리가 SDK 값과 같음 |
| 화면 키보드 키 노출 | PASS | `android layout`의 `INPUT_METHOD` 창에 자모 26개. `uiautomator dump`에는 없음 |
| Android CLI 선택자로 한글 입력 | PASS | 자모 7번 탭으로 검색창에 "코엑스". 목적지는 바꾸지 않음 |
| 기기 잠금 `pleos-test.lock` | PASS | 걸기, 있을 때 두 번째 걸기 거절, preflight `device_lock` WARN, 풀기 |
| preflight `foreign_activity` | PASS | 다른 세션의 COM-36 실행 중 WARN, 끝난 뒤 OK |
| Pleos_PA N4 `get_navigation_destination`와의 대조 | NOT_RUN | COM-24 기록 참고 |

## 검증 기록 (2026-11-02)

아래는 PoC 기준 기록이다. 그 뒤 Pleos_PA에 MCP와 내비 조회가 생겼으므로 "Pleos_PA SDK·Bridge·MCP BLOCKED"는 더는 현재 상태가 아니다.

환경: `emulator-5554`, `Pleos_Connect_v2.1.0`, API 34, 사용자 10, Vehicle Service 2.3.22, Navi Service 3.0.4. 판독 앱은 PoC `com.example.pleostest`(SDK 2.3.2)이며 Pleos_PA 결과가 아니다.

| 항목 | 상태 | 관찰 |
| --- | --- | --- |
| Android CLI layout·capture·resolve·tap | PASS | 모든 창 반환, 탭 후 레이아웃 변화 확인 |
| Pleos_PA `install` user 10 | PASS | Activity 없음. 실행은 BLOCKED |
| Console 활성화 조건 | PASS | 위 표 |
| 문 FL 기준·열림·닫힘, PUT | PASS | L0, L2(로그), L3, L4 모두 기대값과 일치 |
| 문 FL 열림·닫힘, 웹 UI | PASS | L0, L3 일치 |
| 앱 UI 표시 L1 | PASS | PoC 앱 화면 `닫힘`, `원시값: 0` |
| Bridge 오류와 복구 | PASS | `ANDROID_UNREACHABLE` 후 복구 조회 성공 |
| 내비 읽기 `getDestinationInfo` | PASS | 빈 목적지 응답 수신 |
| 그 외 Console 기능, 공식 Vehicle 샘플, `requestRoute` | NOT_RUN | |
| Pleos_PA SDK·Bridge·MCP | BLOCKED | 구현 없음 |
| 스킬 자동 발견 (Codex, Claude Code) | BLOCKED | Codex는 모델 설정 오류, 헤드리스 Claude는 인증 만료로 실행 불가. 진행 중인 세션에서는 새 스킬 폴더가 인식되지 않음. 새 세션에서 확인 필요 |
| 외부 AI E2E(L5), 실차 | NOT_RUN | |
