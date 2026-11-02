# 약속에 맞춰 출발하기 (목표 시나리오, 미구현)

SDK 목적지 설정·ETA 및 동일 AI 환경(ChatGPT·Claude)의 일정/메시지 연동이 지원되는지 먼저 검증한다 (COM-8, COM-10). 도구 결합이 확인된 AI로 판정한다. Gleo 연동은 사용하지 않는다.

```mermaid
sequenceDiagram
    actor User as 사용자
    participant AI as 대상 AI
    participant Calendar as 일정 도구
    participant MCP as 외부 Node MCP
    participant App as Android Bridge/Adapter
    participant Navi as 순정 내비 SDK
    participant Message as 연락 도구
    User->>AI: 약속 출발 준비, 늦으면 연락
    AI->>Calendar: 일정 조회 (권한 확인)
    Calendar-->>AI: 시간·장소 또는 오류
    Note over AI,Navi: 일정/장소 모호·미지원이면 확인 또는 중단
    AI->>User: 목적지 변경 확인 (필요한 승인)
    User-->>AI: 승인
    AI->>MCP: 확인된 목적지 설정·시간 조회
    MCP->>App: Bridge 요청
    App->>Navi: 권한·지원·주행 규제 확인 후 호출
    Navi-->>App: 실제 결과·시간 정보
    App-->>User: 차량 화면에 변경 결과 표시
    App-->>MCP: 결과·오류·수신 시각
    MCP-->>AI: 검증된 응답
    alt 시간 정보 부족/실패
        AI-->>User: 지연 판단 불가, 추정/자동 연락 안 함
    else 정상 도착
        AI-->>User: 내비 결과 안내, 메시지 호출 없음
    else 지연 예상
        AI->>Message: 수신자·초안 준비
        AI->>User: 수신자·내용 확인 및 전송 승인
        alt 승인 및 전송 권한 있음
            User-->>AI: 전송 승인
            AI->>Message: 전송
            Message-->>AI: 실제 결과
            AI-->>User: 내비와 전송 결과 각각 안내
        else 거절/권한 없음/전송 미지원
            AI-->>User: 전송하지 않음, 초안 수준 명시
        end
    end
```

목적지 변경 승인이 거절되면 SDK 변경 호출을 하지 않는다. AI 프롬프트만을 승인 강제 장치로 보지 않으며, 승인 검증이 성립하지 않으면 변경 도구를 제외한다. 변경 요청 후 timeout은 결과 불명으로 표시하고 자동 재시도하지 않는다.
ETA/약속 시간은 시간대·기준 시각·단위를 확인해 비교한다.

## 인수 기준

정상·지연 분기를 각각 증명해야 통합 경험 완료로 판정한다. 증명하지 못하면 단일 통합 작업의 성공으로만 기록한다. 기능 기준은 [요구사항](https://linear.app/wonsub/document/요구사항-정의서-1b7e6fcd5721) 4.7, 횟수와 시간 목표는 6.2를 따른다.

- 정상 도착: 일정/내비 결과 안내, 메시지 도구 호출 없음.
- 지연 도착: 수신자·초안 확인 → 승인/권한 후 전송 → 실제 결과 안내.
- 거절·권한 없음·일정/장소 불명·ETA 미지원·연결 실패: 추가 변경/전송을 하지 않고 실패나 초안 수준을 표시.

전체 시행을 기록하고 성공 사례만 선별하지 않는다. commit/APK, SDK·Emulator·AI 제품과 버전, 입력, 실제 결과, 소요 시간, 판정을 남기고 비밀과 개인정보는 제거한다. 모의 응답과 실제 SDK 동작을 구분한다.
