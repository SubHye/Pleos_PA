# 약속 출발 검수

Codex PC 채팅에서 **연결된 Notion 테스트 일정 → 제품 앱 MCP → 순정 내비 → 필요할 때만 Slack 초안/승인**을 확인한다. 기존 연결을 사용한다. ChatGPT 터널·새 모델 API·메시지 봇·모의 MCP 서버는 추가하지 않는다.

`departure_check.py`는 외부 AI가 받은 최소 결과로 시간 비교를 교차 확인하는 선택적 호스트 도구다. Android APK/MCP에 등록하지 않으며, 일정·대화·메일 원문을 차량 앱에 전달하지 않는다. 네트워크/API/차량 변경/메시지 전송 코드는 없다. 결과는 항상 `maySend=false`다. 이 값이 외부 Slack 도구를 강제하거나 전송 승인을 대신하는 것은 아니다.

## 빠른 실행 순서

1. 같은 Codex PC 대화에서 Notion 읽기, 실제 `pleos-pa-vehicle` MCP, Slack 테스트 채널 접근을 확인한다. 유료 플랜·제품/버전·모델·승인 설정과 빌드 SHA를 로컬 증거에 기록한다. 구 모의 MCP는 사용하지 않는다. 다른 ChatGPT 화면/다른 대화의 연결 성공으로 대체하지 않는다.
2. 연결된 Notion DB에 고유한 `COM28-<회차>` 테스트 일정을 준비한다. 내용은 합성이지만 실제 Notion에 생성·재조회한 테스트 데이터임을 구분한다. 참석자는 넣지 않는다. 본문 전체가 아닌 시작 시각(명시적 UTC offset)과 고정 장소만 사용한다.
3. 실제 내비 변경 전 [Pleos 검수 스킬](../../.agents/skills/pleos-emulator-test/SKILL.md)의 preflight를 실행해 Pleos 기기/사용자/패키지/기존 포워딩을 확인한다. P 상태의 대상 에뮬레이터 한 대만 사용한다. 기존 앱·AVD·ADB 서버를 삭제/초기화하지 않는다.
4. 실행 목록을 제시하고 승인받는다. 기본 목록: 정상1 판교역, 정상2 현대백화점 판교점으로 최대 각1회 변경. 나머지 지연/거절 검사는 두 번째 목적지를 유지해 `ALREADY_SET`/조회 경로를 사용한다. 이전 목적지는 복구 가능 여부를 먼저 확인한다. 경유지·옵션까지 완전히 복원한다고 약속하지 않는다. 목록 밖의 변경·재실행·취소는 별도 승인 전 실행하지 않는다.
5. 정상2회 → 지연 초안 → 명시 거절을 우선 검수한다. 실제 발송은 **이번 테스트 채널/수신자와 최종 본문**을 제시한 새 명시 승인 뒤1회만 한다. 승인 전/거절/취소/수신자·본문 변경 뒤 새 승인 전에는 전송0건이어야 한다. 채널 ID와 본문은 커밋하지 않는다.
6. 아래 증거를 회차별로 보관한다. 검증 불가 항목은 `BLOCKED`/`NOT_RUN`이며 합성 실패를 실전 실패로 바꾸지 않는다. 불필요한 재시도·외부 발송은 하지 않는다.

| 회차 | 준비 | 기대값 |
| --- | --- | --- |
| N1 | 판교역, 실제 ETA보다 충분히 늦은 시작 시각의 Notion 일정 | find SINGLE → set APPLIED/ALREADY_SET → get COMPLETE → 정상 안내. Slack 조회/초안/전송 도구 모두0회 |
| N2 | 현대백화점 판교점, 같은 정상 시간 조건 | 서로 다른 목적지로 N1과 같은 결과. 정상 기능2회 판정 |
| L1 | 현재 목적지와 같은 장소, 현재보다 미래이면서 실제 ETA보다 이른 일정 | get의 ETA와 일정 offset 비교 → 지연 예상·수신자/초안 제시. 승인 전 전송0회 |
| D1 | L1 초안을 명시 거절/취소 | Slack 전송0회. 승인 대기/답변 시간을 전체 시간에서 분리 |
| C1 | 초안 승인 후 수신자 또는 본문 변경, 새 승인은 거절 | 수정본 전송0회. 승인 범위 재확인 |
| S1 | 승인된 내부 테스트 대상/본문이 있는 L1 | 최종 승인 뒤1회 전송, 실제 게시물과 대조. 반환 불명/실패면 자동 재전송0회 |
| E1 | Notion 조회0건 / 날짜만 있음 / 장소 후보 없음·모호 | 추가 확인 또는 중단. 설정·지연 판단·전송 없음 |
| E2 | Notion/MCP 권한 거부·연결 실패 / ETA 누락 | 값 추측·전체 성공 안내·자동 재시도 없음 |
| E3 | 실제 Slack 전송 미지원·실패·결과 불명 | 미지원은 초안, 실패/불명은 그대로 안내. 재현 못하면 미검증 |

L1은 임의의 남은 시간을 만들지 않는다. 현재 실제 ETA와 현재 시각 사이에 테스트 시작 시각을 둘 여유가 없으면 실행하지 않고 조건을 다시 준비한다. 이미 시작한 일정은 자동 연락하지 않고 사용자에게 확인한다. 5초는 부가 성능 목표이며 초과만으로 기능 실패로 세지 않는다.

