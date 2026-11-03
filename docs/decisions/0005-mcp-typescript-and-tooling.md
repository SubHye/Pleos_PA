# ADR-0005: MCP 서버의 TypeScript 실행과 검사 도구

- 날짜: 2026-11-03
- 상태: 제안 (COM-19 PR에서 상대 개발자 승인 필요)

## 맥락
요구사항 정의서는 MCP 서버를 Node.js·TypeScript 계층으로 적지만, PoC와 COM-16의 코드는 `.mjs`였다. COM-16은 PoC를 따랐을 뿐 언어를 정하지 않았다. COM-19가 언어와 oxlint·oxfmt 도입 여부를 정해야 한다.

## 결정
- `mcp/`는 TypeScript로 쓰고 빌드 없이 `node src/server.ts`로 실행한다. Node 24.15.0의 내장 type stripping을 쓰며, 산출물·`tsx`·`ts-node`·`--experimental-transform-types`를 쓰지 않는다. Node 문서상 이 기능은 v24.12.0부터 stable이다.
- 지울 수 있는 타입 문법만 쓴다(`erasableSyntaxOnly`). 타입 검사는 Node가 하지 않으므로 `tsc`로 한다.
- 정확히 고정한다: typescript 7.0.2, `@types/node` 24.19.2, oxlint 1.90.0, oxfmt 0.75.0.
- oxlint는 `correctness` 범주만 쓰고 경고도 실패로 처리한다. 타입 정보 규칙은 `oxlint-tsgolint`가 더 필요하고 타입 검사가 `tsc`와 겹치므로 쓰지 않는다.
- oxfmt는 `mcp/`의 `.ts`만 대상으로 하고, import와 `package.json` 정렬을 끈다. `package-lock.json`, 설정 JSON, Markdown, `contracts/`, `docs/`, Kotlin은 건드리지 않는다.

## 대안
- `.mjs` 유지: 변경이 가장 작지만 요구사항과 다르고 타입 검사가 없다. 응답 형식이 많은 계약 코드에서 타입 검사의 이득을 포기하게 된다.
- `tsc`로 빌드: 실행 방식이 더 익숙하지만 클라이언트 등록 전에 빌드 단계가 생기고 산출물과 소스가 어긋날 수 있다.
- oxfmt 미도입: oxfmt는 도입 시점에 0.75.0으로 1.0 전이다. 사용자 결정으로 도입하되, 버전을 올릴 때 포맷이 바뀔 수 있으므로 정확히 고정한다.

## 결과
빌드 없이 소스로 바로 실행하고 Android와 같은 PR에서 검사한다. 대신 `NODE_OPTIONS=--no-strip-types` 환경이나 낮은 Node에서는 서버가 시작되지 않고, `node_modules` 아래의 TS는 실행되지 않으므로 패키지로 배포하려면 빌드 단계와 이 ADR의 대체가 필요하다. oxfmt 0.x를 올릴 때는 재포맷 diff를 별도 커밋으로 검토한다.
