# Pleos_PA MCP 서버

외부 AI 클라이언트와 Android 앱 사이의 stdio MCP 서버다. 언어와 도구 결정은 [ADR-0005](../docs/decisions/0005-mcp-typescript-and-tooling.md), 도구·오류 규칙은 [차량 조회 계약](../contracts/vehicle-read/CONTRACT.md)을 따른다.

## 현재 상태

- **`tools/list`는 비어 있다.** 계약은 실행 검증을 통과한 도구만 올리도록 정하고, 아직 통과한 도구가 없다. `src/server.ts`의 `ADOPTED_TOOLS`에 COM-23이 항목을 추가한다. 기본 실행 경로는 인자나 환경 변수로 도구를 받지 않는다.
- **차량 값을 읽을 수 없다.** 이 저장소에는 Android `/v3/vehicle-read` Bridge(COM-18)와 Vehicle Adapter(COM-20)가 아직 없다.
- 테스트의 Bridge는 모두 가짜이며 실제 SDK 결과가 아니다.
- PoC의 ChatGPT·Claude 성공 기록은 이 서버의 기록이 아니다.

## 실행 환경

| 항목 | 기준 |
| --- | --- |
| Node | 24.15.0(`.node-version`), 최소 `>=24.15.0` |
| 실행 | `node src/server.ts`. 빌드 없음 |
| 문법 | 지울 수 있는 타입 문법만. 타입 import는 `import type`, 상대 경로는 `.ts` 확장자 |
| 타입 검사 | Node는 `tsconfig.json`을 읽지 않으므로 `npm run typecheck`로만 검사한다 |
| 위치 | 저장소 경로에서 실행한다. Node는 `node_modules` 아래의 TS를 실행하지 않는다 |

`NODE_OPTIONS=--no-strip-types`가 설정된 환경에서는 `ERR_UNKNOWN_FILE_EXTENSION`으로 시작하지 못한다(Node 24.15.0에서 확인).

## 설치와 검사

`mcp/`에서 실행한다.

```powershell
npm ci
npm run typecheck
npm run lint
npm run format:check
npm test
```

`npm test`는 고정 포트 18765가 비어 있으면 그 포트에 가짜 Bridge를 띄워 기본 서버의 요청 0회까지 확인한다. `adb forward`가 그 포트를 쓰고 있으면 이 확인만 건너뛰고 진단 메시지를 남긴다.

## ADB 연결

서버는 `127.0.0.1:18765`로 요청하고, 그 포트를 Android 앱으로 잇는 것은 사용자가 만든 forward다. 서버는 ADB를 실행하지 않는다.

```powershell
adb devices -l
adb forward --list
adb -s <기기 serial> forward --no-rebind tcp:18765 tcp:18765
```

- 기기 하나를 지정한다. `tcp:18765`가 이미 있거나 `--no-rebind`가 실패하면 다른 작업이 쓰는 포트이므로 덮어쓰지 않는다.
- 제거: `adb -s <기기 serial> forward --remove tcp:18765`

Host/Origin 검사는 프로세스 인증이 아니다. forward가 켜져 있는 동안 이 PC의 다른 로컬 프로세스도 같은 포트로 요청할 수 있다. 쓰지 않을 때는 forward를 제거한다. 외부 네트워크에 공개하지 않는다.

## 클라이언트 연결

명령은 공통이다. Node 절대 경로와 저장소의 서버 파일을 쓴다. 토큰과 환경 변수는 필요 없다. 앱마다 자기 프로세스를 실행한다.

| 항목 | 값 |
| --- | --- |
| 서버 이름 | `pleos-pa-vehicle` |
| 명령 | `C:\Program Files\nodejs\node.exe` |
| 인자 | `<저장소>\mcp\src\server.ts` |

| 표면 | 등록 경로 | 1차 대상 |
| --- | --- | --- |
| ChatGPT Desktop 로컬 Codex 호스트 | Settings → MCP servers → Add server → STDIO. 설정은 `~/.codex/config.toml`에 저장되고 Codex CLI와 공유한다. 연결 확인은 composer의 `/mcp` | 대상 |
| Claude Desktop | `%APPDATA%\Claude\claude_desktop_config.json`의 `mcpServers`에 추가한 뒤 앱을 완전히 재시작 | 대상 |
| ChatGPT 웹 커스텀 MCP 앱 | 공개 HTTPS 또는 Secure MCP Tunnel만 가능. 로컬 stdio를 직접 연결하지 못한다 | 대상 아님. 네트워크 공개·터널은 [ADR-0002](../docs/decisions/0002-boundaries-and-transport.md) 범위 밖이라 별도 결정이 필요하다 |

Claude Desktop 설정 예시:

```json
{
  "mcpServers": {
    "pleos-pa-vehicle": {
      "command": "C:\\Program Files\\nodejs\\node.exe",
      "args": ["<저장소>\\mcp\\src\\server.ts"]
    }
  }
}
```

Codex CLI와 Claude Code의 연결은 데스크톱 클라이언트의 증거로 쓰지 않는다. 클라이언트별 실제 연결 결과는 PR 본문에 기록한다.
