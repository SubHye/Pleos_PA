# vehicle-sweep

에뮬레이터에서 앱의 MCP 도구를 모두 실행해, 카탈로그의 `verification`과 `adoption`을 판정할 보고서를 만든다. 테스트 지원 도구이며 앱에 들어가지 않는다. COM-12에서 Node의 `mcp/test-support/vehicle-sweep.ts`를 Kotlin으로 옮겼다.

## 실행

먼저 앱에서 연결을 시작하고 forward를 만든다.

```powershell
adb -s <기기 serial> forward --no-rebind tcp:18766 tcp:18766
adb -s <기기 serial> forward --no-rebind tcp:8088 tcp:8088   # 대조표를 쓸 때만
.\gradlew.bat :vehicle-sweep:run --args="build/pleos-emulator-test/<RunId>/sweep.json tools/vehicle-sweep/console-map.json"
```

- 도구마다 인자 없이 5회 호출한다. 한 번에 하나씩 호출하고 재시도하지 않는다.
- 어느 호출에서도 분류되지 않은 속성은 `properties` 인자로 한 번 더 따로 부른다.
- 응답의 `source`가 `pleos_emulator`가 아니면 멈춘다.
- 대조표를 주면 Virtual Console 값을 바꿔 SDK 값과 비교하고, 끝나면 원래 값으로 되돌린다.
  - Console 페이지가 열려 있어야 한다.
  - `http://127.0.0.1:8088`의 비공식 경로를 쓰므로, 에뮬레이터 버전이 바뀌면 경로를 다시 확인한다.
- 대조표에는 Console 쓰기 후 값이 바뀌고 되돌릴 수 있는 항목만 둔다. 주행 거리처럼 줄일 수 없는 값은 넣지 않는다.
- 보고서는 커밋하지 않는다. 결과는 카탈로그, PR 본문, SDK 지원 기능표에 옮긴다.

## Node 판과 다른 점

- **판정 대상:** MCP 서버가 내놓는 도구, 즉 카탈로그에서 `adopted`인 도구만 판정한다.
  - 나머지 도구는 보고서의 `notSwept`에 이름만 남는다.
  - 그 도구 속성의 `verification`은 보고서에 나오지 않으므로 카탈로그 값을 유지한다.
  - `rejected` 도구를 다시 판정하려면 그 도구를 잠시 `adopted`로 바꾼 빌드에서 실행해야 한다.
- **응답 크기:** `responseBytes`는 게이트웨이 응답이 아니라 MCP 결과의 structuredContent 크기다.
- **시간:** MCP 호출 시간이 들어간다.
