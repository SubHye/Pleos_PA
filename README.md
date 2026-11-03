# Pleos_PA

Pleos Connect MCP: 평소 쓰는 AI와 연결된 외부 서비스를 Pleos 차량 기능까지 확장하는 MCP 기반 연결 서비스. 현대차·기아 차량용 앱 개발 공모전 2인 팀 저장소다.

1차 목표는 외부 AI(ChatGPT·Claude)에서 외부 일정 확인 → 순정 내비 목적지 설정 → ETA 기반 지연 판단 → 필요 시 승인 후 연락을 수행하고, 기존 차량 조회 4개 도구와 연결 중지를 재검증하는 것이다. 충전소·주유소 검색, 경유지 추가, 주행 가능 거리는 필수가 아닌 조건부 기능으로, SDK 지원이 확인되면 포함한다. 제출 데모는 PC의 Node.js MCP와 ADB 연결을 쓰는 에뮬레이터 환경이며, SDK·AI·외부 서비스 지원은 검증 전제다. Gleo 연동과 차량 직접 제어는 포함하지 않는다.

## 현재 상태

Android Car Empty 프로젝트에 차량 조회 [계약](contracts/vehicle-read/CONTRACT.md), 양쪽 계약 테스트, 도구가 없는 [stdio MCP 서버](mcp/README.md)가 있다. COM-9 실행 경계 프로토타입과 debug 전용 UX 관찰·앱/알림 중지 진단 Activity가 있다. Foreground Service/Bridge, Vehicle Adapter, 신뢰된 승인 UI는 아직 없어 MCP에서 차량 값을 읽거나 내비를 변경할 수 없다. 진단의 원시 UX 관찰은 정차·운전자 영역의 증명이 아니며 내비 변경은 기본 차단된다. 빌드되는 APK가 사용자 기능을 제공한다는 뜻은 아니다. 기존 [PoC](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca)에서 기능 단위로 선택 이전할 예정이다.

## 시작하기

| 항목 | 기준 |
| --- | --- |
| Gradle / AGP | Wrapper 9.6.0 / 9.4.1 |
| JDK | 25 (Java 컴파일 타깃 11) |
| Android SDK | compile·target 37, min 28, Build Tools 36.0.0 |
| Vehicle SDK | 2.3.2 (계약 테스트 전용) |
| Node | 24.15.0 (`mcp/.node-version`), TypeScript 소스를 빌드 없이 실행 |
| TypeScript / oxlint / oxfmt | 7.0.2 / 1.90.0 / 0.75.0 ([ADR-0005](docs/decisions/0005-mcp-typescript-and-tooling.md)) |
| MCP SDK / zod | 2.4.0 / 4.2.0 |
| ChatGPT, Claude | 연결 경로는 [mcp/README.md](mcp/README.md), 실제 연결 결과는 COM-19 PR |
| Emulator | 해당 기능 이전 시 기록 |

```powershell
.\gradlew.bat :spotlessCheck :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
npm --prefix mcp ci
npm --prefix mcp run typecheck
npm --prefix mcp run lint
npm --prefix mcp run format:check
npm --prefix mcp test
```

PR과 main push에서 CI가 같은 검사와 Structurizr 모델 검증을 실행한다.

COM-9 로컬 Pleos 검증은 [Windows 수집 스크립트](scripts/verify-com9-local.ps1)를 쓴다. 명시한 에뮬레이터에서 `Install` → `RuntimeTest` → `Start` → 상태별 `Capture` 순으로 실행하며, 실제 기어·규제 전환과 화면/알림 접근성은 담당자가 별도로 관찰한다. 수집 성공과 JVM 테스트 통과를 실제 차량 동작 검증으로 간주하지 않는다.

## 문서

- [작업 지침](AGENTS.md): 협업 흐름, 환경, 검증, 문서 맵
- [아키텍처](docs/architecture/AGENTS.md): Structurizr C4 모델과 Mermaid 시나리오
- [결정 기록](docs/decisions/AGENTS.md): ADR
- [차량 조회 계약](contracts/vehicle-read/CONTRACT.md): 도구, wire 형식, 요청 생명주기
- [MCP 서버](mcp/README.md): 실행 환경, ADB·클라이언트 연결
- [Linear 팀](https://linear.app/wonsub/team/COM/overview): 요구사항과 진행 관리
  - [공모전 1차 Project](https://linear.app/wonsub/project/pleos-connect-mcp-공모전-1차-29bf62b430ba) · [프로젝트 기획서](https://linear.app/wonsub/document/프로젝트-기획서-e1e6374c0bbb) · [요구사항 정의서](https://linear.app/wonsub/document/요구사항-정의서-1b7e6fcd5721) (둘이 충돌하면 최신 결정을 반영한 요구사항 정의서 우선)
