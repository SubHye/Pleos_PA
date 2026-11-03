// Explicit opt-in test entry point. No Android, ADB, Calendar or Slack access.
import { serve } from '../src/server.ts';
import { energyReply, json, startBridge } from './fake-bridge.ts';

const bridge = await startBridge((body, res) => json(res, 200, energyReply(String(body.requestId))));
let closing = false;
const close = () => {
  if (closing) return;
  closing = true;
  void bridge.close().catch(() => {
    process.stderr.write('pleos-pa-com10: MOCK_BRIDGE_CLOSE_FAILED\n');
    process.exitCode = 1;
  });
};
process.stdin.once('end', close);
process.stdin.once('close', close);
process.once('SIGINT', close);
process.once('SIGTERM', close);

serve({
  port: bridge.port,
  tools: [
    {
      name: 'get_energy_level',
      title: '[COM-10 MOCK] Energy level',
      description:
        '[MCP 모의 결과] COM-10 도구 결합 테스트 전용. 연료와 EV 배터리를 각각 고정값 50%로 반환합니다. 실제 SDK·차량 조회가 아닙니다. 최종 답변과 메시지에도 모의 결과임을 표시하세요.',
    },
  ],
});
