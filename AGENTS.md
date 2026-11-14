# 작업 지침

대상은 `SubHye/Pleos_PA`다. 시작할 때 root, remote, branch, git status를 확인한다.

## 작업 흐름

Linear Issue → Coding Agent(Codex, Claude Code) → 구현·테스트·검증·PR → 코드 리뷰·통합. 세부 구현 방법은 제한하지 않는다.

- 상위 Issue는 사용자 관점에서 완성되는 기능, 하위 Issue는 독립 개발이 유리한 구현 작업(Android Adapter, MCP Tool, E2E 검증 등)이다. 상위 Issue는 기준 기획서 3.1의 기능 행 하나에 대응하고, 4장의 해당 시나리오로 검수한다.
- Linear의 Todo가 착수 가능 상태다. 선택한 개발자가 착수할 때 배정한다.
- 짧은 feature branch에서 작업한다. main에 직접 쓰지 않고 사용자 변경을 보존한다. PR은 [템플릿](.github/pull_request_template.md)을 따른다.
- 완료 기준: 수용 기준 증거, CI 통과, 상대 개발자 승인, main 병합, 코드와 문서 일치, Linear에 결과 링크. Agent 리뷰는 사람 리뷰를 대체하지 않는다.
- 현재 저장소 요금제에서는 브랜치 보호를 쓸 수 없다. 병합하는 사람이 CI 통과와 상대 승인을 직접 확인한다.
- Linear는 [wonsub의 COM 팀](https://linear.app/wonsub/team/COM/overview)을 쓴다. 커넥터가 다른 워크스페이스에 연결되어 있으면 추측하지 말고 알린다.
- Linear는 요구·우선순위·진행의 원천, 저장소는 코드·계약·아키텍처의 원천이다. 충돌하면 사실과 제안을 분리해 보고한다. 기준 기획서는 [제출 기획서 초안](https://linear.app/wonsub/document/공모전-제출-기획서-초안-익숙한-ai와-차량주행기록-활용-6838b0dd196c)이며, 옛 프로젝트 기획서와 기획 전략은 동결했다. 기획서와 요구사항 정의서가 다르면 요구사항 정의서를 우선한다.

## 환경과 검증

프로젝트 Wrapper만 사용한다. JDK 25, Android SDK API 37, Build Tools 36.0.0이 필요하다. SDK 경로는 `local.properties` 또는 `ANDROID_HOME`으로 지정하고 커밋하지 않는다. Unix에서는 `.\gradlew.bat` 대신 `bash ./gradlew`를 쓴다.

- 구현 중, 커밋 전: `.\gradlew.bat :spotlessApply`
- PR 제출 전: `.\gradlew.bat :spotlessCheck :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :vehicle-sweep:test :nav-place-check:assembleDebug :nav-place-check:testDebugUnitTest`
- 제출용 APK: `.\gradlew.bat :app:assembleRelease`. 루트에 `keystore.properties`가 있으면 그 키로 서명하고, 없으면 서명 없는 APK를 만든다. 형식은 [keystore.properties.example](keystore.properties.example)에 있다. 키 파일과 `keystore.properties`는 커밋하지 않는다.

CI가 같은 검사를 실행한다. release 빌드는 CI가 실행하지 않는다. 실행하지 못한 검사, NO-SOURCE/skip, 모의 응답과 실제 SDK 결과를 구분해 보고한다. 빌드 성공을 차량 기능 완료로 보고하지 않는다.

## 안전

- 토큰·API 키·서명키·개인 일정·연락처, 제출 규정상 공개가 제한되는 정보(개발자 실명 등)를 커밋이나 로그에 남기지 않는다.
- 사용자 승인 없이 차량/내비 변경, 메시지 전송, 네트워크 공개를 실행하지 않는다. 조회에 실패하면 값을 추측하거나 무조건 재시도하지 않는다.

## 맵

해당 영역을 작업하기 전에 그 문서를 읽는다. 도구가 하위 파일을 자동으로 읽는다고 가정하지 않는다. CLAUDE.md에는 지침을 복제하지 않는다.

| 위치 | 내용 |
| --- | --- |
| [README](README.md) | 사람이 읽는 기술 소개. 단계별 범위, 구조, 제공하는 도구, 데모 실행 절차, 릴리스 정보. [공모전 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)가 요구하는 다섯 항목(앱 이름과 한 줄 소개, 실행 방법, 테스트 환경과 필요한 설정, 에뮬레이터 버전과 화면 규격, APK 위치)을 빼지 않는다. 기획 배경과 agent용 세부 내용은 쓰지 않는다 |
| [app/src](app/src/AGENTS.md) | Android 소스의 구조와 지킬 것 |
| [contracts/vehicle-read](contracts/vehicle-read/CONTRACT.md) | 차량 조회 계약, 카탈로그, 공용 fixture |
| [contracts/navigation](contracts/navigation/CONTRACT.md) | 내비 조회·목적지 설정·경유지 계약, 고정 장소 목록 |
| [tools/vehicle-sweep](tools/vehicle-sweep/README.md) | 에뮬레이터에서 도구 전체를 실행해 카탈로그 판정 근거를 만드는 도구 |
| [tools/nav-place-check](tools/nav-place-check/README.md) | 고정 장소 목록을 목적지로 설정·재조회해 목록 근거를 만드는 도구와 경유지 SDK 조사 도구. 내비를 바꾸므로 실행마다 승인받는다 |
| [tools/departure-check](tools/departure-check/README.md) | 약속 출발의 정상·지연 검수 절차와 읽기 전용 시간 비교 도구 |
| [scripts/COM5-WINDOWS.md](scripts/COM5-WINDOWS.md) | Windows에서의 설치·화면 검사·프로세스 재시작 절차 |
| [docs](docs/AGENTS.md) | 내용별 문서 위치 |
| [docs/architecture](docs/architecture/AGENTS.md) | C4 모델과 런타임 시나리오 |
| [docs/decisions](docs/decisions/AGENTS.md) | ADR |
| Linear | [공모전 1차 Project](https://linear.app/wonsub/project/pleos-pa-공모전-1차-29bf62b430ba), [공모전 2차 Project](https://linear.app/wonsub/project/pleos-pa-공모전-2차-106a0409819b), [제출 기획서 초안](https://linear.app/wonsub/document/공모전-제출-기획서-초안-익숙한-ai와-차량주행기록-활용-6838b0dd196c), [요구사항 정의서](https://linear.app/wonsub/document/요구사항-정의서-1b7e6fcd5721) |
