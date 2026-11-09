# ADR-0001: 1차 외부 MCP와 Embedded 후속 검증 분리

- 날짜: 2026-11-01
- 상태: 채택 (사용자 확정 방침). 정한 대로 Embedded 검증(COM-13)을 거쳐 [ADR-0008](0008-embedded-mcp-server.md)에서 MCP 서버를 앱으로 옮긴다

## 맥락
Pleos_PA는 빈 Android 모듈이다. 기존 PoC는 외부 Node stdio MCP와 Android HTTP Bridge/Foreground Service를 제공한다. 1차 대표 경험 검증과 서버 실행 위치 이전을 동시에 진행하면 검증 범위가 커진다.

## 결정
1차는 PoC의 외부 MCP 방식을 선택 재사용한다. Embedded MCP와 원격 중계는 현재 구현/배포 구조로 표시하지 않는다. Embedded 기술 검증을 별도 Issue로 수행하고 Go/No-Go 후 이전 Issue를 구체화한다.

## 결과
PC/ADB 의존성을 제출 자료에 명시한다. 기존 버전·도구 계약·검사 재사용을 우선한다. 새 저장소의 기능은 이전 후 다시 검증한다. 서버 위치를 바꿀 때는 전송/권한/생명주기/외부 접근/롤백을 새 ADR과 상호 리뷰로 결정한다.
