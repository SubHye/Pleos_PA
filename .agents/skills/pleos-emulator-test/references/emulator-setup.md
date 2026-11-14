# 환경 설치와 도구 제약

검증일 2026-11-02, Windows 11, `Pleos_Connect_v2.1.0`. 버전이 바뀌면 다시 확인한다.

## 에뮬레이터

공식 가이드: [설치](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator), [구성](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator).

- Android Studio Device Manager에서 Automotive 기기 `Pleos Connect v2.1.0`, API 34 이미지로 만든다. RAM은 4GB.
- **Default boot는 Cold로 둔다.** 공식 문서에 따르면 Quick boot에서는 VHAL을 쓸 수 없다. preflight의 `cold_boot_config`로 확인한다.
- 이미지를 다시 설치할 때는 공식 문서대로 이전 버전을 먼저 제거한다.

## Android CLI

**cmd.exe**에서 공식 스크립트로 설치한다. `%USERPROFILE%\AppData\AndroidCLI`에 설치되고 사용자 PATH에 추가된다. 새 셸을 열어야 PATH가 반영된다.

```bat
curl -fsSL https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd -o "%TEMP%\i.cmd" && "%TEMP%\i.cmd"
```

사용 데이터 수집은 기본값이 켜짐이다. 끄려면 `--no-metrics`를 쓴다.

공식 스킬은 프로젝트 범위로 설치할 수 있다. `claude-code`는 `.claude/skills`, `codex`는 `.agents/skills`에 들어간다. `android init`은 전역 변경이라 이 스킬에 필요하지 않다.

```powershell
android skills add android-cli --project=<저장소 경로> --agent=claude-code,codex
```

### Pleos에서 확인한 동작 (CLI 1.1.16717390)

| 명령 | 결과 |
| --- | --- |
| `layout --device=<serial>` | 동작. 시스템 바와 차량 패널을 포함한 **모든 창**을 반환 |
| `screen capture --annotate`, `screen resolve` | 동작. 좌표는 2560x1440 기준 |
| `input --device=<serial> tap "text=..."` | 동작. 한글 선택자 가능. `--match=first`, 정규식 `text=/^값$/` 지원 |
| `install` | 동작. user 0과 현재 사용자 10 모두에 설치됨 |
| `run` | 실행 가능한 Activity가 없으면 `No default component found`로 설치 전에 중단. 설치만 하려면 `install` |

주의할 점:

- `layout`은 처음 실행할 때 기기에 `com.android.cli.interact.instrumentation`을 설치한다. `pm list packages -3`에 보여도 정상이다.
- **그 계측 프로세스가 살아 있는 동안 `uiautomator dump`는 `Killed`로 끝난다.** UiAutomation 연결이 하나뿐이라 연결 단계에서 예외가 난다(logcat `UiAutomationConnection.connect`). `collect-evidence.ps1`은 이때 `android layout`으로 `layout.json`을 대신 저장한다. 다른 세션이 쓰는 중일 수 있으니 계측 프로세스를 강제로 끄지 않는다.
- 비ASCII 문자는 stdout에서 깨진다. `-o <파일>`로 저장하고 `Get-Content -Encoding UTF8`로 읽는다. 파일 내용은 정상이다.
- 공식 문서에 AAOS 지원 언급은 없다. 위 표는 이 환경의 실행 결과이며 공식 보증이 아니다.

## adb 대체 절차

Android CLI를 쓸 수 없을 때 사용한다.

| 작업 | 명령 |
| --- | --- |
| UI 조회 | `adb -s <serial> exec-out uiautomator dump /dev/tty` |
| 스크린샷 | `cmd /c "adb -s <serial> exec-out screencap -p > out.png"` |
| 탭 | `adb -s <serial> shell input tap <x> <y>` |

- `uiautomator dump`는 **포커스된 창만** 반환한다. 시스템 바와 왼쪽 차량 패널은 빠진다.
- Windows PowerShell 파이프·`>` 리다이렉트는 바이너리를 손상시킨다(파일 앞에 BOM이 붙는다). 셸과 관계없이 되는 방법은 기기에 저장한 뒤 가져오는 것이다.
  ```powershell
  adb -s <serial> shell screencap -p /data/local/tmp/shot.png
  adb -s <serial> pull /data/local/tmp/shot.png <로컬 경로>
  ```
