---
name: pleos-emulator-test
description: Pleos Connect Emulator에서 앱, Vehicle/Navigation SDK, MCP 연동을 실행하고 증거로 판정한다. Pleos 에뮬레이터 테스트, Virtual Console 차량 상태 변경, SDK 응답과 차량 상태 대조, Bridge·MCP 통합 검증, 차량 기능 재검증을 요청받았을 때 사용한다. 실차, 일반 폰 AVD, 순수 JVM 단위 테스트에는 사용하지 않는다.
compatibility: Windows PowerShell 5.1+, Android SDK Platform-Tools, Pleos Connect Emulator 2.1.0. Android CLI와 agent-browser 권장.
---

# Pleos Emulator Test

범용 Android 조작은 기존 도구에 맡긴다. 이 스킬은 Pleos 고유 부분인 차량 상태 시뮬레이션, 계층 간 대조, 판정 규칙만 다룬다.

## 도구 역할

| 작업 | 도구 |
| --- | --- |
| UI 조회·탭·입력·스크린샷, 설치 | Android CLI. 명령은 공식 `android-cli` 스킬과 `android <cmd> --help`를 따른다 |
| 로그, 포워딩, 패키지, 사용자 조회 | `adb` |
| Virtual Console 페이지 유지와 조작 | `agent-browser` |
| 환경 진단 | `scripts/preflight.ps1` |
| 증거 수집 | `scripts/collect-evidence.ps1` (화면 요약 `ui-summary.txt`, 앱 알림 `notifications.txt` 포함) |
| 화면 빠르게 보기 | `scripts/summarize-layout.ps1 -Serial <serial>`: 이름 있는 요소와 동작 가능한 요소만 한 줄씩 |
| 내비 목적지 설정·재조회 | 저장소의 `tools/nav-place-check` ([pleos-specific.md](references/pleos-specific.md#내비게이션)) |

새 MCP 서버나 제어 프로그램을 만들지 않는다.

## 반드시 지킬 것

참조 문서를 읽지 않아도 아래는 항상 적용한다.

- **에뮬레이터는 한 번에 한 세션만 쓴다.** 내비·차량 SDK 콜백은 등록된 모든 앱에 가므로, 다른 세션의 조회·변경이 내 판정을 바꾸고 내 조회가 남의 증거에 섞인다. 시작 전에 preflight의 `device_lock`, `foreign_activity`를 본다. 다른 세션이 쓰고 있으면 끝날 때까지 SDK 호출과 상태 변경을 하지 않고 사용자에게 알린다.
  ```powershell
  # 시작: 잠금을 건다(이미 있으면 걸지 않는다)
  adb -s <serial> shell "test -e /data/local/tmp/pleos-test.lock || echo '<세션·이슈> <ISO 시각>' > /data/local/tmp/pleos-test.lock"
  # 끝: 내가 건 잠금만 푼다
  adb -s <serial> shell rm /data/local/tmp/pleos-test.lock
  ```
- preflight `ui_automation`이 `WARN`이면 Android-MCP 플러그인이 UiAutomation을 잡고 있어 Android CLI 조작이 실패한다. 그 프로세스를 끄지 말고 사용자에게 알린다([emulator-setup.md](references/emulator-setup.md#android-mcp-플러그인과의-충돌)).
- `adb logcat -c`를 쓰지 않는다. 다른 작업의 증거까지 지운다. `logcat -T '<MM-DD HH:MM:SS.mmm>'`나 `-t`로 구간을 나눈다.
- 증거는 `scripts/collect-evidence.ps1`로 모은다. 임시 스크립트로 대신하면 `meta.json`과 마스킹이 빠진다. 도구 로그는 `-LogTag`로 함께 저장한다.
- 화면 조작은 Android CLI 선택자(`android input --device=<serial> tap "text=..."`)를 먼저 쓴다. 좌표가 필요하면 `android layout` 결과에서 얻는다. 스크린샷만 보고 좌표를 정하지 않는다. 화면 키보드도 `android layout`의 `INPUT_METHOD` 창에 나온다(`uiautomator dump`에는 안 나온다).
- 실행 전에 시나리오 표(초기 조건, 조작, 기대값, 계층, 판독 앱, 복구)를 대화나 보고서에 먼저 적는다.
- 보고는 [test-protocol.md](references/test-protocol.md)의 양식을 쓴다. 같은 조작을 반복하면 반복 실행 표를 쓴다.
- **내비 시나리오면 [pleos-specific.md](references/pleos-specific.md#내비게이션)의 내비 절과 [test-protocol.md](references/test-protocol.md)를 실행 전에 읽는다.**

## 절차

1. **진단.** 스킬 폴더의 스크립트를 실행한다. `FAIL`이면 원인을 해결하거나 시나리오를 `BLOCKED`로 기록한다.
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\pleos-emulator-test\scripts\preflight.ps1 -Serial <serial> -Package <package>
   ```
2. **시나리오 정의.** 실행 전에 초기 조건, 조작, 기대값, 대조할 계층을 적는다. 어느 앱이 값을 읽는지 명시한다.
3. **기준값 기록.** 조작 전에 각 계층의 값과 증거를 수집한다.
4. **조작.** UI는 레이아웃을 조회해 선택자로 찾는다. 차량 상태는 Virtual Console로 바꾼다 ([pleos-specific.md](references/pleos-specific.md)).
5. **대조.** 계층별 값을 기대값과 비교하고 증거를 다시 수집한다.
6. **복구.** 바꾼 상태, 포워딩, 브라우저 세션, 앱을 시작 상태로 돌린다. 내가 건 `pleos-test.lock`을 푼다.
7. **보고.** [test-protocol.md](references/test-protocol.md)의 양식으로 시나리오별 상태를 보고한다. 수정했다면 같은 시나리오를 다시 실행한다.

## 판정 원칙

상태는 `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN` 중 하나다.

- 각 계층은 자기 값만 증명한다. Console 값이나 앱 화면을 SDK 응답으로 쓰지 않는다.
- 명령 성공, HTTP 200, `Initialized` 표시, 빌드 성공은 PASS 근거가 아니다.
- 증거가 부족하면 PASS가 아니다. 원인을 확정하지 못하면 `원인 미확정`으로 적고 추측하지 않는다.
- 판독한 앱을 기록한다. PoC 앱의 결과를 Pleos_PA의 결과로 옮기지 않는다.
- 모의 응답과 실제 SDK 결과를 구분한다.

## 안전 경계

- 상태를 바꾸기 전에 preflight의 `pleos_identity`가 `OK`인지 확인한다. 실차와 다른 기기에는 쓰지 않는다.
- 시나리오에 필요한 신호만 바꾸고 끝나면 복구한다. 사용자가 요청하지 않은 신호는 바꾸지 않는다.
- `requestRoute`, `cancelRoute`, 순정 내비 화면의 "안내 시작" 등 내비 변경과 메시지 전송은 실행 전에 사용자 승인을 받는다. 승인 요청에는 장소·횟수·복구 방법을 담은 실행 목록을 보인다. 받은 승인은 그 목록에만 유효하고, 목록 밖의 실행이나 재실행은 다시 승인받는다.
- 순정 내비 경로 미리보기의 "안내 시작"에는 카운트다운이 있어 그대로 두면 안내가 시작된다. 승인 없이 검색 결과의 길안내 버튼을 누르지 않는다.
- Console과 Bridge는 `127.0.0.1`로만 접근한다. 포워딩은 `--no-rebind`로 만들고 기존 매핑을 덮어쓰지 않는다.
- 실패하면 무조건 재시도하지 않는다. 조회 실패를 값으로 추측하지 않는다.
- 증거에 토큰이나 개인 일정·연락처를 남기지 않고, 증거를 커밋하지 않는다.

## 참조

- [emulator-setup.md](references/emulator-setup.md): 설치, 기동, 포워딩, 도구별 제약. 환경 문제나 도구 오류가 있을 때 읽는다.
- [pleos-specific.md](references/pleos-specific.md): Virtual Console, SDK·MCP·내비 절차, 순정 내비 화면 조작, 검증 기록. 차량·내비 시나리오 전에 읽는다.
- [test-protocol.md](references/test-protocol.md): 계층 정의, 판정 기준, 보고 양식, 비밀정보 규칙. 보고 전에 읽는다.
