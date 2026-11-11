# COM-26 AI 호출 시나리오와 기록표

대상은 ChatGPT Desktop 로컬 Codex 호스트와 Claude Desktop이다. 연결 방법은 [mcp/README.md](../../mcp/README.md)의 "클라이언트 연결"을 따른다. 실행 브랜치는 COM-18, COM-20, COM-23이 모두 있는 `feature/com-23-vehicle-tools`다.

결과를 실차 동작으로 일반화하지 않는다. 실행자 이름은 이 파일이나 PR이 아니라 Linear에만 적는다. 이 파일은 커밋하지 않는다.

## 준비

1. 에뮬레이터와 앱에서 "연결됨"을 확인한다.
2. `adb forward --list`에 `tcp:18765`와 `tcp:8088`이 있는지 확인한다.
3. `http://127.0.0.1:8088/`을 열어 둔다. 페이지를 닫으면 Console 값을 바꿀 수 없다.
4. AI 앱의 도구 목록에 20개가 보이는지 확인한다. `get_fuel_economy`, `get_toll_status`는 없어야 한다.
5. 새 대화에서 시작한다. 이전 답변을 재사용한 시행은 무효다.

## 필수 4개

두 AI에서 각각 모든 줄을 실행한다. 최소 16회다. 요청을 제출한 순간이 t0, 최종 응답이 끝난 순간이 t1이다. t1-t0가 5초 이내여야 한다. 실패를 포함한 전체 시행이 분모다.

| ID | 도구 | Console 설정 (`/vehicle/body` 등) | 기대 항목 | 요청 문장 |
| --- | --- | --- | --- | --- |
| R1 | `get_door_status` | `door_fl=true` | `DOOR_POSITION` `ROW_1_LEFT` = 1 | 지금 차 문 상태 알려줘 |
| R2 | `get_door_status` | `door_fl=false`, `doors_locked=true` | `DOOR_POSITION` `ROW_1_LEFT` = 0, `DOOR_LOCK` = true | 문 열린 데 있어? 잠겨 있어? |
| R3 | `get_window_status` | `window_fl=50` | `WINDOW_POSITION` `ROW_1_LEFT` = 5 | 창문 얼마나 열려 있어? |
| R4 | `get_window_status` | `window_fl=100` | `WINDOW_POSITION` `ROW_1_LEFT` = 10 | 운전석 창문 상태 알려줘 |
| R5 | `get_energy_level` | `/vehicle/ev-battery` `field=soc`, `value=40` | `EV_BATTERY_PERCENT` = 40, `FUEL_PERCENT`는 `PROPERTY_UNSUPPORTED` | 배터리 얼마나 남았어? |
| R6 | `get_energy_level` | `field=soc`, `value=80` | `EV_BATTERY_PERCENT` = 80, `FUEL_PERCENT`는 `PROPERTY_UNSUPPORTED` | 연료나 배터리 잔량 알려줘 |
| R7 | `get_front_left_door_position` | `door_fl=true` | `state` = open, `ok` = true | 앞좌석 왼쪽 문만 확인해 줘 |
| R8 | `get_front_left_door_position` | `door_fl=false` | `state` = closed, `ok` = true | 앞좌석 왼쪽 문 닫혀 있어? |

창문 값은 퍼센트가 아니라 원시값이다. Console 50은 SDK 5, Console 100은 SDK 10이다. AI가 "50% 열림"처럼 답하면 근거 없는 변환이므로 AI 응답 해석 실패로 분류한다.

## 채택 후보 16개

두 AI에서 각 1회, 총 32회다. 올바른 도구 선택과 값 답변을 확인한다. 시간은 기록만 한다.

| ID | 도구 | 요청 문장 | 확인할 점 |
| --- | --- | --- | --- |
| C1 | `get_brake_status` | 브레이크 밟고 있어? | |
| C2 | `get_display_units` | 온도 단위 뭐로 돼 있어? | |
| C3 | `get_glove_box_status` | 글로브박스 잠겨 있어? | |
| C4 | `get_driving_mode_status` | 크루즈 컨트롤 켜져 있어? | 미지원 항목을 꺼짐으로 말하지 않는다 |
| C5 | `get_ev_status` | 충전 전류 제한이 얼마야? | |
| C6 | `get_hvac_status` | 에어컨 켜져 있어? 설정 온도는? | |
| C7 | `get_light_status` | 전조등 상태 알려줘 | |
| C8 | `get_trip_status` | 지금까지 총 주행거리 얼마야? | 주행거리계를 이번 주행 거리로 말하지 않는다 |
| C9 | `get_safety_status` | 차에 경고 뜬 거 있어? | 미지원 22개를 정상으로 말하지 않는다 |
| C10 | `get_seat_status` | 운전석에 사람 앉아 있어? | Console과 달랐던 값을 단정하지 않는다 |
| C11 | `get_side_mirror_status` | 사이드미러 열선 켜져 있어? | |
| C12 | `get_steering_wheel_status` | 핸들 어느 쪽으로 돌아가 있어? | 회전 방향을 단정하지 않는다 |
| C13 | `get_tire_status` | 타이어 공기압 얼마야? | 저압 임계값을 현재 공기압으로 말하지 않는다 |
| C14 | `get_turn_signal_status` | 방향지시등 켜져 있어? | `mode=BlinkLeft`로 설정하고 왼쪽인지 확인 |
| C15 | `get_wiper_status` | 와이퍼 켜져 있어? | |
| C16 | `get_window_detail_status` | 창문 잠금 걸려 있어? 블라인드는? | 스마트 글라스 미지원과 실패를 값처럼 말하지 않는다 |

