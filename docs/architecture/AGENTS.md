# 아키텍처

- 정적 구조: [workspace.dsl](workspace.dsl) (Structurizr C4). System Context, Container, Deployment만 관리하고 Component는 필요가 드러날 때 추가한다.
- 런타임 흐름: [scenarios/](scenarios/appointment-departure.md) (Mermaid). 순서, 승인, 분기를 표현한다.
- 결정: [ADR](../decisions/AGENTS.md).

모델은 세 시스템을 구분한다. 구현이 이전되면 Current를 갱신하고, Target을 구현 사실로 표기하지 않는다.

| 구분 | 의미 |
| --- | --- |
| Current | 이 저장소에 실제로 있는 것 |
| Target | 2차 PT 전 목표 구조(기준 기획서 5.1, 5.2). 미구현 |
| Reference | 참고 PoC에서 코드로 확인한 구조. 이전되었다는 뜻이 아님 |

## 검증

구 CLI/Lite 대신 통합 Structurizr 고정 버전을 쓰며 Java 21 이상이 필요하다. `https://download.structurizr.com/structurizr-2026.09.19.war`를 저장소 밖에 받아 루트에서 실행한다. CI도 같은 버전을 쓴다.

```text
java -jar <도구경로>/structurizr-2026.09.19.war validate -workspace docs/architecture/workspace.dsl
```

validate 성공은 SDK 지원이나 배포 성공의 증거가 아니다.
