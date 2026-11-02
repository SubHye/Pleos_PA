# 작업 지침

대상은 `SubHye/Pleos_PA`다. 시작할 때 root, remote, branch, git status를 확인한다. `HyeeSeong/pleostest`는 읽기 전용 참고이며 수정하지 않는다.

## 작업 흐름

Linear Issue → Coding Agent(Codex, Claude Code) → 구현·테스트·검증·PR → 코드 리뷰·통합. 세부 구현 방법은 제한하지 않는다.

- 상위 Issue는 사용자 관점에서 완성되는 기능, 하위 Issue는 독립 개발이 유리한 구현 작업(Android Adapter, MCP Tool, E2E 검증 등)이다.
- Linear의 Todo가 착수 가능 상태다. 선택한 개발자가 착수할 때 배정한다.
- 짧은 feature branch에서 작업한다. main에 직접 쓰지 않고 사용자 변경을 보존한다. PR은 [템플릿](.github/pull_request_template.md)을 따른다.
- 완료 기준: 수용 기준 증거, CI 통과, 상대 개발자 승인, main 병합, 코드와 문서 일치, Linear에 결과 링크. Agent 리뷰는 사람 리뷰를 대체하지 않는다.
- 현재 저장소 요금제에서는 브랜치 보호를 쓸 수 없다. 병합하는 사람이 CI 통과와 상대 승인을 직접 확인한다.
- Linear는 [wonsub의 COM 팀](https://linear.app/wonsub/team/COM/overview)을 쓴다. 커넥터가 다른 워크스페이스에 연결되어 있으면 추측하지 말고 알린다.
- PR #1·#2가 Issue에 자동 연결되지 않았다. Linear–GitHub 연동의 설치 여부는 확인하지 않았고 자동화에 의존하지 않는다. PR 링크를 Issue에 직접 첨부한다.
- Linear는 요구·우선순위·진행의 원천, 저장소는 코드·계약·아키텍처의 원천이다. 충돌하면 사실과 제안을 분리해 보고한다. 기획서와 요구사항 정의서가 다르면 요구사항 정의서를 우선한다.

## 환경과 검증

프로젝트 Wrapper만 사용한다. JDK 25, Android SDK API 37, Build Tools 36.0.0이 필요하다. SDK 경로는 `local.properties` 또는 `ANDROID_HOME`으로 지정하고 커밋하지 않는다. Unix에서는 `.\gradlew.bat` 대신 `bash ./gradlew`를 쓴다.

- 구현 중, 커밋 전: `.\gradlew.bat :spotlessApply`
- PR 제출 전: `.\gradlew.bat :spotlessCheck :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`

CI가 같은 검사를 실행한다. 실행하지 못한 검사, NO-SOURCE/skip, 모의 응답과 실제 SDK 결과를 구분해 보고한다. 빌드 성공을 차량 기능 완료로 보고하지 않는다.

## 범위

- 1차는 ChatGPT·Claude와 외부 Node.js MCP 방식을 유지한다. AI별 필수 수준은 요구사항 6.2를 따른다. Android Embedded 이전은 별도 검증 Issue다. Gleo 연동과 차량 직접 제어는 넣지 않는다. 문서의 미래안을 구현 사실로 바꾸지 않는다.
- 콘솔이나 문서에 있는 SDK API를 동작한다고 간주하지 않는다. 실행 검증 후 채택하고, 미지원이면 모의 값을 실제 결과로 반환하지 않는다.
- 폴더 이동, 버전 업그레이드, PoC 전체 복사를 하지 않는다. PoC는 기능 단위로 선택 이전하고, PoC 성공 기록을 신규 앱의 성공으로 옮기지 않는다.
- 공통 도구 이름·입출력·오류·권한·통신 계약 또는 ADR 변경은 영향과 대안을 PR에 적고 상대 개발자와 합의한다. 양쪽 검사와 문서를 같은 PR에서 갱신한다.

## 안전

- 토큰·API 키·서명키·개인 일정·연락처, 제출 규정상 공개가 제한되는 정보(개발자 실명 등)를 커밋이나 로그에 남기지 않는다.
- 사용자 승인 없이 차량/내비 변경, 메시지 전송, 네트워크 공개를 실행하지 않는다. 조회에 실패하면 값을 추측하거나 무조건 재시도하지 않는다.

## 맵

해당 영역을 작업하기 전에 그 AGENTS.md를 읽는다. 도구가 하위 파일을 자동으로 읽는다고 가정하지 않는다. CLAUDE.md에는 지침을 복제하지 않는다.

- [app/src](app/src/AGENTS.md): Android 소스
- [contracts/vehicle-read](contracts/vehicle-read/CONTRACT.md): 차량 조회 계약, 카탈로그, 공용 fixture
- `mcp/`: Node 측 계약 코드와 테스트. MCP 서버는 아직 없다(`npm --prefix mcp test`)
- [docs](docs/AGENTS.md): 문서
- [docs/architecture](docs/architecture/AGENTS.md): C4 모델과 런타임 시나리오
- [docs/decisions](docs/decisions/AGENTS.md): ADR
