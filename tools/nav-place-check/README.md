# nav-place-check

Pleos 에뮬레이터에서 [고정 장소 목록](../../contracts/navigation/places.json)의 각 장소를 순정 내비 목적지로 설정하고 다시 읽어, 목록 값이 그대로 반영되는지 확인한다. 테스트 지원 도구이며 제품 앱에 들어가지 않는다.

- 이 도구는 목록의 장소를 직접 설정하고 취소한다(`NAVI_ROUTE_SEARCH`). 제품 앱도 같은 권한으로 `set_navigation_destination`을 제공하지만 `cancelRoute`는 부르지 않으므로, 안내 취소에는 이 도구의 `cancel`을 쓴다.
- 빌드할 때 `places.json`을 APK에 넣는다. 장소를 추가하면 다시 빌드하고 설치한다.
- `CheckActivity`와 `WaypointActivity`는 `android.permission.DUMP`를 가진 호출자(adb shell)만 실행할 수 있다.

## 안전

- `route`와 `cancel`은 내비 상태를 바꾼다. **실행할 때마다 사용자 승인을 먼저 받는다.**
- 실행 전에 [pleos-emulator-test](../../.agents/skills/pleos-emulator-test/SKILL.md) preflight의 `pleos_identity`가 `OK`인지 확인한다. 실차나 다른 기기에서는 실행하지 않는다.
- 끝나면 `cancel`로 목적지 없음 상태로 되돌린다.
- logcat 줄에는 남은 거리·시간이 있다. 여러 장소의 값을 모으면 에뮬레이터 위치를 역산할 수 있으므로, 로그를 첨부하거나 커밋하지 않는다. `places.json`에는 좌표·이름·장소 ID와 판정만 옮긴다.

## 실행

```powershell
.\gradlew.bat :nav-place-check:assembleDebug
adb -s <serial> install --user 10 -r -g tools\nav-place-check\build\outputs\apk\debug\nav-place-check-debug.apk
adb -s <serial> logcat -c
adb -s <serial> shell am start --user 10 -W -n com.example.pleos_pa.placecheck/.CheckActivity --es mode <mode> [--es place <key>]
adb -s <serial> logcat -d -s PlaceCheck:I
```

| `mode` | 하는 일 |
| --- | --- |
| `capture` | `getDestinationInfo()`만 부른다. 순정 내비 화면에서 직접 설정한 목적지의 좌표·이름·장소 ID를 얻을 때 쓴다 |
| `route` | 기준 조회 → 목록 값으로 `requestRoute` → 10초 대기(`--ei waitMs`로 변경) → 재조회. 좌표 차이가 1e-6° 이하이면 `PASS` |
| `cancel` | `cancelRoute` → 3초 뒤 재조회해 목적지 없음(빈 이름, (0, 0))인지 적는다 |

- 한 번 실행하면 한 동작만 하고 화면을 닫는다. 실행 중에 또 시작하면 `BUSY`로 끝낸다. 자동 재시도는 없다.
- 결과는 `PlaceCheck` 태그의 한 줄 JSON이다. 예외는 종류만 남긴다.
- `RouteInfo`는 [내비 계약](../../contracts/navigation/CONTRACT.md#고정-장소-목록)의 구성 규칙을 따른다.

## 경유지 조사 (`WaypointActivity`)

COM-47의 경유지 SDK 조사에 쓴 도구다. `CheckActivity`와 같은 모듈·권한·안전 규칙을 따르며, 제품 앱의 경유지 도구(COM-41)와 별개다. 여기서 얻은 관찰은 조사 근거이고 제품 앱의 검증 결과로 옮기지 않는다.

```powershell
adb -s <serial> shell am start --user 10 -W -n com.example.pleos_pa.placecheck/.WaypointActivity --es mode <mode> [옵션]
adb -s <serial> logcat -d -s WaypointCheck:I
```

| `mode` | 옵션 | 하는 일 |
| --- | --- | --- |
| `get` | | `getWaypointInfo()`와 `getDestinationInfo()`만 부른다. 내비를 바꾸지 않는다 |
| `add` | `--es place <key> --es index FIRST\|SECOND\|ALL` | 조회 → `addWaypoint` → 대기 → 재조회 |
| `remove` | `--es index FIRST\|SECOND\|ALL` | 조회 → `removeWaypoint` → 대기 → 재조회 |
| `via` | `--es dest <key> [--es wps "<key>:<INDEX>,..."]` | 조회 → `requestRouteWithWaypoints` → 대기 → 재조회. 경유지가 없으면 `wps`를 아예 빼라. 빈 문자열 인자는 adb shell에서 사라진다 |

- `add`와 `via`는 `places.json`의 키만 받는다. 목록에 없는 장소는 보내지 않는다.
- 대기 시간은 `--ei waitMs`(기본 8000)로 바꾼다. 한 번 실행하면 한 동작만 하고, 실행 중에 또 시작하면 `BUSY`로 끝낸다. 자동 재시도는 없다.
- 결과는 `WaypointCheck` 태그의 한 줄 JSON이다. 호출 뒤에 도착한 `onWaypointChanged`·`onRouteCancelled` 등의 이벤트도 시각과 함께 남는다. 다시 읽은 경유지의 좌표는 0/0이고 `poiSubId`는 null이다.
- `add`, `remove`, `via`는 내비 상태를 바꾼다. **실행할 때마다 사용자 승인을 먼저 받고**, 끝나면 `remove --es index ALL`과 `CheckActivity`의 `cancel`로 목적지와 경유지 없음 상태로 되돌린다.
- 로그에는 좌표와 남은 거리·시간이 있으므로 위 `안전`의 로그 규칙을 그대로 적용한다.

## 새 장소 추가

1. 순정 내비 화면에서 장소를 검색해 목적지로 설정한다. 화면 키보드로 한글을 입력할 수 있다.
2. `capture`로 좌표·이름·장소 ID를 읽고 `cancel`로 되돌린다. 상세 화면의 도로명 주소도 기록한다.
3. `places.json`에 항목을 추가한다(`coordinateSource: "navi_capture"`, `capture`, `evidence.status: "not_run"`). 목록에는 검증된 장소만 두므로, 이 상태에서는 `PlaceCatalogTest`가 실패한다.
4. 다시 빌드·설치하고 `route` → `cancel`을 2회 이상 실행한다. 결과를 `evidence.runs`에 옮기고 `status`를 `sdk_emulator`로 바꾼다.
5. 재조회 좌표가 목록과 다르면 그 장소를 넣지 않거나 원인을 기록한다.
6. 별칭을 바꿨으면 `fixtures/place-cases.json`의 사례를 함께 갱신하고 JVM 테스트를 돌린다.
