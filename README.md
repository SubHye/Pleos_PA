# Pleos_PA

Pleos Connect MCP: 평소 쓰는 AI와 연결된 외부 서비스를 Pleos 차량 기능까지 확장하는 MCP 기반 연결 서비스. 현대차·기아 차량용 앱 개발 공모전 2인 팀 저장소다.

1차 목표는 외부 AI(ChatGPT·Claude)에서 외부 일정 확인 → 순정 내비 목적지 설정 → ETA 기반 지연 판단 → 필요 시 승인 후 연락을 수행하고, 기존 차량 조회 4개 도구와 연결 중지를 재검증하는 것이다. 충전소·주유소 검색, 경유지 추가, 주행 가능 거리는 필수가 아닌 조건부 기능으로, SDK 지원이 확인되면 포함한다. 제출 데모는 PC의 Node.js MCP와 ADB 연결을 쓰는 에뮬레이터 환경이며, SDK·AI·외부 서비스 지원은 검증 전제다. Gleo 연동과 차량 직접 제어는 포함하지 않는다.

## 현재 상태

Android Car Empty 프로젝트다. Activity, Pleos SDK, Foreground Service/Bridge, Node MCP는 아직 없다. 빌드되는 APK가 사용자 기능을 제공한다는 뜻은 아니다. 기존 [PoC](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca)에서 기능 단위로 선택 이전할 예정이다.

## 시작하기

| 항목 | 기준 |
| --- | --- |
| Gradle / AGP | Wrapper 9.6.0 / 9.4.1 |
| JDK | 25 (Java 컴파일 타깃 11) |
| Android SDK | compile·target 37, min 28, Build Tools 36.0.0 |
| Pleos SDK, Node, ChatGPT, Claude, Emulator | Pleos_PA에는 아직 없음. PoC 기준은 아래 표 |

```powershell
.\gradlew.bat :spotlessCheck :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

PR과 main push에서 CI가 같은 검사와 Structurizr 모델 검증을 실행한다.

## PoC 재현

기능 이전 전에 기존 PoC가 재현되는지 확인하는 절차다. **결과는 PoC의 것이며 Pleos_PA 기능의 성공이 아니다.** 요구사항 6.2의 시간·반복 판정도 아니다. 실행별 결과는 PR 본문에 남긴다.

| 항목 | 2026-11-02 재현 기준 |
| --- | --- |
| PoC | `HyeeSeong/pleostest` `0d288c8`, `com.example.pleostest` |
| Pleos SDK / Service | Vehicle SDK 2.3.2 / Vehicle Service 2.3.22 |
| Node | 24.15.0 |
| Emulator | `Pleos_Connect_v2.1.0`, API 34, 현재 사용자 10 |
| ChatGPT | 데스크톱 26.1027.3384.0 (Windows 패키지명 `OpenAI.Codex`), Plus, 로그인 필요 |
| Claude | 데스크톱 패키지 4.4212.0.0, Pro, 로그인 필요 |
| 연결 | AI 앱 → stdio Node MCP → PC `127.0.0.1:18765` → `adb forward` → 에뮬레이터 `127.0.0.1:18765` → Foreground Service → SDK |

**1. 빌드.** PoC를 별도 폴더에 clone해 `0d288c8`을 checkout한다. `local.properties`는 Android Studio로 만들거나 `sdk.dir=C\:/Users/<user>/AppData/Local/Android/Sdk`처럼 콜론을 이스케이프한다. 그렇지 않으면 Lint가 실패한다. `npm test`는 포워딩 전에 실행한다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
Set-Location mcp; npm ci; npm test; Set-Location ..
```

**2. 설치와 연결.** AVD를 Cold boot로 실행한 뒤, `<serial>`과 현재 사용자를 확인한다. 기존 매핑은 덮어쓰지 않는다.

```powershell
adb devices -l
adb -s <serial> shell am get-current-user
adb -s <serial> install -r --user 10 app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> forward --no-rebind tcp:8088 tcp:8088
adb -s <serial> forward --no-rebind tcp:18765 tcp:18765
adb -s <serial> shell am start --user 10 -W -n com.example.pleostest/.MainActivity
```

`http://127.0.0.1:8088/`에서 Virtual Console을 열어 둔다. 탭이 닫히거나 연결이 끊기면 값 변경이 `emulator_inactive`로 거부된다.

**3. AI 앱 등록.** 두 앱 모두 명령은 `node.exe`, 인자는 clone한 `mcp\src\server.mjs`의 절대 경로다. 다른 경로의 같은 서버가 등록되어 있으면 재현 중에 끈다. 호출한 도구의 서버 이름을 답변 화면에서 확인한다.

- ChatGPT: 설정의 MCP 서버 추가에서 STDIO를 고르고 작업 디렉터리를 `mcp`로 둔다. 화면 이름은 버전에 따라 다를 수 있다. 이 PC에서는 항목이 `%USERPROFILE%\.codex\config.toml`에 저장됐다.
- Claude: 패키지 설치에서는 `%LOCALAPPDATA%\Claude-Data\<패키지 폴더>\claude_desktop_config.json`의 `mcpServers`에 추가한다. `%APPDATA%\Claude`의 파일을 고치면 적용되지 않았다. 추가 후 트레이에서 앱을 완전히 종료하고 다시 실행한다. 이 앱에서 시작한 에뮬레이터는 재시작 때 함께 꺼질 수 있으니 재시작 후 2단계를 다시 확인한다.

**4. 조회·실패·복구.** Console의 `Emulation Controls → Convenience → Door Position`에서 앞좌측 문을 바꾸고, 앱마다 새 대화에서 앞좌측 문 상태를 묻는다. 도구를 동시에 호출하면 `BUSY`가 날 수 있다.

| 사례 | 조작 | 기대 결과 |
| --- | --- | --- |
| 조회 | 문 열림 | `get_front_left_door_position`이 `rawValue=1`, 열림으로 답변 |
| 연결 해제 | `adb -s <serial> forward --remove tcp:18765` | `ANDROID_UNREACHABLE`을 전달하고 문 상태를 추측하지 않음 |
| 복구 | `forward --no-rebind`로 복원, 문 닫힘 | 새 요청에서 `rawValue=0`, 닫힘으로 답변 |

끝나면 문을 닫고, 추가한 포워딩과 재현용 등록을 지우고, 꺼 둔 등록을 되돌린다. Bridge를 PowerShell로 직접 호출하면 `Expect` 헤더 때문에 400이 날 수 있다. Bridge는 이 헤더를 거부한다. `[Net.ServicePointManager]::Expect100Continue = $false`를 먼저 설정한다.

## 문서

- [작업 지침](AGENTS.md): 협업 흐름, 환경, 검증, 문서 맵
- [아키텍처](docs/architecture/AGENTS.md): Structurizr C4 모델과 Mermaid 시나리오
- [결정 기록](docs/decisions/AGENTS.md): ADR
- [Linear 팀](https://linear.app/wonsub/team/COM/overview): 요구사항과 진행 관리
  - [공모전 1차 Project](https://linear.app/wonsub/project/pleos-connect-mcp-공모전-1차-29bf62b430ba) · [프로젝트 기획서](https://linear.app/wonsub/document/프로젝트-기획서-e1e6374c0bbb) · [요구사항 정의서](https://linear.app/wonsub/document/요구사항-정의서-1b7e6fcd5721) (둘이 충돌하면 최신 결정을 반영한 요구사항 정의서 우선)
