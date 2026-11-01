# 검증

## 로컬/CI 공통

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Unix: `bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
JUnit 결과는 app/build/test-results/testDebugUnitTest, Lint는 app/build/reports/lint-results-debug.html에서 확인한다.
기존 ExampleUnitTest는 산술 검사 한 개다. 차량/Bridge 검증이 아니다. Instrumentation 검사는 이번 CI 범위에 포함하지 않는다.

DSL: [아키텍처 도구 안내](../architecture/README.md)의 고정 버전 validate. Markdown 링크·실제 소스와의 일치도 리뷰한다.
MCP: 현재 미구현. `mcp/` 이전 후 `npm ci` → `npm test`. CI는 manifest가 생기면 lock/Node 버전 파일 누락을 실패로 처리하고 `--if-present`로 검사를 생략하지 않는다.

## 기능 이전 시

- Android Adapter/Bridge와 Node 양쪽에서 요청·응답·오류·취소·BUSY·timeout 계약 회귀 검사.
- Service 화면 전환, 중지/재개, 앱 종료와 ADB 해제는 Pleos 에뮬레이터에서 별도 확인.
- SDK 지원/권한/단위·영역과 Virtual Console 실제 설정값을 대조. 모의 응답/코드 존재/빌드 성공을 실제 SDK 동작과 구분.
- 내비 변경·메시지는 사용자 승인과 테스트 대상을 확보한 경우에만 실행.

## 대표 시나리오 인수

정상 도착: 일정/내비 결과 안내, 메시지 도구 호출 없음.
지연 도착: 수신자·초안 확인 → 승인/권한 후 전송 → 실제 결과 안내.
거절·권한 없음·일정/장소 불명·ETA 미지원·연결 실패: 추가 변경/전송을 하지 않고 실패나 초안 수준을 표시.
변경 전달 후 timeout은 결과 불명으로 처리하며 재시도로 중복 실행하지 않는다.

최신 [요구사항 6.2](https://linear.app/wonsub/document/요구사항-정의서-1b7e6fcd5721)에 따라 채택한 정상 시나리오별 최소 2회, 자연어 제출부터 최종 응답 완료까지 5초 이내와 도구 선택/SDK 실행/값·단위/최종 응답 정확성을 모두 판정한다. 승인 대기도 포함하고 별도로 기록한다. 10초 내부 timeout과 5초 제품 목표를 혼동하지 않는다. 전체 시행을 분모로 기록하며 성공 사례만 선별하지 않는다. 오류 시나리오는 별도로 판정한다.

기록: 실행자·확인자, commit/APK, SDK·Service·Emulator·AI 제품/버전/계정·연결 방식, 입력/기준값, requestId, 실제 결과, t0/t1, 판정·제약. 비밀과 개인정보는 제거한다. 현재 종단간 목표는 미측정이며 실차·모바일·핸즈프리는 미검증이다.
