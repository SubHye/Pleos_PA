# 작업 지침

- 대상은 `SubHye/Pleos_PA`. 먼저 root, remote, branch, git status를 확인한다. `HyeeSeong/pleostest`는 읽기 전용 참고이며 덮어쓰거나 수정하지 않는다.
- 현재 `app/`는 Android 초기 모듈, `gradle/`는 Wrapper/버전, `docs/architecture/`는 구조·계약·ADR, `docs/development/`는 재현·검증·협업 절차다. MCP/Bridge/SDK는 아직 이 저장소에 없다.
- Linear는 요구·우선순위·진행의 원천, 저장소는 코드·실제 계약·아키텍처의 원천이다. [README](README.md)의 연결을 따른다. 충돌은 사실과 제안을 분리해 보고한다.
- 두 개발자는 Ready Issue를 자율 선택하고 착수 상태를 공유한다. 고정 Android/MCP 담당자는 없다. 사용자 기능을 상위 Issue로, 독립 검증이 필요한 기술 작업만 하위 Issue로 둔다.
- Issue → 짧은 feature branch → 최소 구현·검증 → PR → 상대 개발자 리뷰 → CI 통과 → main 통합. 사용자 변경을 보존하고 main에 직접 쓰지 않는다.
- 1차는 외부 Node.js MCP 방식을 유지한다. Android Embedded 이전은 별도 검증/마이그레이션 Issue다. 문서의 미래안을 구현 사실로 바꾸지 않는다.
- 폴더 이동, 버전 업그레이드, PoC 전체 복사는 하지 않는다. 기능 이전은 [evolution](docs/architecture/evolution.md)에 따라 작게 나눈다.
- 공통 도구 이름·입출력·오류·권한·통신 계약 또는 ADR 변경은 영향과 대안을 PR에 적고 상대 개발자와 합의한다. 양쪽 계약 검사와 문서를 같은 PR에서 갱신한다.
- 검증: Windows ` .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`; Unix `bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. 환경·DSL·향후 MCP 명령은 [testing](docs/development/testing.md).
- 실행하지 못한 검사, NO-SOURCE/skip, 모의 응답과 실제 SDK 결과를 구분한다. 빌드 성공을 차량 기능 완료로 보고하지 않는다.
- 토큰·API 키·서명키·개인 일정·연락처는 커밋/로그에 남기지 않는다. 로컬 설정은 gitignore를 확인한다.
- 사용자 승인 없는 차량/내비 변경·메시지 전송·네트워크 공개를 실행하지 않는다. 조회 실패 시 값을 추측하거나 무조건 재시도하지 않는다.
- 하위 AGENTS는 기술별 규칙이 실제로 다를 때만 추가한다. CLAUDE.md에 지침을 복제하지 않는다.
