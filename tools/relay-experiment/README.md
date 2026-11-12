# relay-experiment

COM-55 실험용 중계 서버다. `AI 클라이언트 ──HTTPS──▶ 중계 서버 ◀──차가 먼저 건 WebSocket── 차량 앱` 구조가 성립하는지 확인하는 데만 쓴다. 테스트 지원 도구이며 앱에 들어가지 않는다. 이 브랜치는 main에 병합하지 않는다.

제품 기능이 아니다. 요구사항 정의서는 1차 범위에서 중계 서버의 실제 운영을 제외하고, NFR-02는 엔드포인트를 개발 PC 범위로 제한한다.

## 동작

- `127.0.0.1`에만 바인딩한다. 바인딩 주소를 바꾸는 인자는 없다. 밖에서 닿게 하는 것은 앞에 세운 터널뿐이다.
- `GET /car`: 차량 앱이 `Authorization: Bearer <차 토큰>`으로 WebSocket을 건다. 토큰이 틀리면 404, 이미 한 대가 연결돼 있으면 409다.
- `POST /r/<비밀>/mcp`: 요청 본문을 프레임 하나로 차에 보내고, id가 같은 응답 프레임을 돌려준다. 비밀이 틀리면 404다.
- 채택된 차량 조회 도구가 아닌 `tools/call`은 차로 보내지 않고 -32602로 답한다. 이것은 한 겹 더 둔 방어다. 내비 도구를 실제로 막는 것은 차 쪽이다. 앱은 내비 도구를 넣지 않은 두 번째 MCP 엔드포인트만 중계에 연결한다.
- 비밀과 차 토큰은 실행마다 새로 만들어 `build/relay-experiment/run.json`에만 쓰고, 종료하면 지운다. 콘솔과 로그에는 나오지 않는다.
- 로그에는 이벤트, 프레임 id, MCP 메서드, 도구 이름, 상태, 소요 시간만 남는다.

| 상황 | 응답 |
| --- | --- |
| GET, DELETE | 405 |
| JSON이 아닌 Content-Type | 415 |
| JSON 배열, 해석 불가 | 400 |
| 본문 64KiB 초과 | 413 |
| 차 미연결, 동시 8건 초과 | 503 |
| 15초 안에 응답 프레임 없음 | 504 |
| 요청 중 차 끊김, 차 쪽 로컬 실패 | 502 |

인증은 경로의 비밀 하나뿐이다. 사용자나 차량을 확인하지 않는다. 비밀은 URL에 들어가므로 터널 제공자와 AI 서비스 쪽에 남는다.

## 실행

저장소 루트에서 실행한다. `gradlew run`이 아니라 설치된 스크립트로 실행해야 Ctrl+C에 `run.json`이 지워진다.

```powershell
.\gradlew.bat :relay-experiment:installDist
tools\relay-experiment\build\install\relay-experiment\bin\relay-experiment.bat --port 18767
```

차량 앱(debug 빌드)에 주소와 토큰을 넣고, 앱에서 연결을 시작한다. 값은 프로세스 메모리에만 있고 다음 연결 시작부터 적용된다.

```powershell
$run = Get-Content build\relay-experiment\run.json -Raw | ConvertFrom-Json
adb -s <기기 serial> reverse --no-rebind tcp:18767 tcp:18767
adb -s <기기 serial> shell am broadcast --user 10 -n com.example.pleos_pa/.relay.RelayConfigReceiver --es url ws://127.0.0.1:18767/car --es token $run.carToken
```

- 앱이 받는 주소는 `wss://<호스트>/car` 또는 `ws://127.0.0.1:<포트>/car`뿐이다.
- 상태는 logcat의 `RelayExperiment` 태그(`RELAY_CONFIG=…`, `RELAY_LINK=…`)로 본다.
- 끊기면 2초, 5초, 10초 뒤 세 번까지 다시 건다. 그 뒤에는 연결을 중지하고 다시 시작해야 한다. 요청은 다시 보내지 않는다.
- 설정을 지우려면 `--ez clear true`로 보낸다.

터널을 켜는 것은 네트워크 공개다. 실행마다 사용자 승인을 받는다.

```powershell
cloudflared tunnel --url http://127.0.0.1:18767
```

## 정리

1. 앱에서 연결을 중지한다. 공개 주소가 503을 답해야 한다.
2. `cloudflared`를 끝낸다. 주소가 더 이상 답하지 않아야 한다.
3. 중계 서버를 끝낸다. 18767 리스너와 `run.json`이 없어야 한다.
4. AI 서비스에 등록한 서버를 지운다.
5. `adb reverse --remove tcp:18767`을 실행하고, 실험 빌드 대신 일반 빌드를 설치한다.

## 검사

```powershell
.\gradlew.bat :relay-experiment:test :app:testDebugUnitTest
```

실제 loopback 소켓과 SDK 대역으로 프레임, 거절, 로그, 차 쪽 내비 제외를 검사한다. 차량 기능 검증이 아니고 Android 네트워크도 검증하지 않는다. CI는 `:relay-experiment:test`를 실행하지 않는다.
