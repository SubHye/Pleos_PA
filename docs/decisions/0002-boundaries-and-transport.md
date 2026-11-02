# ADR-0002: SDK 책임과 개발용 통신 경계

- 날짜: 2026-11-01
- 상태: 채택 (재사용 방향); 세부 계약 채택은 이전 PR에서 확정

## 결정
외부 AI는 사용자 맥락·도구 선택, Node MCP는 MCP 입출력·Bridge 통신, Android Adapter는 SDK 접근을 담당한다. Service가 Android 세션 자원을 소유한다. 개발 연결은 PoC의 stdio + loopback HTTP + ADB forward를 우선 유지한다.

## 근거와 한계
참고 PoC는 127.0.0.1:18765, POST /v2/vehicle-read, 10초 제한과 재시도 없는 읽기 경로를 구현한다. Host/Origin 검사는 사용자/프로세스 인증이 아니다. 외부 공개를 허용하지 않는다. 신규 저장소에는 아직 이 경로가 없다.

## 계약 변경
도구 이름·스키마·오류·시간 의미·권한·포트 변경은 Android/Node 양쪽 영향과 검사를 같은 PR로 제출하고 상대 개발자와 합의한다. Linear 제안 스키마를 현재 wire contract로 오인하지 않는다. 원격 접근 또는 Embedded 변경 시 이 결정을 대체하는 ADR을 작성한다.
