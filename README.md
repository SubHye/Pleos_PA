# Pleos_PA

Pleos Connect MCP: 평소 쓰는 AI와 연결된 외부 서비스를 Pleos 차량 기능까지 확장하는 MCP 기반 연결 서비스. 현대차·기아 차량용 앱 개발 공모전 2인 팀 저장소다.

1차 목표는 ChatGPT에서 외부 일정 확인 → 순정 내비 목적지 설정 → ETA 기반 지연 판단 → 필요 시 승인 후 연락을 수행하고, 기존 차량 조회 4개 도구와 연결 중지를 재검증하는 것이다. 제출 데모는 PC의 Node.js MCP와 ADB 연결을 쓰는 에뮬레이터 환경이며, SDK·AI·외부 서비스 지원은 검증 전제다. Gleo 연동과 차량 직접 제어는 포함하지 않는다.

## 현재 상태

Android Car Empty 프로젝트다. Activity, Pleos SDK, Foreground Service/Bridge, Node MCP는 아직 없다. 빌드되는 APK가 사용자 기능을 제공한다는 뜻은 아니다. 기존 [PoC](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca)에서 기능 단위로 선택 이전할 예정이다.

## 시작하기

| 항목 | 기준 |
| --- | --- |
| Gradle / AGP | Wrapper 9.6.0 / 9.4.1 |
| JDK | 25 (Java 컴파일 타깃 11) |
| Android SDK | compile·target 37, min 28, Build Tools 36.0.0 |
| Pleos SDK, Node, ChatGPT, Emulator | 해당 기능 이전 시 기록 |

```powershell
.\gradlew.bat :spotlessCheck :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

PR과 main push에서 CI가 같은 검사와 Structurizr 모델 검증을 실행한다.

## 문서

- [작업 지침](AGENTS.md): 협업 흐름, 환경, 검증, 문서 맵
- [아키텍처](docs/architecture/AGENTS.md): Structurizr C4 모델과 Mermaid 시나리오
- [결정 기록](docs/decisions/AGENTS.md): ADR
- [Linear 팀](https://linear.app/wonsub/team/COM/overview): 요구사항과 진행 관리
  - [공모전 1차 Project](https://linear.app/wonsub/project/pleos-connect-mcp-공모전-1차-29bf62b430ba) · [프로젝트 기획서](https://linear.app/wonsub/document/프로젝트-기획서-e1e6374c0bbb) · [요구사항 정의서](https://linear.app/wonsub/document/요구사항-정의서-1b7e6fcd5721) (둘이 충돌하면 최신 결정을 반영한 요구사항 정의서 우선)