## Codex PC에 붙여 넣을 실행 지침

```text
COM-4/28 검수를 진행한다. 먼저 같은 대화에 Notion, 실제 Pleos MCP,
Slack 도구가 모두 있는지 읽기 전용으로 확인하고 제품/모델/승인 설정을 기록해줘.
외부 앱이나 차량을 변경하기 전에 테스트 DB/수신 채널과 이번 실행 목록을 제시해줘.

일정은 내가 지정한 COM28 테스트 레코드만 읽어. 없거나 시간이 불명확하면
추측하지 말고 멈춰. 명시적 offset이 있는 시작 시각과 검증된 장소만 사용해.
find_navigation_place가 MULTIPLE이면 선택을 물어보고, NO_MATCH면 중단해.
내가 요청하고 승인한 실행 목록 안에서만 set_navigation_destination을 호출해.
APPLIED/ALREADY_SET 이후 get_navigation_destination의 실제 결과를 다시 읽어.
OUTCOME_UNKNOWN, 조회 실패, 시간 누락이면 지연 판단/연락을 진행하지 마.

정상 도착 예상이면 Slack 도구를 하나도 호출하지 마.
지연 예상이면 대상 워크스페이스/채널/수신자와 최종 본문을 제시하고 기다려.
내가 그 최종 대상과 본문에 명시적으로 전송 승인하기 전에는 보내지 마.
거절/취소/수신자·본문 변경 후에는 이전 승인을 재사용하지 마.
전송 도구 미지원이면 초안까지만, 실패/결과 불명이면 그대로 알리고 재전송하지 마.

일정·메일·대화 원문을 차량 앱에 전달하지 마. 차량 입력은 장소 검색에 필요한
장소 문자열/확인된 후보 ID뿐이야. 결과의 source/requestId/시각을 대조해줘.
실행별 도구 순서·회수·결과·최종 응답과 t0/t1, 사람 대기 시간을 로컬에 기록하되
공유 요약에는 일정 원문·수신자·메시지 본문·토큰을 빼줘.
성공뿐 아니라 실패 시행도 남기고 실제 SDK/합성 입력/직접 관찰을 구분해줘.
```

## 시간 비교 도구

Python 3.9+ 표준 라이브러리만 사용한다. 도구가 없는 PC에서는 위 실제 검수를 먼저 진행할 수 있으며, 이 도구는 AI의 시간 비교를 교차 확인하는 보조 수단이다.

```powershell
python .\tools\departure-check\departure_check.py .\tools\departure-check\case.snapshot.json
```

로컬 파일 형식은 `appointment.startAt`, 검증된 `appointment.candidateId`, 실제 MCP 응답의 `destinationSetting`/`navigation` structuredContent다. 원래 JSON-RPC/SSE envelope 전체가 아니다. 아래 시각과 결과는 **형식 설명용 합성 예**이며 실제 E2E 근거가 아니다.

```json
{
  "appointment": {"startAt": "2026-11-12T10:30:00+09:00", "candidateId": "pangyo_station"},
  "destinationSetting": {
    "ok": true, "source": "pleos_emulator", "status": "APPLIED",
    "destination": {"candidateId": "pangyo_station"}
  },
  "navigation": {
    "ok": true, "source": "pleos_emulator", "status": "COMPLETE",
    "destination": {"name": "판교역"},
    "receivedAt": "2026-11-12T01:00:00Z", "remainTimeSeconds": 1200,
    "estimatedArrivalAt": "2026-11-12T01:20:00Z"
  }
}
```

합성 재생만 `--now 2026-11-12T01:00:00Z`를 사용한다. 실제 검수에서 과거 snapshot에 맞춰 현재 시각을 바꾸지 않는다. 수신 후60초 초과/미래 시각은 기본 중단한다. 60초는 보수적인 데모 작업 기준이며 SDK의 데이터 최신성 보장이 아니다. ETA는 계약대로 `receivedAt + remainTimeSeconds`인 앱 추정치이며 교통 보장값이 아니다. `PARTIAL`은 이번 보조 도구에서 중단한다.

이 도구는 **입력으로 받은 주장**을 검사할 뿐 실제 Notion 연결/SDK 실행/Slack 미호출을 입증하지 않는다. `source` 문자열이나 helper의 ON_TIME/LATE만으로 PASS를 주지 않는다. 출력의 scope는 항상 `derived_input_check_not_e2e_evidence`다. 실제 도구 로그와 내비 화면 대조가 별도로 필요하다.

## 증거와 자동 검사

회차별 입력·도구 순서·실제 내비 상태 일치 여부·MCP requestId·응답 코드/source·최종 답변·t0/t1·사용자 대기 시간을 로컬에 보관한다. 공유 결과는 마스킹한다. 실행자 실명은 Linear에만 기록한다. 2개 정상 시행의 기능 판정과 전체 시행 분모, 성능 목표를 분리한다. 음성 모드/비교 AI/재현 불가 오류는 별도 NOT_RUN 또는 제한으로 남긴다.

```text
python3 -m unittest discover -s tools/departure-check -p 'test_*.py' -v
```

자동 검사는 합성 데이터의 시간대·분기·실패 중단만 확인한다. 실제 E2E의 정상2회/지연/승인/발송 횟수에 합산하지 않는다.