## AI 동작 확인

COM-23의 AI 동작 확인 항목에 답하는 별도 시행이다. 정상 성공률에 넣지 않는다.

| ID | 질문 | 방법 | 기록할 것 |
| --- | --- | --- | --- |
| B1 | `isError: true`인 PARTIAL의 값을 쓰는가 | R5, R6의 결과를 본다. 이 에뮬레이터에서는 항상 PARTIAL이다 | 배터리 값을 답했는지, 오류로만 답했는지 |
| B2 | 도구를 병렬로 부르는가 | 한 요청으로 "문, 창문, 배터리, 공조 상태 한 번에 알려줘" | 도구 호출 순서, 앱 로그의 시각, `BUSY` 유무 |
| B3 | 두 AI 동시 연결에서 `BUSY`가 얼마나 나오는가 | 두 앱에서 같은 때에 "좌석이랑 공조 상태 알려줘"를 5회 | 시행 수 중 `BUSY` 수 |
| B4 | `BUSY`를 어떻게 전하는가 | B2, B3에서 `BUSY`가 나온 시행 | 최종 답변 원문, 값처럼 말했는지 |
| B5 | 연결 해제를 값처럼 말하지 않는가 | 앱에서 "중지"를 누른 뒤 "문 상태 알려줘" | 최종 답변 원문, 호출 결과의 code |

B1에서 값을 쓰지 않거나 평소 사용(B2)에서 `BUSY`가 나오면 구현을 바꾸지 않는다. 사실과 제안을 나눠 보고하고 상대 개발자와 계약을 다시 합의한다.

## 실패 분류

실패한 시행은 가장 앞에서 실패한 단계 하나로 분류한다. 필수 4개는 분류와 관계없이 그 시행의 판정이 실패다.

| 분류 | 조건 | 근거 |
| --- | --- | --- |
| 도구 선택 실패 | 도구를 부르지 않았거나, 다른 도구나 잘못된 인자로 불렀다 | AI 화면의 도구 호출, 앱 로그의 requestId·query |
| 연결 실패 | 도구는 맞지만 앱에 닿지 않았거나 호출 단위 실패로 끝났다 | 호출 결과의 code·origin, 앱 로그 |
| SDK 조회 실패 | 앱까지 닿았지만 항목이 `OK`가 아니거나 값이 Console과 다르다 | 도구 결과의 항목, Console 값 |
| AI 응답 해석 실패 | 도구 결과는 맞지만 답변의 값·단위·영역이 다르거나 실패를 값처럼 말했다 | 도구 결과와 최종 답변 |
| 시간 초과 | 위가 모두 맞지만 필수 4개 시행이 5초를 넘었다 | t0·t1 |

## 근거 남기기

- 도구 이름, 인자, 최종 답변을 아래 표에 텍스트로 옮겨 적는다. 캡처가 필요하면 대화 영역만 잘라 계정 정보와 대화 목록을 빼고, 공유 전에 실행자가 확인한다.
- 앱의 requestId와 처리 시간은 시행 직후 아래 명령으로 얻는다.

```bash
adb -s emulator-5554 logcat -d -s BridgeService:I | tail -n 5
```

## 공통 기록

| 항목 | 값 |
| --- | --- |
| 실행자 역할 | |
| 일시 | |
| 커밋 | |
| 에뮬레이터 | Pleos_Connect_v2.1.0, API 34, Vehicle Service 2.3.22 |
| ChatGPT 제품·버전·작업 환경 | |
| Claude 제품·버전·작업 환경 | |

## 시행 기록

ID 하나에 AI별로 한 줄씩 쓴다. 필수 4개는 판정에 5초 기준을 포함한다. 후보는 t0·t1을 기록만 한다.

| ID | AI | 입력 | Console 값 | 부른 도구와 인자 | requestId | 앱 처리 ms | 도구 결과 요약 | 최종 답변 | t0 | t1 | 판정 | 실패 분류 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R1 | ChatGPT | | | | | | | | | | | |
| R1 | Claude | | | | | | | | | | | |
