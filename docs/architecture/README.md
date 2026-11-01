# 아키텍처

2026-11-01 조사 기준. 현재 코드는 `a1313e0`, 참고 PoC는 `0d288c8aeab3b2fd08d09a257d894cbfeca793ca`에서 확인했다.

## 모델 범위

[workspace.dsl](workspace.dsl)은 두 시스템을 분리한다.

- `Current*`: Pleos_PA의 빈 Android 모듈. Deployment는 APK의 개발용 설치 위치를 표현하며 실제 설치/실행 완료를 뜻하지 않는다.
- `Reference*`: 참고 PoC에서 코드로 확인한 외부 Node stdio MCP → loopback HTTP/ADB → Android Service/SDK 구조. 이 저장소에 이전됐다는 뜻은 아니다.

System Context, Container, Deployment만 관리한다. Component는 필요가 드러날 때 추가한다. 런타임 순서·승인·분기는 [Mermaid 시나리오](scenarios/appointment-departure.md), 책임과 계약은 [boundaries](boundaries.md), 구조 변경은 [evolution](evolution.md)과 [ADR](decisions/0001-external-mcp-first.md)로 관리한다.

## 도구와 검증

2026-11-01 [공식 binaries](https://docs.structurizr.com/binaries), [DSL 문법](https://docs.structurizr.com/dsl/language), [validate](https://docs.structurizr.com/validate)을 확인했다.
구 CLI/Lite 대신 통합 Structurizr `2026.09.19`를 사용한다. Java WAR는 Java 21 이상이 필요하며 프로젝트 개발 JDK 25로 검증할 수 있다.

공식 `https://download.structurizr.com/structurizr-2026.09.19.war`를 저장소 밖에 다운로드한 후 루트에서:

```text
java -jar <도구경로>/structurizr-2026.09.19.war validate -workspace docs/architecture/workspace.dsl
```

CI는 같은 고정 버전의 WAR를 임시 디렉터리에 내려받아 검증한다. `latest`를 사용하지 않는다.
검증 기록은 [verification](../development/verification-2026-11-01.md). 모델 validation은 SDK 지원이나 배포 성공을 증명하지 않는다.
