# ADR-0007: 앱 내장 MCP 서버 Go/No-Go (COM-13)

- 날짜: 2026-11-06
- 상태: 제안. 상대 개발자 합의가 필요하다. ADR-0002를 대체하지 않는다.

## 맥락

[ADR-0001](0001-external-mcp-first.md)은 Embedded MCP를 별도 Issue로 검증하고 Go/No-Go를 정하도록 했다. COM-13은 격리 브랜치 [`experiment/com-13-embedded-mcp`](https://github.com/SubHye/Pleos_PA/tree/experiment/com-13-embedded-mcp)에서 이를 실험했다. 이 브랜치는 main에 병합하지 않는다.

**실험 구성**
- Kotlin MCP SDK로 앱 안에 MCP 서버를 열었다. 위치는 `127.0.0.1:18766/mcp`, 전송은 Streamable HTTP다.
- 도구는 `get_energy_level` 하나다. 기존 Bridge와 같은 게이트웨이와 읽기 슬롯을 거친다.
- PC는 `adb forward tcp:18766`으로 연결한다.

**사전 Go 조건**
- 반드시 통과: 빌드(Kotlin·AGP 상향 없음), 동작, 결과 동등성, 생명주기, 공개 범위
- 허용 범위: 속도는 중앙값 +200ms 이내, 자원은 ANR·OOM·FGS 강제 종료·중지 후 잔존이 없으면 증가해도 허용

**판정 환경**
- 측정: Pleos Connect Emulator v2.1.0(API 34), Vehicle Service 2.3.22, Vehicle SDK 2.3.2
- 대조: Virtual Console의 EV SOC
- 연료는 이 이미지에서 `PROPERTY_UNSUPPORTED`라 대조하지 못했다.
- 실행 기록은 PR 본문에 있다.

## 결정

**Go를 제안한다.** COM-12의 후보 구성은 Kotlin MCP SDK **0.14.0**(Ktor 3.4.3 CIO)의 **stateless** Streamable HTTP다.

실제 AI 호출은 ChatGPT 계열에서 확인했다. Codex CLI 0.166.0(ChatGPT 로그인)이 Streamable HTTP로 내장 서버의 `get_energy_level`을 호출했고, Console의 EV SOC 42와 같은 "42%"로 답했다. Claude Code는 서버 연결까지만 확인했고, 계정 크레딧 부족으로 도구 호출은 하지 못했다(BLOCKED). 두 계열의 E2E는 COM-12에서 다시 검증한다.

| 조건 | 결과 |
| --- | --- |
| 빌드 | 통과. AGP 9.4.1 내장 Kotlin 2.2.10으로 CI 검사 4종과 release 조립이 통과했다. |
| 동작 | 통과. 공식 TS 클라이언트로 `tools/list`·`get_energy_level`이 동작했고, 프로토콜 `2025-11-25`로 협상됐다. EV SOC 100·58이 Console, SDK 로그, Bridge, 두 MCP 경로에서 일치했다. ChatGPT(Codex CLI) 실제 호출이 SOC 42를 그대로 답했고, 앱 로그의 `requestId`가 일치했다. Claude Code 도구 호출은 BLOCKED(크레딧)다. |
| 결과 동등성 | 통과. 공용 `tool-results.json`의 items 형식 14개 사례가 Node와 같았다. 에뮬레이터에서도 수신 시각을 빼면 결과 문장이 같았다. |
| 생명주기 | 통과. 중지·재개 3회 동안 중지하면 두 포트가 닫히고 호출이 실패했으며, 재개하면 다시 성공했다. 동시 호출 시 읽기 슬롯을 공유해 한쪽이 `BUSY`를 받았다. |
| 공개 범위 | 통과. `127.0.0.1`에만 바인드됐다. `Origin` 헤더가 있거나 Host가 `127.0.0.1`이 아니면 403이다. 인증은 없다. |
| 속도 | 통과. 도구 호출 중앙값이 내장 29~31ms, 기존 Node 경로 108ms였다(각 10회 × 2). 차이의 대부분은 기존 Bridge의 `awaitReply`가 응답 준비를 100ms(`PEER_POLL_MILLIS`) 단위 소켓 읽기 타임아웃으로 확인하는 데서 온다. Node 경로는 20회 모두 106ms 이상이었다. 내장 방식 고유의 이점으로 보지 않는다. |
| 자원 | 통과(기록). debug APK +6.2MB, release APK +4.7MB. PSS는 main 54~62MB, 내장 78~94MB였다. ANR·OOM·FGS 강제 종료는 없었다. |

## 대안

- **SDK 0.16.0 (Kotlin 2.4 필요):**
  - 0.15 이상은 kotlin-stdlib 2.4를 요구한다. 컴파일러 2.2는 이를 읽지 못한다.
  - stdlib만 2.2.10으로 고정하면 실행 중 Ktor에서 `NoClassDefFoundError`가 난다.
  - KGP 2.4.21로 덮어쓰면 동작한다. 하지만 저장소 규칙(버전 상향 금지)에 걸리고 저장소 전체 빌드에 영향을 준다. 지금은 채택하지 않는다.
- **stateful Streamable HTTP:**
  - 공식 TS 클라이언트의 `close()`는 세션 삭제(DELETE)를 보내지 않는다.
  - 세션 상한 8에서 DELETE 없이 떠난 클라이언트가 쌓이자 새 접속이 503으로 거부됐다.
  - stateless에서는 같은 시험의 50회 접속이 모두 성공했다.
  - 서버가 먼저 보내는 알림·질문(elicitation 등)은 쓸 수 없다. 읽기 전용 도구에는 필요하지 않다.
- **SDK 없이 직접 구현:** 프로토콜 변경을 직접 따라가야 한다. SDK 경로가 조건을 통과했으므로 시도하지 않았다.
- **No-Go:** Node·TypeScript를 빼고 Kotlin + Android로 기술 스택을 통합하는 이점을 버리게 된다.

## 결과

**장점**
- PC의 Node 프로세스 없이 앱이 MCP를 직접 제공한다.
- 기술 스택이 Kotlin + Android 하나로 줄어든다.
- 속도는 이점으로 보지 않는다. 측정된 차이는 대부분 기존 Bridge의 100ms 대기 주기에서 나왔다. 이 주기는 Node 경로에서도 따로 고칠 수 있다.

**비용**
- 런타임 의존성이 78개에서 131개로 늘고, 다음이 올라간다.
  - stdlib 2.2.10 → 2.3.21
  - coroutines 1.9 → 1.11
  - serialization 1.7 → 1.11
  - Ktor, kotlinx-io, kotlin-logging 추가
- SDK 0.15 이상으로 올리려면 Kotlin 2.4 결정이 먼저 필요하다.

**이전 작업량 추정**
- 도구 1개(items 형식)를 옮기는 데 앱 코드 약 300줄이 들었다(서버, 결과 이식, 엔드포인트 묶음).
- 남은 19개 도구에 필요한 것:
  - scalar 결과 형식 이식
  - 입력 스키마(`properties`·`areas`) 생성
  - 도구 이름과 설명 공유
  - `tool-inputs.json` 대조 테스트
- 도구 설명이 `mcp/src/tool-texts.ts`에 있으므로, 이를 공유하는 것은 계약 변경으로 다룬다.

**남은 위험**
- 인증이 없다. Host/Origin 검사는 인증이 아니다. `127.0.0.1`은 같은 기기의 다른 앱도 접근할 수 있어서, 차량 권한이 없는 앱이 이 서버를 거쳐 값을 읽을 수 있다. 지금의 18765 Bridge도 같은 문제를 가진다. 실차 전에 인증이 필요하다.
- 외부 AI가 PC·ADB 없이 앱에 닿는 경로는 여전히 없다(COM-15).
- 클라이언트마다 HTTP 연결 지원이 다르다. Codex CLI와 Claude Code는 URL로 연결됐다. Claude Desktop의 설정 파일 방식은 확인하지 않았다. 로컬 HTTP를 바로 등록할 수 없으면 stdio↔HTTP 중계가 필요하다.
- 실제 SDK로 대조한 속성은 에너지뿐이다.
- 앱마켓 보안 가이드의 HTTPS·노출 최소화 원칙에 loopback HTTP가 해당하는지 확인하지 않았다.

**COM-12에서 할 일**
- Node 경로와의 계약 동등성 테스트
- 실제 AI E2E 재검증
- Node 경로로 되돌리는 롤백 검증
- ADR-0002를 대체하는 ADR 작성(전송·권한·생명주기·외부 접근·롤백)
- C4 모델 갱신
