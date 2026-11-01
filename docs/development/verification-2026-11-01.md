# 환경 구성 검증 기록 — 2026-11-01

## 조사 범위

- 실제 루트: `C:/Users/byong/AndroidStudioProjects/Pleos_pa`.
- origin: `https://github.com/SubHye/Pleos_PA.git`; default/main 기준 `a1313e0`.
- 시작 시 main 작업 트리 깨끗함, origin/main 대비 추가 커밋 없음. `chore/development-architecture`로 전환.
- 참고 PoC: `HyeeSeong/pleostest`, 로컬과 원격 HEAD `0d288c8aeab3b2fd08d09a257d894cbfeca793ca` 일치. 읽기만 수행, 수정/실행/이전하지 않음.
- Linear 기획서 수정일 2026-10-31, 요구사항 수정일 2026-11-01을 읽음. 문서의 보고된 PoC 성공과 이번 세션의 직접 검증을 분리함.
- Linear field notes 도구는 서버에서 Tool not found로 반환. 일반 문서 조회는 성공.
- 신규 저장소는 Manifest/리소스와 예제 테스트만 존재. Activity, SDK, Bridge, Service, Node manifest/lock 없음.

## 실행 결과

| 검사 | 결과 | 의미/한계 |
| --- | --- | --- |
| Gradle --version | 9.6.0, daemon Java 25 기준 | Launcher는 PATH Java 17.0.12, daemon 요구와 구분 |
| assembleDebug / testDebugUnitTest / lintDebug --rerun-tasks | exit 0, BUILD SUCCESSFUL, 47 tasks executed | Windows 로컬 재실행. 앱 주요 소스는 NO-SOURCE |
| JUnit XML | 1 test, 0 failures/errors/skipped | 기존 addition_isCorrect뿐, 차량 기능 증거 아님 |
| Lint | 0 errors / 1 warning | AGP 9.5.0 업데이트 알림; 요청에 따라 기존 9.4.1 보존 |
| Structurizr validate | exit 0 | 공식 통합 WAR 2026.09.19, 라이브러리 6.2.3, JBR 25.0.3 |
| git diff --check | 통과 | 최종 커밋 직전 다시 확인 |
| MCP npm ci / npm test | 미적용 | 신규 저장소에 MCP 없음. 빈 성공용 패키지/검사를 만들지 않음 |
| GitHub Actions | 원격 실행 전 | 로컬 결과와 구분; PR에서 확인 |
| 차량/내비/메시지/AI E2E | 실행하지 않음 | 사용자 기능, SDK/서비스 지원, 계정/권한, 실차 검증 완료 주장 안 함 |

공식 Structurizr WAR 다운로드는 기본 환경에서 DNS 실패했으나 허용된 네트워크 경로로 성공했다. 도구는 저장소 밖 작업 폴더에 두고 커밋하지 않는다.
문서와 CI 설정만 변경했으므로 기능 단위 테스트는 추가하지 않았다. 기존 Android 검사 재실행, 실제 DSL validation, 링크/설정/범위 검토로 대체한다.

## 결정과 잔여 검증

외부 Node MCP 유지 방침과 신규 앱 미구현 상태를 분리했다. SDK/Node/Service는 별도 선택 이전 PR로 진행한다. 기존 버전·폴더·앱 소스는 변경하지 않았다.
CI는 Android 검사, 실제 MCP 도입 후 lock/Node pin을 요구하는 npm 검사, 고정 Structurizr validation으로 구성한다.
신규 APK 실행, SDK 이전 호환(minSdk 28 대 PoC 34), 실제 AI 제품 연결, 내비/ETA, 정상·지연·승인 분기, Embedded 기술 검증, 원격 중계는 남아 있다. 후속 후보와 기준은 [evolution](../architecture/evolution.md).
