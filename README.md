# Pleos_PA

현대차·기아 차량용 앱 개발 공모전 2인 팀 저장소.

1차 목표는 외부 일정 확인 → 순정 내비 목적지 설정 → ETA 기반 지연 판단 → 필요 시 승인 후 연락이다. 외부 AI가 Node.js MCP를 통해 차량 앱의 기능을 호출하는 구조로, SDK·외부 AI·서비스 지원은 검증 전제다.

## 현재 상태

Android Car Empty 프로젝트다. Activity, Pleos SDK, Foreground Service/Bridge, Node MCP는 아직 없다. 빌드되는 APK가 사용자 기능을 제공한다는 뜻은 아니다. 기존 [PoC](https://github.com/HyeeSeong/pleostest/tree/0d288c8aeab3b2fd08d09a257d894cbfeca793ca)에서 기능 단위로 선택 이전할 예정이다.

## 시작하기

JDK 25와 Android SDK API 37을 준비한 뒤:

```powershell
.\gradlew.bat :spotlessCheck :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

PR과 main push에서 CI가 같은 검사와 Structurizr 모델 검증을 실행한다.

## 문서

- [작업 지침](AGENTS.md): 협업 흐름, 환경, 검증, 문서 맵
- [아키텍처](docs/architecture/AGENTS.md): Structurizr C4 모델과 Mermaid 시나리오
- [결정 기록](docs/decisions/AGENTS.md): ADR
- [Linear 팀](https://linear.app/wonsub/team/COM/overview): 요구사항과 진행 관리
  - [공모전 1차 Project](https://linear.app/wonsub/project/pleos-connect-mcp-공모전-1차-29bf62b430ba) · [프로젝트 기획서](https://linear.app/wonsub/document/프로젝트-기획서-e1e6374c0bbb) · [요구사항 정의서](https://linear.app/wonsub/document/요구사항-정의서-1b7e6fcd5721)
