# Pleos_PA

현대차·기아 차량용 앱 개발 공모전 2인 팀 저장소.

현재는 Android Car Empty 프로젝트다. Activity, Pleos SDK, Foreground Service/Bridge, Node MCP는 아직 없다. 빌드 가능한 APK가 사용자 기능을 제공한다는 뜻은 아니다.

1차 목표는 외부 일정 확인 → 순정 내비 목적지 설정 → ETA 기반 지연 판단 → 필요 시 승인 후 연락이다. SDK·외부 AI·서비스 지원은 검증 전제이며 Gleo 연동은 필수가 아니다. 외부 Node.js MCP 방식을 유지하고 Android Embedded MCP는 별도 후속 검증으로 분리한다.

## 시작하기

- [개발 환경](docs/development/setup.md) · [검증](docs/development/testing.md) · [협업](docs/development/workflow.md)
- [Agent 지침](AGENTS.md) · [아키텍처](docs/architecture/README.md)
- [현재 경계](docs/architecture/boundaries.md) · [재사용/이전 계획](docs/architecture/evolution.md)
- [대표 시나리오](docs/architecture/scenarios/appointment-departure.md)
- [환경 구성 검증 기록](docs/development/verification-2026-11-01.md)

## 진실의 원천

요구와 진행은 [Linear 공모전 Project](https://linear.app/wonsub/project/pleos-connect-mcp-공모전-1차-29bf62b430ba), 코드는 이 저장소, 계약·결정은 코드와 함께 변경하는 docs다.

- [프로젝트 기획서](https://linear.app/wonsub/document/프로젝트-기획서-e1e6374c0bbb) — 확인한 수정일 2026-10-31.
- [요구사항 정의서](https://linear.app/wonsub/document/요구사항-정의서-1b7e6fcd5721) — 확인한 수정일 2026-11-01.
- [기존 PoC](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca) — 읽기 전용 재사용 후보. 신규 저장소의 구현/검증 증거와 구분한다.

## 자동 검증

PR과 main push에서 Android assembleDebug/testDebugUnitTest/lintDebug와 Structurizr 모델을 검사한다.
MCP job은 `mcp/package.json` 도입 전에는 미적용으로 표시하고, 도입 후에는 package-lock과 Node 버전 파일을 요구하며 npm ci/npm test를 실행한다.
