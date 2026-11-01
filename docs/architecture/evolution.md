# 재사용과 발전 순서

## 1차 결정

외부 Node MCP를 유지한다. 다만 신규 저장소에는 아직 해당 코드가 없다. Empty 프로젝트 전체를 PoC로 교체하거나 패키지/폴더를 대규모 이동하지 않는다. [ADR-0001](decisions/0001-external-mcp-first.md), [ADR-0002](decisions/0002-boundaries-and-transport.md).

## 재사용 절차

1. PoC 고정 커밋의 재사용 권한/라이선스와 파일 출처를 확인한다. 현재 신규 저장소의 applicationId·Wrapper·AGP를 유지한다.
2. 별도 기능 Issue/PR에서 Vehicle 2.3.2 의존성·Maven·권한·Adapter와 기존 JVM 검사를 선택 이전한다. 신규 minSdk 28과 PoC minSdk 34 차이를 SDK 요구/실행 환경으로 검증하고 합의 없이 변경하지 않는다.
3. Bridge·Foreground Service·중지 UI를 세션 단위로 이전하고 loopback/취소/BUSY/timeout 및 에뮬레이터 생명주기를 검증한다.
4. 같은 기능의 Node 부분을 `mcp/`에 package.json·package-lock·검사와 함께 이전한다. 기존 Node >=24.15.0, MCP SDK 2.4.0, zod 4.2.0을 우선 보존한다. 검증한 정확한 Node 버전을 mcp/.node-version에 기록한다. CI가 이 세 파일을 요구한다.
5. 도구 목록·스키마·오류 의미 동등성을 확인한 후 신규 APK/Node 조합으로 실제 클라이언트 연결을 재검증한다. PoC 성공 기록을 신규 앱 성공으로 복사하지 않는다.
6. NaviHelper 목적지/ETA와 동일 AI의 일정·차량·메시지 조합을 먼저 검증하고 대표 시나리오를 확장한다.

## 후속 Issue 후보 (이 문서로 생성/완료 처리하지 않음)

| 후보 | 완료 증거 |
| --- | --- |
| PoC 선택 이전 및 신규 패키지 호환 | 출처·버전·계약 비교, JVM/MCP 검사, 신규 APK 실제 연결 |
| 내비 목적지·ETA 지원 검증 | 실제 SDK API/단위/시간 기준, 지원·미지원 증거; 기존 COM-3과 중복 확인 |
| 동일 AI 통합 경로 검증 | 제품·버전·계정·연결 방식, 정상/지연/승인 거절 증거; 기존 COM-10과 중복 확인 |
| Embedded MCP 기술 검증 | 격리 브랜치에서 tools/list 및 최소 도구, Kotlin/Android 전송·서비스·자원·보안 검증, Go/No-Go ADR |
| Embedded 이전 | 기술 검증 Go 이후만 착수; 기존 계약 동등성·E2E·롤백 및 외부 연결 경로 확정 |
| 원격 중계/제품 배포 | 사용자/차량 인증, 권한·세션·암호화·중지 설계와 검증; ADB 대체 여부 결정 |

Embedded를 넣어도 클라우드 AI에서 차량 localhost에 접근할 수 있게 되는 것은 아니다. 외부 연결 경로를 별도로 검증한다. Gleo·실차 원격 제어·핸즈프리는 1차 필수 범위가 아니다.
