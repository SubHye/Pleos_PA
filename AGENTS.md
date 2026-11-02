# 작업 지침

대상은 `SubHye/Pleos_PA`다. 시작할 때 root, remote, branch, git status를 확인한다. `HyeeSeong/pleostest`는 읽기 전용 참고이며 수정하지 않는다.

## 작업 흐름

Linear Issue → Coding Agent(Codex, Claude Code) → 구현·테스트·검증·PR → 코드 리뷰·통합. 세부 구현 방법은 제한하지 않는다.

- 짧은 feature branch에서 작업한다. main에 직접 쓰지 않고 사용자 변경을 보존한다.
- 완료 기준: 수용 기준 증거, CI 통과, 상대 개발자 승인, main 병합, 코드와 문서 일치, Linear에 결과 링크. Agent 리뷰는 사람 리뷰를 대체하지 않는다.
- Linear는 요구·우선순위·진행의 원천, 저장소는 코드·계약·아키텍처의 원천이다. 충돌하면 사실과 제안을 분리해 보고한다.

## 환경과 검증

프로젝트 Wrapper만 사용한다. JDK 25, Android SDK API 37, Build Tools 36.0.0이 필요하다. SDK 경로는 `local.properties` 또는 `ANDROID_HOME`으로 지정하고 커밋하지 않는다. Unix에서는 `.\gradlew.bat` 대신 `bash ./gradlew`를 쓴다.

- 구현 중, 커밋 전: `.\gradlew.bat :spotlessApply`
- PR 제출 전: `.\gradlew.bat :spotlessCheck :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`

CI가 같은 검사를 실행한다. 실행하지 못한 검사, NO-SOURCE/skip, 모의 응답과 실제 SDK 결과를 구분해 보고한다. 빌드 성공을 차량 기능 완료로 보고하지 않는다.

## 범위

- 1차는 외부 Node.js MCP 방식을 유지한다. Android Embedded 이전은 별도 검증 Issue다. 문서의 미래안을 구현 사실로 바꾸지 않는다.
- 폴더 이동, 버전 업그레이드, PoC 전체 복사를 하지 않는다. PoC는 기능 단위로 선택 이전하고, PoC 성공 기록을 신규 앱의 성공으로 옮기지 않는다.
- 공통 도구 이름·입출력·오류·권한·통신 계약 또는 ADR 변경은 영향과 대안을 PR에 적고 상대 개발자와 합의한다. 양쪽 검사와 문서를 같은 PR에서 갱신한다.

## 안전

- 토큰·API 키·서명키·개인 일정·연락처를 커밋이나 로그에 남기지 않는다.
- 사용자 승인 없이 차량/내비 변경, 메시지 전송, 네트워크 공개를 실행하지 않는다. 조회에 실패하면 값을 추측하거나 무조건 재시도하지 않는다.

## 맵

해당 영역을 작업하기 전에 그 AGENTS.md를 읽는다. 도구가 하위 파일을 자동으로 읽는다고 가정하지 않는다. CLAUDE.md에는 지침을 복제하지 않는다.

- [app/src](app/src/AGENTS.md): Android 소스
- [docs](docs/AGENTS.md): 문서
- [docs/architecture](docs/architecture/AGENTS.md): C4 모델과 런타임 시나리오
- [docs/decisions](docs/decisions/AGENTS.md): ADR