- 고정 좌표는 레이아웃 조회 결과에서 얻은 값만 쓴다.
- 한글은 `adb shell input text`로 입력되지 않는다. 앱의 화면 키보드 키를 누른다(순정 내비는 [pleos-specific.md](pleos-specific.md#순정-내비-화면-조작-332-rc5-hotfix3-2560x1440)).

## Windows 셸 함정

| 셸 | 증상 | 대처 |
| --- | --- | --- |
| Windows PowerShell 5.1 | BOM 없는 UTF-8 `.ps1` 안의 한글 리터럴이 깨져 파싱 오류가 난다 | 스크립트를 UTF-8 BOM으로 저장하거나 한글을 인자로 넘긴다 |
| Windows PowerShell 5.1 | 네이티브 명령의 stderr를 `2>&1`로 받으면 `adb pull` 성공 메시지도 `NativeCommandError`로 보인다 | 종료 코드와 결과 파일로 판단한다 |
| Git Bash | `/data/local/tmp/...` 같은 기기 경로를 Windows 경로(`C:/.../Git/data/...`)로 바꾼다 | `MSYS_NO_PATHCONV=1`을 붙인다 |
| Git Bash | `${s:i:1}`이 한글을 바이트 단위로 자른다 | `LC_ALL=C.UTF-8`로 실행한다 |
| 공통 | 실행 후 끝나지 않는 Activity를 `am start`로 다시 부르면 기존 화면을 앞으로 가져오기만 한다(`result code=2`) | 테스트 도구는 실행이 끝나면 `finish()`하거나 `am start -S`를 쓴다 |

## Automotive 환경의 제약

- 포그라운드 사용자는 10이다. 앱 조회와 실행에 `--user 10`을 붙인다: `pm path --user 10 <pkg>`, `am start --user 10 -W -n <pkg>/<activity>`. 현재 값은 `am get-current-user`로 읽는다.
- `KEYCODE_HOME`으로는 앱이 onStop 되지 않을 수 있다. 앱을 다시 시작해야 하면 `am force-stop --user 10 <pkg>` 후 `am start`를 쓴다.
- `logcat -c`는 다른 작업의 증거까지 지운다. 지우지 말고 시각이나 `-t`로 구간을 나눈다. 시작 시각은 `adb shell date '+%m-%d %H:%M:%S.000'`로 얻고 `logcat -d -T '<그 값>'`로 읽는다.

## agent-browser

- 공용 기본 세션을 쓰지 말고 `--session pleos-vc`처럼 이름을 붙인다.
- **PowerShell에서 첫 `open`을 파이프로 실행하면 명령이 끝나지 않는다.** 데몬이 stdout을 물려받기 때문으로 보인다(원인 미확정). 리다이렉트로 실행한다.
  ```powershell
  cmd /c "agent-browser --session pleos-vc open http://127.0.0.1:8088/ > NUL 2>&1"
  ```
- 끝나면 닫는다: `cmd /c "agent-browser --session pleos-vc close > NUL 2>&1"`

## Android-MCP 플러그인과의 충돌

Claude 앱 확장 Android-MCP(CursorTouch 0.1.0, uiautomator2 기반)를 2026-11-10에 읽기 전용 Snapshot으로 확인했다.

- 앱이 켜져 있는 동안 서버가 항상 떠 있다. 기기에서 `/data/local/tmp/u2.jar`로 `com.wetest.uia2.Main`을 띄우고, PC에 `tcp:<포트> → tcp:9008` 포워딩을 만든다. 대상 기기는 서버를 시작할 때 정해진다(기본값 `emulator-5554`).
- **그 서버가 UiAutomation을 잡고 있어서 `android layout`은 "Error communicating with instrumentation server"로, `uiautomator dump`는 `Killed`로 끝난다.** preflight `ui_automation`이 이 상태를 `WARN`으로 알린다.
- 서버 프로세스는 강제로 끄지 않는다. 플러그인 것이다. Android CLI로 조작해야 하면 사용자에게 Claude 앱에서 Android-MCP를 끄도록 요청한다. 켜 둔 채로는 Snapshot으로 화면을 읽기만 한다.
- Click, Type, Swipe는 좌표만 받는다. 승인 규칙과 잠금이 없어 "안내 시작" 같은 버튼을 누를 수 있으므로 내비 시나리오에는 쓰지 않는다.
- 플러그인을 제거하면 기기 서버는 사라지지만 PC의 `tcp:13066 → tcp:9008` 포워딩은 남았다. `adb -s <serial> forward --remove tcp:13066`으로 지운다. 제거 뒤 preflight `ui_automation`이 `free`, `summarize-layout.ps1 -Serial`이 정상임을 확인했다(2026-11-10).
- 이 서버의 짧은 요소 목록은 `scripts/summarize-layout.ps1`이 같은 방식으로 제공한다.

## 채택하지 않은 도구

| 도구 | 이유 |
| --- | --- |
| Journeys | 공식 문서에 CLI 실행 명령과 파일 위치가 없다 |
| Maestro | 공식 문서에 Automotive 언급이 없고, Android CLI 대비 추가 가치를 확인하지 못했다 (미실행) |
| Playwright | `agent-browser`로 Console 조작이 충분하다. 대체 수단으로만 쓴다 (미실행) |

## 스킬 발견

| 에이전트 | 경로 |
| --- | --- |
| Codex | `.agents/skills/pleos-emulator-test/` (원본) |
| Claude Code | `.claude/skills/pleos-emulator-test/SKILL.md` (원본을 가리키는 진입 파일) |

이 PC는 `core.symlinks=false`라 심볼릭 링크 대신 진입 파일을 쓴다. Claude Code에서 새 스킬 폴더가 보이지 않으면 `/reload-skills`를 실행한다.
